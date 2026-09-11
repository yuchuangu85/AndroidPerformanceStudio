/**
 * Port of SourceProvider.kt and RemoteSourceProviders.kt.
 *
 * A provider turns a workspace config plus a revision into a file list and file
 * bytes. The remote providers never follow a redirect: a token must not be
 * replayed against another host, so a 3xx is reported as a failure.
 */
import type { SourceProviderConfig, SourceProviderKind } from './model.js';

export interface ProviderSourceFile {
  readonly relativePath: string;
  readonly sizeBytes: number;
  /** Provider side hash when it has one (a git blob id); a local tree has none. */
  readonly contentHash: string | null;
}

export interface SourceProvider {
  readonly kind: SourceProviderKind;
  resolveRevision(config: SourceProviderConfig): Promise<string>;
  listFiles(config: SourceProviderConfig, revision: string): Promise<ProviderSourceFile[]>;
  readFile(config: SourceProviderConfig, revision: string, relativePath: string): Promise<Uint8Array>;
}

export class SourceProviderRegistry {
  private readonly providers: ReadonlyMap<SourceProviderKind, SourceProvider>;

  constructor(providers: readonly SourceProvider[]) {
    this.providers = new Map(providers.map((provider) => [provider.kind, provider]));
  }

  providerFor(kind: SourceProviderKind): SourceProvider {
    const provider = this.providers.get(kind);
    if (provider === undefined) throw new Error('No source provider registered for ' + kind);
    return provider;
  }
}

export interface SourceHttpResponse {
  readonly statusCode: number;
  readonly body: Uint8Array;
}

export interface SourceHttpTransport {
  get(url: string, headers: Readonly<Record<string, string>>): Promise<SourceHttpResponse>;
}

export const SOURCE_HTTP_TIMEOUT_MS = 15_000;

export interface FetchSourceHttpTransportOptions {
  readonly timeoutMs?: number;
  readonly fetch?: typeof globalThis.fetch;
}

export function fetchSourceHttpTransport(options: FetchSourceHttpTransportOptions = {}): SourceHttpTransport {
  const implementation = options.fetch ?? globalThis.fetch;
  const timeoutMs = options.timeoutMs ?? SOURCE_HTTP_TIMEOUT_MS;
  return {
    async get(url, headers) {
      const response = await implementation(url, {
        method: 'GET',
        headers: { ...headers },
        redirect: 'error',
        signal: AbortSignal.timeout(timeoutMs),
      });
      return { statusCode: response.status, body: new Uint8Array(await response.arrayBuffer()) };
    },
  };
}

export interface SourceCredentialProvider {
  credential(key: string): string | undefined;
}

const COMMIT_SHA = /^[0-9a-fA-F]{40}$/;
const SOURCE_EXTENSIONS = new Set(['kt', 'kts', 'java', 'xml', 'c', 'cc', 'cpp', 'cxx', 'h', 'hh', 'hpp']);

function isSourcePath(path: string): boolean {
  const extension = path.includes('.') ? (path.split('.').pop() as string).toLowerCase() : '';
  return SOURCE_EXTENSIONS.has(extension);
}

function encode(value: string): string {
  return encodeURIComponent(value).replaceAll('+', '%20');
}

function encodePath(path: string): string {
  return path.split('/').map(encode).join('/');
}

export function requireSafeRelativePath(path: string): void {
  const segments = path.split('/');
  if (path.trim().length === 0 || path.startsWith('/') || segments.some((segment) => segment === '..')) {
    throw new Error('Unsafe source path: ' + path);
  }
}

function checkSuccessful(response: SourceHttpResponse, operation: string): void {
  if (response.statusCode < 200 || response.statusCode > 299) {
    throw new Error(operation + ' failed (' + String(response.statusCode) + ')');
  }
}

function decodeUtf8(bytes: Uint8Array): string {
  return new TextDecoder('utf-8').decode(bytes);
}

/** The googlesource JSON endpoints prefix their payload with a guard line. */
export function removeJsonGuard(text: string): string {
  const lines = text.split('\n');
  let index = 0;
  while (index < lines.length && lines[index].startsWith(")]}'")) index += 1;
  return lines.slice(index).join('\n');
}

export interface GitHubSourceProviderOptions {
  readonly transport: SourceHttpTransport;
  readonly credentials?: SourceCredentialProvider;
}

export class GitHubSourceProvider implements SourceProvider {
  readonly kind: SourceProviderKind = 'GITHUB';
  private readonly transport: SourceHttpTransport;
  private readonly credentials: SourceCredentialProvider;

  constructor(options: GitHubSourceProviderOptions) {
    this.transport = options.transport;
    this.credentials = options.credentials ?? { credential: () => undefined };
  }

  async resolveRevision(config: SourceProviderConfig): Promise<string> {
    const github = requireGitHub(config);
    if (COMMIT_SHA.test(github.ref)) return github.ref.toLowerCase();
    const response = await this.get(github, githubPath(github, '/commits/' + encode(github.ref)));
    checkSuccessful(response, 'GitHub revision');
    const parsed: unknown = JSON.parse(decodeUtf8(response.body));
    return String((parsed as { sha: string }).sha);
  }

