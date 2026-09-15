import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, readdir, rename, stat, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { inflateRawSync } from 'node:zlib';

const MAX_ARTIFACT_BYTES = 128 * 1024 * 1024;
const MAX_INSPECTOR_BYTES = 64 * 1024 * 1024;
const GOOGLE_MAVEN = 'https://dl.google.com/dl/android/maven2/';

export interface ComposeInspectorArtifactIdentity {
  readonly group: 'androidx.compose.ui';
  readonly artifact: 'ui' | 'ui-android';
  readonly version: string;
  readonly sha256: string;
  readonly source: string;
  readonly certified: false;
}

export interface ResolvedComposeInspector {
  readonly jarPath: string;
  readonly identity: ComposeInspectorArtifactIdentity;
}

export interface ComposeInspectorArtifactResolverOptions {
  readonly cacheDirectory: string;
  readonly projectArtifacts?: readonly string[];
  readonly gradleUserHome?: string;
  readonly mavenLocal?: string;
  readonly repositories?: readonly string[];
  readonly download?: (url: string) => Promise<Buffer>;
}

export class ComposeInspectorArtifactError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ComposeInspectorArtifactError';
  }
}

/** Resolves only an exact Compose-version inspector, then caches its extracted dex JAR by digest. */
export class ComposeInspectorArtifactResolver {
  private readonly projectArtifacts: readonly string[];
  private readonly gradleUserHome: string;
  private readonly mavenLocal: string;
  private readonly repositories: readonly string[];
  private readonly download: (url: string) => Promise<Buffer>;

  constructor(private readonly options: ComposeInspectorArtifactResolverOptions) {
    this.projectArtifacts = options.projectArtifacts ?? [];
    this.gradleUserHome = options.gradleUserHome ?? join(homedir(), '.gradle');
    this.mavenLocal = options.mavenLocal ?? join(homedir(), '.m2', 'repository');
    this.repositories = options.repositories ?? [GOOGLE_MAVEN];
    this.download = options.download ?? downloadHttps;
  }

  async resolve(version: string, explicitLocalArtifact?: string): Promise<ResolvedComposeInspector> {
    const coordinate = composeCoordinate(version);
    const candidates: Array<{ readonly path: string; readonly source: string }> = [];
    if (explicitLocalArtifact !== undefined) candidates.push({ path: explicitLocalArtifact, source: 'explicit-local' });
    const cached = await this.verifiedCache(version, coordinate);
    if (cached !== undefined) return cached;
    candidates.push(...this.projectArtifacts
      .filter((path) => basename(path) === `${coordinate.artifact}-${version}.aar`)
      .map((path) => ({ path, source: 'project-cache' })));
    candidates.push(...await this.gradleArtifacts(coordinate, version));
    candidates.push({ path: mavenArtifact(this.mavenLocal, coordinate, version), source: 'maven-local' });

    for (const candidate of candidates) {
      const resolved = await this.resolveLocal(candidate.path, coordinate, version, candidate.source);
      if (resolved !== undefined) return await this.cache(resolved);
    }

    for (const repository of this.repositories) {
      const url = new URL(mavenRelativePath(coordinate, version), requireHttpsRepository(repository)).toString();
      try {
        return await this.cache(await this.resolveBytes(await this.download(url), coordinate, version, url, true));
      } catch {
        // Try the next explicitly configured repository; callers get one stable error below.
      }
    }
    throw new ComposeInspectorArtifactError(`No exact Compose inspector found for ${coordinate.group}:${coordinate.artifact}:${version}`);
  }

  private async verifiedCache(version: string, coordinate: Coordinate): Promise<ResolvedComposeInspector | undefined> {
    const versionDirectory = join(this.options.cacheDirectory, version);
    let entries;
    try { entries = await readdir(versionDirectory, { withFileTypes: true }); } catch { return undefined; }
    for (const entry of entries) {
      if (!entry.isDirectory() || !/^[a-f0-9]{64}$/.test(entry.name)) continue;
      const jarPath = join(versionDirectory, entry.name, 'inspector.jar');
      try {
        const metadata = await stat(jarPath);
        if (!metadata.isFile() || metadata.size < 1 || metadata.size > MAX_INSPECTOR_BYTES) continue;
        if (await sha256File(jarPath) !== entry.name) continue;
        return { jarPath, identity: { ...coordinate, version, sha256: entry.name, source: 'aps-cache', certified: false } };
      } catch { /* Ignore an incomplete or tampered cache entry. */ }
    }
    return undefined;
  }

