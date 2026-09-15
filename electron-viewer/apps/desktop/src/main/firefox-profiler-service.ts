/**
 * Firefox Profiler hand-off for retained CPU sessions.
 *
 * The official site cannot consume local bytes directly. Both Firefox engines
 * therefore receive a valid gzipped Gecko profile over loopback: the bundled
 * engine also receives its static application assets, while the official one
 * gets a short-lived one-profile transfer endpoint.
 */
import { existsSync } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { resolve, sep } from 'node:path';
import { gzipSync } from 'node:zlib';
import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  exportGeckoProfile,
  normalizeSimpleperfReportWithLookups,
  type CpuProfileSessionRecord,
} from '@aps/simpleperf-profiler';

const LOOPBACK_HOST = '127.0.0.1';
const PROFILE_FILE = 'perf_data.json.gz';
const OFFICIAL_FIREFOX_PROFILER_ORIGIN = 'https://profiler.firefox.com';
const DEFAULT_TRANSFER_LIFETIME_MILLIS = 5 * 60 * 1000;

export interface CpuProfileReportStore {
  readReport(record: CpuProfileSessionRecord): Promise<Uint8Array | undefined>;
}

export interface FirefoxProfilerServiceOptions {
  readonly openExternal: (url: string) => Promise<void>;
  readonly localSiteDirectory?: string;
  readonly resourcesPath?: string;
  readonly repositoryRoot?: string;
  readonly transferLifetimeMillis?: number;
}

/** Returns an existing complete Firefox Profiler site without creating files. */
export function firefoxProfilerSiteDirectory(options: {
  readonly configuredPath?: string;
  readonly resourcesPath?: string;
  readonly repositoryRoot?: string;
}): string | undefined {
  const candidates = [
    options.configuredPath,
    options.resourcesPath === undefined ? undefined : resolve(options.resourcesPath, 'firefox-profiler'),
    options.repositoryRoot === undefined ? undefined : resolve(options.repositoryRoot, 'third_party', 'firefox-profiler', 'dist'),
  ];
  return candidates.find((candidate): candidate is string =>
    candidate !== undefined && candidate.length > 0 && existsSync(resolve(candidate, 'index.html')),
  );
}

/**
 * Uses retained raw Gecko bytes whenever possible. All other sessions are
 * normalized from their protobuf source and serialized through the v24 writer.
 */
export async function geckoProfileForCpuSession(
  store: CpuProfileReportStore,
  record: CpuProfileSessionRecord,
): Promise<StudioResult<Uint8Array>> {
  const report = await store.readReport(record);
  if (report === undefined) {
    return fail('IO', 'CPU_REPORT_MISSING', 'The stored report for this session is gone');
  }
  if (record.sourceFormat === 'GECKO_PROFILE_JSON_GZIP') return ok(report);
  const normalized = normalizeSimpleperfReportWithLookups(report);
  if (!normalized.ok) return normalized;
  if (normalized.value.samples.length === 0) {
    return fail('DATA_VALIDATION', 'CPU_EXPORT_NO_SAMPLES', 'The stored profile contains no samples');
  }
  return ok(new Uint8Array(gzipSync(exportGeckoProfile(normalized.value.samples).json)));
}

/**
 * Owns at most one local Firefox server and any number of transient official
 * transfers. The profile stays in memory: no rendered data is written outside
 * the already-retained CPU session.
 */
export class FirefoxProfilerService {
  private readonly openExternal: (url: string) => Promise<void>;
  private readonly localSiteDirectory: string | undefined;
  private readonly transferLifetimeMillis: number;
  private localServer: Server | undefined;
  private officialServers = new Set<Server>();

  constructor(options: FirefoxProfilerServiceOptions) {
    this.openExternal = options.openExternal;
    this.localSiteDirectory =
      options.localSiteDirectory ??
      firefoxProfilerSiteDirectory({
        configuredPath: process.env['APS_FIREFOX_PROFILER_DIST'],
        resourcesPath: options.resourcesPath,
        repositoryRoot: options.repositoryRoot,
      });
    this.transferLifetimeMillis = options.transferLifetimeMillis ?? DEFAULT_TRANSFER_LIFETIME_MILLIS;
  }

  async openLocal(profile: Uint8Array): Promise<void> {
    const site = this.localSiteDirectory;
    if (site === undefined) {
      throw new Error('Bundled Firefox Profiler assets are unavailable');
    }
    await this.closeLocal();
    const server = createLocalServer(site, profile);
    const origin = await listen(server);
    this.localServer = server;
    try {
      await this.openExternal(firefoxProfilerUrl(origin, new URL('/' + PROFILE_FILE, origin).toString()));
    } catch (error) {
      await this.closeLocal();
      throw error;
    }
  }

  async openOfficial(profile: Uint8Array): Promise<void> {
    const server = createOfficialTransferServer(profile, this.transferLifetimeMillis, () => {
      this.officialServers.delete(server);
    });
    const origin = await listen(server);
    this.officialServers.add(server);
    try {
      await this.openExternal(officialFirefoxProfilerUrl(new URL('/' + PROFILE_FILE, origin).toString()));
    } catch (error) {
      await closeServer(server);
      this.officialServers.delete(server);
      throw error;
    }
  }

  async close(): Promise<void> {
    await this.closeLocal();
    await Promise.all([...this.officialServers].map(closeServer));
    this.officialServers.clear();
  }

  private async closeLocal(): Promise<void> {
    if (this.localServer === undefined) return;
    const server = this.localServer;
    this.localServer = undefined;
    await closeServer(server);
  }
}

export function officialFirefoxProfilerUrl(profileUrl: string): string {
  return OFFICIAL_FIREFOX_PROFILER_ORIGIN + '/from-url/' + encodeURIComponent(profileUrl) + '/flame-graph/';
}