  async listFiles(config: SourceProviderConfig, revision: string): Promise<ProviderSourceFile[]> {
    const github = requireGitHub(config);
    const response = await this.get(
      github,
      githubPath(github, '/git/trees/' + revision) + '?recursive=1',
    );
    checkSuccessful(response, 'GitHub tree');
    const parsed = JSON.parse(decodeUtf8(response.body)) as { tree?: unknown[] };
    const files: ProviderSourceFile[] = [];
    for (const entry of parsed.tree ?? []) {
      if (typeof entry !== 'object' || entry === null) continue;
      const item = entry as Record<string, unknown>;
      if (item['type'] !== 'blob') continue;
      const path = String(item['path'] ?? '');
      if (!isSourcePath(path)) continue;
      const size = item['size'];
      files.push({
        relativePath: path,
        sizeBytes: typeof size === 'number' ? size : 0,
        contentHash: typeof item['sha'] === 'string' ? (item['sha'] as string) : null,
      });
    }
    return files.sort(byRelativePath);
  }

  async readFile(config: SourceProviderConfig, revision: string, relativePath: string): Promise<Uint8Array> {
    requireSafeRelativePath(relativePath);
    const github = requireGitHub(config);
    const response = await this.get(
      github,
      githubPath(github, '/contents/' + encodePath(relativePath)) + '?ref=' + revision,
      'application/vnd.github.raw+json',
    );
    checkSuccessful(response, 'GitHub content');
    return response.body;
  }

  private async get(
    config: Extract<SourceProviderConfig, { kind: 'GITHUB' }>,
    path: string,
    accept = 'application/vnd.github+json',
  ): Promise<SourceHttpResponse> {
    const headers: Record<string, string> = {
      Accept: accept,
      'X-GitHub-Api-Version': '2022-11-28',
    };
    const credentialKey = config.credentialKey;
    if (credentialKey !== undefined && credentialKey !== null) {
      const token = this.credentials.credential(credentialKey);
      if (token !== undefined && token.trim().length > 0) headers['Authorization'] = 'Bearer ' + token;
    }
    return this.transport.get('https://api.github.com' + path, headers);
  }
}

export interface AospSourceProviderOptions {
  readonly transport: SourceHttpTransport;
}

export class AospSourceProvider implements SourceProvider {
  readonly kind: SourceProviderKind = 'AOSP';
  private readonly transport: SourceHttpTransport;

  constructor(options: AospSourceProviderOptions) {
    this.transport = options.transport;
  }

  async resolveRevision(config: SourceProviderConfig): Promise<string> {
    const aosp = requireAosp(config);
    if (COMMIT_SHA.test(aosp.ref)) return aosp.ref.toLowerCase();
    const response = await this.transport.get(showUrl(aosp, aosp.ref, '', 'JSON'), {});
    checkSuccessful(response, 'AOSP revision');
    const parsed: unknown = JSON.parse(removeJsonGuard(decodeUtf8(response.body)));
    return String((parsed as { commit: string }).commit);
  }

  async listFiles(config: SourceProviderConfig, revision: string): Promise<ProviderSourceFile[]> {
    const aosp = requireAosp(config);
    const pending: string[] = [''];
    const result: ProviderSourceFile[] = [];
    while (pending.length > 0) {
      const directory = pending.shift() as string;
      const response = await this.transport.get(showUrl(aosp, revision, directory, 'JSON'), {});
      checkSuccessful(response, 'AOSP tree');
      const parsed = JSON.parse(removeJsonGuard(decodeUtf8(response.body))) as { entries?: unknown[] };
      for (const element of parsed.entries ?? []) {
        if (typeof element !== 'object' || element === null) continue;
        const entry = element as Record<string, unknown>;
        const name = String(entry['name'] ?? '');
        const path = [directory, name].filter((part) => part.length > 0).join('/');
        if (entry['type'] === 'tree') {
          pending.push(path);
        } else if (entry['type'] === 'blob' && isSourcePath(path)) {
          result.push({ relativePath: path, sizeBytes: 0, contentHash: typeof entry['id'] === 'string' ? (entry['id'] as string) : null });
        }
      }
    }
    return result.sort(byRelativePath);
  }

  async readFile(config: SourceProviderConfig, revision: string, relativePath: string): Promise<Uint8Array> {
    requireSafeRelativePath(relativePath);
    const aosp = requireAosp(config);
    const response = await this.transport.get(showUrl(aosp, revision, relativePath, 'TEXT'), {});
    checkSuccessful(response, 'AOSP content');
    return base64Decode(removeMimeLineBreaks(decodeUtf8(response.body)));
  }
}

/** The TEXT format is base64 with MIME line breaks, so both are removed. */
function removeMimeLineBreaks(text: string): string {
  return text.replace(/\s+/g, '');
}

/** atob keeps this module free of Node builtins; it exists in both runtimes. */
function base64Decode(text: string): Uint8Array {
  const binary = atob(text);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function showUrl(
  config: Extract<SourceProviderConfig, { kind: 'AOSP' }>,
  revision: string,
  path: string,
  format: string,
): string {
  const suffix = path.length > 0 ? '/' + encodePath(path) : '';
  return 'https://android.googlesource.com/' + config.project + '/+/' + encode(revision) + suffix + '?format=' + format;
}

function githubPath(config: Extract<SourceProviderConfig, { kind: 'GITHUB' }>, path: string): string {
  return '/repos/' + config.owner + '/' + config.repository + path;
}

function byRelativePath(left: ProviderSourceFile, right: ProviderSourceFile): number {
  return left.relativePath === right.relativePath ? 0 : left.relativePath < right.relativePath ? -1 : 1;
}

export function requireGitHub(config: SourceProviderConfig): Extract<SourceProviderConfig, { kind: 'GITHUB' }> {
  if (config.kind !== 'GITHUB') throw new Error('GitHubSourceProvider requires GitHub config');
  return config;
}

export function requireAosp(config: SourceProviderConfig): Extract<SourceProviderConfig, { kind: 'AOSP' }> {
  if (config.kind !== 'AOSP') throw new Error('AospSourceProvider requires AOSP config');
  return config;
}