  private async resolveLocal(path: string, coordinate: Coordinate, version: string, source: string): Promise<ResolvedBytes | undefined> {
    try {
      const metadata = await stat(path);
      if (!metadata.isFile()) return undefined;
      if (metadata.size < 1 || metadata.size > MAX_ARTIFACT_BYTES) throw new ComposeInspectorArtifactError('Compose inspector artifact is too large');
      return await this.resolveBytes(await readFile(path), coordinate, version, source, path.toLowerCase().endsWith('.aar'));
    } catch (error) {
      if (error instanceof ComposeInspectorArtifactError) throw error;
      return undefined;
    }
  }

  private async resolveBytes(bytes: Buffer, coordinate: Coordinate, version: string, source: string, isAar: boolean): Promise<ResolvedBytes> {
    if (bytes.length < 1 || bytes.length > MAX_ARTIFACT_BYTES) throw new ComposeInspectorArtifactError('Compose inspector artifact is too large');
    const jar = isAar ? extractInspectorJar(bytes) : bytes;
    if (jar.length < 1 || jar.length > MAX_INSPECTOR_BYTES) throw new ComposeInspectorArtifactError('Compose inspector JAR is invalid');
    return { coordinate, version, source, jar };
  }

  private async cache(resolved: ResolvedBytes): Promise<ResolvedComposeInspector> {
    const sha256 = hash(resolved.jar);
    const directory = join(this.options.cacheDirectory, resolved.version, sha256);
    const jarPath = join(directory, 'inspector.jar');
    await mkdir(directory, { recursive: true });
    try {
      const metadata = await stat(jarPath);
      if (!metadata.isFile() || metadata.size !== resolved.jar.length || await sha256File(jarPath) !== sha256) throw new Error('replace');
    } catch {
      const temporary = join(directory, `inspector-${randomUUID()}.tmp`);
      try {
        await writeFile(temporary, resolved.jar, { flag: 'wx' });
        await rename(temporary, jarPath);
      } finally {
        // rename has moved it in the normal case; a failed rename leaves no trusted output.
        await stat(temporary).then(() => import('node:fs/promises').then(({ unlink }) => unlink(temporary))).catch(() => undefined);
      }
    }
    return { jarPath, identity: { ...resolved.coordinate, version: resolved.version, sha256, source: resolved.source, certified: false } };
  }

  private async gradleArtifacts(coordinate: Coordinate, version: string): Promise<Array<{ readonly path: string; readonly source: string }>> {
    const directory = join(this.gradleUserHome, 'caches', 'modules-2', 'files-2.1', coordinate.group, coordinate.artifact, version);
    return (await filesUnder(directory)).filter((path) => path.toLowerCase().endsWith('.aar')).map((path) => ({ path, source: 'gradle-cache' }));
  }
}

type Coordinate = { readonly group: 'androidx.compose.ui'; readonly artifact: 'ui' | 'ui-android' };
type ResolvedBytes = { readonly coordinate: Coordinate; readonly version: string; readonly source: string; readonly jar: Buffer };

export function composeCoordinate(version: string): Coordinate {
  const match = /^(\d+)\.(\d+)(?:\..+)?$/.exec(version);
  if (match === null) throw new ComposeInspectorArtifactError('Invalid Compose version: ' + version);
  const major = Number(match[1]);
  const minor = Number(match[2]);
  return { group: 'androidx.compose.ui', artifact: major > 1 || major === 1 && minor >= 5 ? 'ui-android' : 'ui' };
}

function mavenArtifact(root: string, coordinate: Coordinate, version: string): string {
  return join(root, ...mavenRelativePath(coordinate, version).split('/'));
}
function mavenRelativePath(coordinate: Coordinate, version: string): string {
  return `${coordinate.group.replaceAll('.', '/')}/${coordinate.artifact}/${version}/${coordinate.artifact}-${version}.aar`;
}
function requireHttpsRepository(repository: string): string {
  const url = new URL(repository);
  if (url.protocol !== 'https:') throw new ComposeInspectorArtifactError('Compose inspector downloads require HTTPS');
  return url.toString().endsWith('/') ? url.toString() : url.toString() + '/';
}
async function downloadHttps(url: string): Promise<Buffer> {
  const response = await fetch(url, { signal: AbortSignal.timeout(30_000) });
  if (!response.ok) throw new ComposeInspectorArtifactError('Compose inspector download failed: HTTP ' + String(response.status));
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > MAX_ARTIFACT_BYTES) throw new ComposeInspectorArtifactError('Compose inspector artifact is too large');
  return bytes;
}
async function sha256File(path: string): Promise<string> { return hash(await readFile(path)); }
function hash(bytes: Uint8Array): string { return createHash('sha256').update(bytes).digest('hex'); }

