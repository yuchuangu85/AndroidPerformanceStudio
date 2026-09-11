/**
 * Node-only half of the source workspace: hashing, walking a local tree, and the
 * content addressed cache that keeps verified content immutable.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { MAX_SOURCE_FILE_BYTES, SOURCE_EXTENSIONS, isIndexableSourcePath } from './language.js';
import type { SourceProviderConfig, SourceProviderKind } from './model.js';
import type { ProviderSourceFile, SourceProvider } from './providers.js';

export function sha256Bytes(content: Uint8Array): string {
  return createHash('sha256').update(content).digest('hex');
}

export function sha256Text(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

export interface WalkedSourceFile {
  readonly relativePath: string;
  readonly sizeBytes: number;
}

/** Walks an absolute root, skipping ignored directories and oversized files. */
export function walkSourceFiles(root: string): WalkedSourceFile[] {
  const absoluteRoot = resolve(root);
  const results: WalkedSourceFile[] = [];
  walk(absoluteRoot, absoluteRoot, results);
  return results.sort((left, right) =>
    left.relativePath === right.relativePath ? 0 : left.relativePath < right.relativePath ? -1 : 1,
  );
}

function walk(root: string, directory: string, results: WalkedSourceFile[]): void {
  let entries;
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const absolute = join(directory, entry.name);
    const relativePath = relative(root, absolute).split(sep).join('/');
    if (entry.isDirectory()) {
      if (!isIgnoredSegment(entry.name)) walk(root, absolute, results);
      continue;
    }
    if (!entry.isFile()) continue;
    if (!isIndexableSourcePath(relativePath)) continue;
    let size: number;
    try {
      size = statSync(absolute).size;
    } catch {
      continue;
    }
    if (size > MAX_SOURCE_FILE_BYTES) continue;
    results.push({ relativePath, sizeBytes: size });
  }
}

function isIgnoredSegment(name: string): boolean {
  return (
    name === '.git' ||
    name === '.gradle' ||
    name === '.idea' ||
    name === 'build' ||
    name === 'out' ||
    name === 'node_modules'
  );
}

/** Reads a file inside the root, refusing anything that escapes it. */
export function readSourceFile(root: string, relativePath: string): Uint8Array {
  const absoluteRoot = resolve(root);
  const absolute = resolve(absoluteRoot, relativePath);
  if (absolute !== absoluteRoot && !absolute.startsWith(absoluteRoot + sep)) {
    throw new Error('Source path escapes workspace: ' + relativePath);
  }
  const stats = statSync(absolute);
  if (!stats.isFile()) throw new Error('Source file is unavailable: ' + relativePath);
  if (stats.size > MAX_SOURCE_FILE_BYTES) {
    throw new Error('Source file exceeds the size limit: ' + relativePath);
  }
  return readFileSync(absolute);
}

/**
 * Content addressed cache: the hash is the identity, so a verified snapshot can
 * always be read back even after the working tree changes.
 */
export class ContentAddressedSourceCache {
  private readonly root: string;

  constructor(root: string) {
    this.root = root;
  }

  pathFor(hash: string): string {
    return join(this.root, hash.slice(0, 2), hash.slice(2));
  }

  put(content: Uint8Array): string {
    const hash = sha256Bytes(content);
    const target = this.pathFor(hash);
    if (!this.contains(hash)) {
      mkdirSync(dirname(target), { recursive: true });
      const temporary = target + '.tmp';
      writeFileSync(temporary, content);
      renameSync(temporary, target);
    }
    return hash;
  }

  read(hash: string): Uint8Array {
    if (!/^[0-9a-f]{64}$/.test(hash)) throw new Error('Invalid cache hash');
    const content = readFileSync(this.pathFor(hash));
    if (sha256Bytes(content) !== hash) throw new Error('Cached source hash mismatch: ' + hash);
    return new Uint8Array(content);
  }

  contains(hash: string): boolean {
    if (!/^[0-9a-f]{64}$/.test(hash)) return false;
    try {
      return statSync(this.pathFor(hash)).isFile();
    } catch {
      return false;
    }
  }
}

export { SOURCE_EXTENSIONS };
export * from './sqlite-repository.js';

/** Port of LocalSourceProvider.kt: a working tree, optionally a git checkout. */
export class LocalSourceProvider implements SourceProvider {
  readonly kind: SourceProviderKind = 'LOCAL';

  async resolveRevision(config: SourceProviderConfig): Promise<string> {
    const local = requireLocal(config);
    if (!statSync(local.root, { throwIfNoEntry: false })?.isDirectory()) {
      throw new Error('Local source root is not a directory: ' + local.root);
    }
    const commit = git(local.root, ['rev-parse', 'HEAD'])?.trim() || 'unversioned';
    const status = git(local.root, ['status', '--porcelain']);
    const hasDirtyFiles = status === undefined ? commit === 'unversioned' : status.trim().length > 0;
    if (!hasDirtyFiles) return commit;
    return commit + '-dirty-' + contentDigest(local.root);
  }

  async listFiles(config: SourceProviderConfig): Promise<ProviderSourceFile[]> {
    const root = resolve(requireLocal(config).root);
    return walkSourceFiles(root).map((file) => ({
      relativePath: file.relativePath,
      sizeBytes: file.sizeBytes,
      contentHash: null,
    }));
  }

  async readFile(config: SourceProviderConfig, revision: string, relativePath: string): Promise<Uint8Array> {
    // A local tree is read as it is on disk; the revision is only a label.
    void revision;
    return readSourceFile(requireLocal(config).root, relativePath);
  }
}

export function requireLocal(config: SourceProviderConfig): { readonly kind: 'LOCAL'; readonly root: string } {
  if (config.kind !== 'LOCAL') throw new Error('LocalSourceProvider requires Local config');
  return config;
}

/** A manifest of relative path and content hash, hashed as a whole. */
function contentDigest(root: string): string {
  const manifest = walkSourceFiles(root)
    .map((file) => file.relativePath + ':' + sha256Bytes(readSourceFile(root, file.relativePath)))
    .join('\n');
  return sha256Text(manifest);
}

function git(root: string, args: readonly string[]): string | undefined {
  try {
    return execFileSync('git', ['-C', root, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch {
    return undefined;
  }
}
