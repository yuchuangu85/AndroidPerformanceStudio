/**
 * Node-only half of the source workspace: hashing, walking a local tree, and the
 * content addressed cache that keeps verified content immutable.
 */
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { MAX_SOURCE_FILE_BYTES, SOURCE_EXTENSIONS, isIndexableSourcePath } from './language.js';

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