async function filesUnder(directory: string, depth = 0): Promise<string[]> {
  if (depth > 8) return [];
  let entries;
  try { entries = await readdir(directory, { withFileTypes: true }); } catch { return []; }
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = resolve(directory, entry.name);
    if (entry.isFile()) return [path];
    return entry.isDirectory() ? await filesUnder(path, depth + 1) : [];
  }));
  return nested.flat();
}

/** Extracts exactly inspector.jar from a bounded, non-encrypted ZIP/AAR. */
function extractInspectorJar(archive: Buffer): Buffer {
  const eocd = findEndOfCentralDirectory(archive);
  const count = archive.readUInt16LE(eocd + 10);
  const centralOffset = archive.readUInt32LE(eocd + 16);
  if (count > 100_000 || centralOffset >= archive.length) throw new ComposeInspectorArtifactError('AAR ZIP index is invalid');
  let offset = centralOffset;
  for (let index = 0; index < count; index += 1) {
    if (offset + 46 > archive.length || archive.readUInt32LE(offset) !== 0x02014b50) throw new ComposeInspectorArtifactError('AAR ZIP index is invalid');
    const flags = archive.readUInt16LE(offset + 8);
    const method = archive.readUInt16LE(offset + 10);
    const compressedSize = archive.readUInt32LE(offset + 20);
    const uncompressedSize = archive.readUInt32LE(offset + 24);
    const nameLength = archive.readUInt16LE(offset + 28);
    const extraLength = archive.readUInt16LE(offset + 30);
    const commentLength = archive.readUInt16LE(offset + 32);
    const localOffset = archive.readUInt32LE(offset + 42);
    const next = offset + 46 + nameLength + extraLength + commentLength;
    if (next > archive.length) throw new ComposeInspectorArtifactError('AAR ZIP index is invalid');
    const name = archive.toString('utf8', offset + 46, offset + 46 + nameLength);
    if (name === 'inspector.jar') {
      if ((flags & 1) !== 0 || uncompressedSize < 1 || uncompressedSize > MAX_INSPECTOR_BYTES) throw new ComposeInspectorArtifactError('AAR inspector.jar is invalid');
      return extractZipEntry(archive, localOffset, method, compressedSize, uncompressedSize);
    }
    offset = next;
  }
  throw new ComposeInspectorArtifactError('AAR does not contain inspector.jar');
}
function findEndOfCentralDirectory(archive: Buffer): number {
  for (let offset = archive.length - 22; offset >= Math.max(0, archive.length - 65_557); offset -= 1) {
    if (archive.readUInt32LE(offset) === 0x06054b50) return offset;
  }
  throw new ComposeInspectorArtifactError('AAR ZIP data is invalid');
}
function extractZipEntry(archive: Buffer, localOffset: number, method: number, compressedSize: number, uncompressedSize: number): Buffer {
  if (localOffset + 30 > archive.length || archive.readUInt32LE(localOffset) !== 0x04034b50) throw new ComposeInspectorArtifactError('AAR ZIP entry is invalid');
  const dataOffset = localOffset + 30 + archive.readUInt16LE(localOffset + 26) + archive.readUInt16LE(localOffset + 28);
  if (dataOffset + compressedSize > archive.length) throw new ComposeInspectorArtifactError('AAR ZIP entry is invalid');
  try {
    const data = archive.subarray(dataOffset, dataOffset + compressedSize);
    const result = method === 0 ? Buffer.from(data) : method === 8 ? inflateRawSync(data, { maxOutputLength: uncompressedSize + 1 }) : undefined;
    if (result === undefined || result.length !== uncompressedSize || result.length > MAX_INSPECTOR_BYTES) throw new ComposeInspectorArtifactError('AAR inspector.jar is invalid');
    return result;
  } catch (error) {
    if (error instanceof ComposeInspectorArtifactError) throw error;
    throw new ComposeInspectorArtifactError('AAR inspector.jar is invalid');
  }
}