export function firefoxProfilerUrl(origin: string, profileUrl: string): string {
  return origin.replace(/\/$/, '') + '/from-url/' + encodeURIComponent(profileUrl) + '/flame-graph/';
}

function createOfficialTransferServer(profile: Uint8Array, lifetimeMillis: number, onClose: () => void): Server {
  const server = createServer((request, response) => {
    const path = requestPath(request);
    if (request.method === 'OPTIONS') {
      writeProfileHeaders(response, 204, 0);
      response.end();
      return;
    }
    if (request.method !== 'GET' || path !== '/' + PROFILE_FILE) {
      writeProfileHeaders(response, 404, 0);
      response.end();
      return;
    }
    writeProfileHeaders(response, 200, profile.byteLength);
    response.end(profile, close);
  });
  const timer = setTimeout(close, lifetimeMillis);
  timer.unref();
  function close(): void {
    clearTimeout(timer);
    server.close(() => onClose());
  }
  return server;
}

function createLocalServer(site: string, profile: Uint8Array): Server {
  const root = resolve(site);
  return createServer(async (request, response) => {
    if (request.method === 'OPTIONS') {
      writeCommonHeaders(response, 204, 0, 'text/plain; charset=utf-8', true);
      response.end();
      return;
    }
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      writeCommonHeaders(response, 405, 0, 'text/plain; charset=utf-8', true);
      response.end();
      return;
    }
    const headOnly = request.method === 'HEAD';
    const path = requestPath(request);
    if (path === '/' + PROFILE_FILE) {
      writeProfileHeaders(response, 200, profile.byteLength);
      response.end(headOnly ? undefined : profile);
      return;
    }
    const file = await staticFileFor(root, path, request.headers.accept);
    if (file === undefined) {
      const body = Buffer.from('Not found');
      writeCommonHeaders(response, 404, body.byteLength, 'text/plain; charset=utf-8', true);
      response.end(headOnly ? undefined : body);
      return;
    }
    try {
      const data = await readFile(file);
      writeCommonHeaders(response, 200, data.byteLength, contentTypeOf(file), false);
      response.end(headOnly ? undefined : data);
    } catch {
      const body = Buffer.from('Not found');
      writeCommonHeaders(response, 404, body.byteLength, 'text/plain; charset=utf-8', true);
      response.end(headOnly ? undefined : body);
    }
  });
}

async function staticFileFor(root: string, path: string | undefined, accept: string | undefined): Promise<string | undefined> {
  if (path === undefined) return undefined;
  if (path === '/' || path.startsWith('/from-url/')) return resolve(root, 'index.html');
  let decoded: string;
  try {
    decoded = decodeURIComponent(path.slice(1));
  } catch {
    return undefined;
  }
  const candidate = resolve(root, decoded);
  if (!isWithin(root, candidate)) return undefined;
  try {
    return (await stat(candidate)).isFile() ? candidate : accept?.includes('text/html') ? resolve(root, 'index.html') : undefined;
  } catch {
    return accept?.includes('text/html') ? resolve(root, 'index.html') : undefined;
  }
}

function isWithin(root: string, candidate: string): boolean {
  return candidate === root || candidate.startsWith(root + sep);
}

function contentTypeOf(path: string): string {
  const extension = path.slice(path.lastIndexOf('.') + 1).toLowerCase();
  switch (extension) {
    case 'html': return 'text/html; charset=utf-8';
    case 'js':
    case 'mjs': return 'text/javascript; charset=utf-8';
    case 'css': return 'text/css; charset=utf-8';
    case 'json':
    case 'map': return 'application/json; charset=utf-8';
    case 'wasm': return 'application/wasm';
    case 'png': return 'image/png';
    case 'jpg':
    case 'jpeg': return 'image/jpeg';
    case 'svg': return 'image/svg+xml';
    case 'ico': return 'image/x-icon';
    case 'woff': return 'font/woff';
    case 'woff2': return 'font/woff2';
    case 'ftl':
    case 'txt': return 'text/plain; charset=utf-8';
    default: return 'application/octet-stream';
  }
}

function requestPath(request: IncomingMessage): string | undefined {
  try {
    return new URL(request.url ?? '/', 'http://loopback').pathname;
  } catch {
    return undefined;
  }
}

function writeProfileHeaders(response: ServerResponse, status: number, length: number): void {
  writeCommonHeaders(response, status, length, 'application/gzip', true, true);
}

function writeCommonHeaders(
  response: ServerResponse,
  status: number,
  length: number,
  contentType: string,
  noStore: boolean,
  allowPrivateNetwork = false,
): void {
  response.writeHead(status, {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': allowPrivateNetwork ? 'GET, OPTIONS' : 'GET, HEAD, OPTIONS',
    'Access-Control-Allow-Headers': '*',
    ...(allowPrivateNetwork ? { 'Access-Control-Allow-Private-Network': 'true' } : {}),
    'Content-Type': contentType,
    'Content-Length': String(length),
    'Cache-Control': noStore ? 'no-store' : 'no-cache',
    Connection: 'close',
  });
}

async function listen(server: Server): Promise<string> {
  await new Promise<void>((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(0, LOOPBACK_HOST, () => {
      server.off('error', reject);
      resolvePromise();
    });
  });
  const address = server.address();
  if (address === null || typeof address === 'string') {
    await closeServer(server);
    throw new Error('Firefox Profiler server did not bind a TCP port');
  }
  return 'http://' + LOOPBACK_HOST + ':' + String(address.port);
}

function closeServer(server: Server): Promise<void> {
  return new Promise((resolvePromise) => server.close(() => resolvePromise()));
}
