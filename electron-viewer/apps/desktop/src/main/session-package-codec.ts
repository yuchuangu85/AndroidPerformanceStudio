import { constants as bufferConstants } from 'node:buffer';
import { createHash, randomUUID } from 'node:crypto';
import { lstat, mkdir, readdir, readFile, rename, rmdir, stat, unlink, writeFile } from 'node:fs/promises';
import { basename, dirname, join, relative, resolve, sep } from 'node:path';
import { deflateRawSync, inflateRawSync } from 'node:zlib';

export const SESSION_PACKAGE_MANIFEST = 'apsession-manifest.txt';
export const SESSION_PACKAGE_SCHEMA_VERSION = 1;
export const DEFAULT_SESSION_PACKAGE_MAX_ENTRY_BYTES = 4 * 1024 * 1024 * 1024;
export const DEFAULT_SESSION_PACKAGE_MAX_TOTAL_BYTES = 8 * 1024 * 1024 * 1024;
export const DEFAULT_SESSION_PACKAGE_MAX_ENTRIES = 100_000;

const ZIP_LOCAL_FILE_HEADER = 0x04034b50;
const ZIP_CENTRAL_DIRECTORY_HEADER = 0x02014b50;
const ZIP_END_OF_CENTRAL_DIRECTORY = 0x06054b50;
const ZIP64_END_OF_CENTRAL_DIRECTORY = 0x06064b50;
const ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR = 0x07064b50;
const ZIP64_EXTRA_FIELD = 0x0001;
const ZIP_UTF8_FLAG = 0x0800;
const ZIP_DATA_DESCRIPTOR_FLAG = 0x0008;
const ZIP_EPOCH_DOS_DATE = 0x0021;
const ZIP_EPOCH_TIMESTAMP_EXTRA = Buffer.from([0x55, 0x54, 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00]);
const UINT16_MAX = 0xffff;
const UINT32_MAX = 0xffffffff;
const MAX_ARCHIVE_OVERHEAD_BYTES = 256 * 1024 * 1024;

export interface SessionPackageLimits {
  readonly maxEntryBytes?: number;
  readonly maxTotalBytes?: number;
  readonly maxEntries?: number;
}

export interface ResolvedSessionPackageLimits {
  readonly maxEntryBytes: number;
  readonly maxTotalBytes: number;
  readonly maxEntries: number;
  /** The largest archive this in-memory Node codec can read safely. */
  readonly maxArchiveBytes: number;
}

export interface SessionPackageExportResult {
  readonly archive: string;
  readonly fileCount: number;
}

export interface SessionPackageImportResult {
  readonly sessionDirectory: string;
  readonly verifiedFiles: number;
}

export interface SessionPackageDocument {
  /** Manifest-listed regular files, including unknown files. */
  readonly files: ReadonlyMap<string, Buffer>;
  /** Empty directories carried by a Kotlin-produced ZIP. */
  readonly directories: readonly string[];
}

export class SessionPackageFormatError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = 'SessionPackageFormatError';
  }
}

interface ZipEntry {
  readonly name: string;
  readonly bytes: Buffer;
  readonly directory: boolean;
}

interface Zip64Values {
  readonly uncompressedSize?: number;
  readonly compressedSize?: number;
  readonly localOffset?: number;
}

export function sessionPackageLimitsFor(limits: SessionPackageLimits = {}): ResolvedSessionPackageLimits {
  const maxEntryBytes = positiveSafeInteger(limits.maxEntryBytes ?? DEFAULT_SESSION_PACKAGE_MAX_ENTRY_BYTES, 'maxEntryBytes');
  const maxTotalBytes = positiveSafeInteger(limits.maxTotalBytes ?? DEFAULT_SESSION_PACKAGE_MAX_TOTAL_BYTES, 'maxTotalBytes');
  const maxEntries = positiveSafeInteger(limits.maxEntries ?? DEFAULT_SESSION_PACKAGE_MAX_ENTRIES, 'maxEntries');
  const maxArchiveBytes = Math.min(bufferConstants.MAX_LENGTH, maxTotalBytes + MAX_ARCHIVE_OVERHEAD_BYTES);
  return { maxEntryBytes, maxTotalBytes, maxEntries, maxArchiveBytes };
}

/**
 * Kotlin-compatible generic `.apsession.zip` transport. It deliberately owns
 * only archive integrity and file preservation; CPU and Method semantics stay
 * in their respective importers.
 */
export class SessionPackageCodec {
  private readonly limits: ResolvedSessionPackageLimits;

  constructor(
    limits: SessionPackageLimits = {},
    private readonly replace: (temporary: string, target: string) => Promise<void> = rename,
  ) {
    this.limits = sessionPackageLimitsFor(limits);
  }

  async export(sessionDirectory: string, destinationArchive: string): Promise<SessionPackageExportResult> {
    const source = resolve(sessionDirectory);
    const target = resolve(destinationArchive);
    const sourceStats = await lstat(source).catch((error: unknown) => {
      throw packageError('Session directory does not exist: ' + source, error);
    });
    if (sourceStats.isSymbolicLink() || !sourceStats.isDirectory()) {
      throw new SessionPackageFormatError('Session directory does not exist: ' + source);
    }

    const files = await sessionFiles(source, target, this.limits);
    const entries = await Promise.all(files.map(async (file) => ({
      name: file.relativePath,
      bytes: await readBoundedFile(file.absolutePath, this.limits.maxEntryBytes),
      directory: false,
    })));
    const total = entries.reduce((sum, entry) => checkedSum(sum, entry.bytes.length, this.limits.maxTotalBytes, 'Session package exceeds total size limit'), 0);
    void total;
    entries.sort((left, right) => left.name.localeCompare(right.name));

    const manifest = serializeManifest(entries);
    if (manifest.length > this.limits.maxEntryBytes) throw new SessionPackageFormatError('Session package manifest exceeds entry size limit');
    checkedSum(entries.reduce((sum, entry) => sum + entry.bytes.length, 0), manifest.length, this.limits.maxTotalBytes, 'Session package exceeds total size limit');
    const archive = writeZip([...entries, { name: SESSION_PACKAGE_MANIFEST, bytes: manifest, directory: false }]);
    if (archive.length > this.limits.maxArchiveBytes) throw new SessionPackageFormatError('Session package archive is too large for this Electron runtime');

    await mkdir(dirname(target), { recursive: true });
    const temporary = join(dirname(target), `.${basename(target)}.${randomUUID()}.tmp`);
    try {
      await writeFile(temporary, archive, { flag: 'wx' });
      await this.replace(temporary, target);
    } finally {
      await unlink(temporary).catch(() => undefined);
    }
    return { archive: target, fileCount: entries.length };
  }

  async import(archive: string, destinationRoot: string): Promise<SessionPackageImportResult> {
    const source = resolve(archive);
    const document = await this.read(source);
    const root = resolve(destinationRoot);
    await mkdir(root, { recursive: true });
    const finalDirectory = await reserveDestination(root, sessionName(source));

    try {
      for (const directory of document.directories) await mkdir(safeOutputPath(finalDirectory, directory, true), { recursive: true });
      for (const [path, bytes] of document.files) {
        const output = safeOutputPath(finalDirectory, path);
        await mkdir(dirname(output), { recursive: true });
        await writeFile(output, bytes, { flag: 'wx' });
      }
      return { sessionDirectory: finalDirectory, verifiedFiles: document.files.size };
    } catch (error) {
      await removeTree(finalDirectory);
      throw packageError('Failed to import session package: ' + describe(error), error);
    }
  }

  /** Verifies a package without mutating a destination directory. */
  async read(archive: string): Promise<SessionPackageDocument> {
    const source = resolve(archive);
    const archiveStats = await stat(source).catch((error: unknown) => {
      throw packageError('Session package does not exist: ' + source, error);
    });
    if (!archiveStats.isFile()) throw new SessionPackageFormatError('Session package does not exist: ' + source);
    if (archiveStats.size > this.limits.maxArchiveBytes) {
      throw new SessionPackageFormatError('Session package archive is too large for this Electron runtime');
    }

    let bytes: Buffer;
    try {
      bytes = await readFile(source);
    } catch (error) {
      throw packageError('Failed to read session package: ' + describe(error), error);
    }
    if (bytes.length !== archiveStats.size || bytes.length > this.limits.maxArchiveBytes) {
      throw new SessionPackageFormatError('Session package changed while it was being read');
    }
    return decodeDocument(readZip(bytes, this.limits), this.limits);
  }
}

async function sessionFiles(
  root: string,
  destinationArchive: string,
  limits: ResolvedSessionPackageLimits,
): Promise<readonly { readonly absolutePath: string; readonly relativePath: string }[]> {
  const files: { absolutePath: string; relativePath: string }[] = [];
  let count = 0;

  async function visit(directory: string): Promise<void> {
    const entries = await readdir(directory, { withFileTypes: true });
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const path = join(directory, entry.name);
      const metadata = await lstat(path);
      if (metadata.isSymbolicLink()) throw new SessionPackageFormatError('Session directory contains a symbolic link: ' + path);
      if (metadata.isDirectory()) {
        await visit(path);
      } else if (metadata.isFile()) {
        if (resolve(path) === destinationArchive || basename(path) === SESSION_PACKAGE_MANIFEST) continue;
        if (metadata.size > limits.maxEntryBytes) throw new SessionPackageFormatError('Session package entry exceeds size limit: ' + path);
        count = checkedSum(count, 1, limits.maxEntries, 'Session package contains too many entries');
        const relativePath = portableRelativePath(root, path);
        safeArchivePath(relativePath, false);
        files.push({ absolutePath: path, relativePath });
      }
    }
  }

  await visit(root);
  return files;
}

async function readBoundedFile(path: string, maxBytes: number): Promise<Buffer> {
  const metadata = await lstat(path);
  if (metadata.isSymbolicLink() || !metadata.isFile()) throw new SessionPackageFormatError('Session file is no longer a regular file: ' + path);
  if (metadata.size > maxBytes) throw new SessionPackageFormatError('Session package entry exceeds size limit: ' + path);
  const bytes = await readFile(path);
  if (bytes.length !== metadata.size || bytes.length > maxBytes) throw new SessionPackageFormatError('Session file changed while it was being read: ' + path);
  return bytes;
}

function decodeDocument(entries: readonly ZipEntry[], limits: ResolvedSessionPackageLimits): SessionPackageDocument {
  const seenNames = new Set<string>();
  const files = new Map<string, Buffer>();
  const directories: string[] = [];
  let manifest: Map<string, string> | undefined;
  let totalBytes = 0;

  for (const entry of entries) {
    if (seenNames.has(entry.name)) throw new SessionPackageFormatError('Duplicate session package entry: ' + entry.name);
    seenNames.add(entry.name);
    if (entry.directory) {
      directories.push(entry.name);
      continue;
    }
    totalBytes = checkedSum(totalBytes, entry.bytes.length, limits.maxTotalBytes, 'Session package exceeds total size limit');
    if (entry.bytes.length > limits.maxEntryBytes) throw new SessionPackageFormatError('Session package entry exceeds size limit: ' + entry.name);
    if (entry.name === SESSION_PACKAGE_MANIFEST) {
      manifest = parseManifest(entry.bytes);
    } else {
      if (basename(entry.name) === SESSION_PACKAGE_MANIFEST) {
        throw new SessionPackageFormatError('Session package file list does not match manifest');
      }
      files.set(entry.name, entry.bytes);
    }
  }

  if (manifest === undefined) throw new SessionPackageFormatError('Session package manifest is missing');
  if (files.size !== manifest.size || [...files.keys()].some((path) => !manifest.has(path))) {
    throw new SessionPackageFormatError('Session package file list does not match manifest');
  }
  for (const [path, expectedHash] of manifest) {
    const bytes = files.get(path);
    if (bytes === undefined || sha256(bytes) !== expectedHash) {
      throw new SessionPackageFormatError('Session package checksum mismatch: ' + path);
    }
  }
  return { files, directories: directories.sort((left, right) => left.localeCompare(right)) };
}

function parseManifest(bytes: Buffer): Map<string, string> {
  const lines = bytes.toString('utf8').split(/\r?\n/).filter((line) => line.trim().length > 0);
  if (lines[0] !== `schema=${SESSION_PACKAGE_SCHEMA_VERSION}`) throw new SessionPackageFormatError('Unsupported session package schema');
  const manifest = new Map<string, string>();
  for (const line of lines.slice(1)) {
    const separator = line.indexOf('  ');
    if (separator !== 64) throw new SessionPackageFormatError('Invalid session package manifest');
    const hash = line.slice(0, separator);
    const path = line.slice(separator + 2);
    if (!/^[0-9a-f]{64}$/.test(hash)) throw new SessionPackageFormatError('Invalid session package manifest');
    safeArchivePath(path, false);
    if (basename(path) === SESSION_PACKAGE_MANIFEST || manifest.has(path)) throw new SessionPackageFormatError('Invalid session package manifest');
    manifest.set(path, hash);
  }
  return manifest;
}

function serializeManifest(entries: readonly ZipEntry[]): Buffer {
  const records = entries
    .filter((entry) => !entry.directory)
    .sort((left, right) => left.name.localeCompare(right.name))
    .map((entry) => `${sha256(entry.bytes)}  ${entry.name}\n`);
  return Buffer.from(`schema=${SESSION_PACKAGE_SCHEMA_VERSION}\n${records.join('')}`, 'utf8');
}

function readZip(zip: Buffer, limits: ResolvedSessionPackageLimits): ZipEntry[] {
  if (zip.length < 22) throw new SessionPackageFormatError('Session package ZIP data is invalid');
  const eocd = findEndOfCentralDirectory(zip);
  ensureRange(zip, eocd, 22);
  const commentLength = zip.readUInt16LE(eocd + 20);
  if (eocd + 22 + commentLength !== zip.length) throw new SessionPackageFormatError('Session package ZIP data is invalid');

  const disk = zip.readUInt16LE(eocd + 4);
  const centralDisk = zip.readUInt16LE(eocd + 6);
  let entriesOnDisk = zip.readUInt16LE(eocd + 8);
  let entryCount = zip.readUInt16LE(eocd + 10);
  let centralSize = zip.readUInt32LE(eocd + 12);
  let centralOffset = zip.readUInt32LE(eocd + 16);
  if (disk !== 0 || centralDisk !== 0 || entriesOnDisk !== entryCount) throw new SessionPackageFormatError('Session package ZIP data is invalid');

  if (entriesOnDisk === UINT16_MAX || entryCount === UINT16_MAX || centralSize === UINT32_MAX || centralOffset === UINT32_MAX) {
    const zip64 = readZip64EndRecord(zip, eocd);
    entriesOnDisk = zip64.entriesOnDisk;
    entryCount = zip64.entryCount;
    centralSize = zip64.centralSize;
    centralOffset = zip64.centralOffset;
  }
  if (entriesOnDisk !== entryCount || entryCount > limits.maxEntries || centralOffset + centralSize > eocd) {
    throw new SessionPackageFormatError('Session package entry count is out of bounds');
  }

  const entries: ZipEntry[] = [];
  let offset = centralOffset;
  for (let index = 0; index < entryCount; index += 1) {
    ensureRange(zip, offset, 46);
    if (zip.readUInt32LE(offset) !== ZIP_CENTRAL_DIRECTORY_HEADER) throw new SessionPackageFormatError('Session package ZIP data is invalid');
    const flags = zip.readUInt16LE(offset + 8);
    const method = zip.readUInt16LE(offset + 10);
    let compressedSize = zip.readUInt32LE(offset + 20);
    let uncompressedSize = zip.readUInt32LE(offset + 24);
    const nameLength = zip.readUInt16LE(offset + 28);
    const extraLength = zip.readUInt16LE(offset + 30);
    const commentLength = zip.readUInt16LE(offset + 32);
    let localOffset = zip.readUInt32LE(offset + 42);
    const end = offset + 46 + nameLength + extraLength + commentLength;
    ensureRange(zip, offset, end - offset);
    if ((flags & 1) !== 0 || (method !== 0 && method !== 8)) throw new SessionPackageFormatError('Session package ZIP data is invalid');

    const name = decodeZipName(zip.subarray(offset + 46, offset + 46 + nameLength), flags);
    const directory = name.endsWith('/');
    safeArchivePath(name, directory);
    const extra = zip.subarray(offset + 46 + nameLength, offset + 46 + nameLength + extraLength);
    if (compressedSize === UINT32_MAX || uncompressedSize === UINT32_MAX || localOffset === UINT32_MAX) {
      const zip64 = parseZip64Extra(extra, compressedSize === UINT32_MAX, uncompressedSize === UINT32_MAX, localOffset === UINT32_MAX);
      compressedSize = zip64.compressedSize ?? compressedSize;
      uncompressedSize = zip64.uncompressedSize ?? uncompressedSize;
      localOffset = zip64.localOffset ?? localOffset;
    }
    if (compressedSize > limits.maxEntryBytes || uncompressedSize > limits.maxEntryBytes) {
      throw new SessionPackageFormatError('Session package entry exceeds size limit: ' + name);
    }

    ensureRange(zip, localOffset, 30);
    if (zip.readUInt32LE(localOffset) !== ZIP_LOCAL_FILE_HEADER) throw new SessionPackageFormatError('Session package ZIP data is invalid');
    const localFlags = zip.readUInt16LE(localOffset + 6);
    const localMethod = zip.readUInt16LE(localOffset + 8);
    const localNameLength = zip.readUInt16LE(localOffset + 26);
    const localExtraLength = zip.readUInt16LE(localOffset + 28);
    ensureRange(zip, localOffset + 30, localNameLength + localExtraLength);
    const localName = decodeZipName(zip.subarray(localOffset + 30, localOffset + 30 + localNameLength), localFlags);
    if ((localFlags & 1) !== 0 || localMethod !== method || localName !== name || (localFlags & ZIP_UTF8_FLAG) !== (flags & ZIP_UTF8_FLAG)) {
      throw new SessionPackageFormatError('Session package ZIP data is invalid');
    }
    const dataOffset = localOffset + 30 + localNameLength + localExtraLength;
    if (dataOffset + compressedSize > centralOffset) throw new SessionPackageFormatError('Session package ZIP data is invalid');
    const compressed = zip.subarray(dataOffset, dataOffset + compressedSize);
    let bytes: Buffer;
    try {
      bytes = method === 0 ? Buffer.from(compressed) : inflateRawSync(compressed, { maxOutputLength: uncompressedSize + 1 });
    } catch (error) {
      throw packageError('Session package ZIP data is invalid: ' + describe(error), error);
    }
    if (bytes.length !== uncompressedSize || crc32(bytes) !== zip.readUInt32LE(offset + 16)) {
      throw new SessionPackageFormatError('Session package ZIP data is invalid');
    }
    if (directory && bytes.length !== 0) throw new SessionPackageFormatError('Session package ZIP data is invalid');
    entries.push({ name, bytes, directory });
    offset = end;
  }
  if (offset !== centralOffset + centralSize) throw new SessionPackageFormatError('Session package ZIP data is invalid');
  return entries;
}

function readZip64EndRecord(zip: Buffer, eocd: number): {
  readonly entriesOnDisk: number;
  readonly entryCount: number;
  readonly centralSize: number;
  readonly centralOffset: number;
} {
  const locator = eocd - 20;
  ensureRange(zip, locator, 20);
  if (zip.readUInt32LE(locator) !== ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR || zip.readUInt32LE(locator + 4) !== 0 || zip.readUInt32LE(locator + 16) !== 1) {
    throw new SessionPackageFormatError('Session package ZIP64 data is invalid');
  }
  const recordOffset = numberFromUInt64(zip.readBigUInt64LE(locator + 8), 'Session package ZIP64 data is invalid');
  ensureRange(zip, recordOffset, 56);
  if (zip.readUInt32LE(recordOffset) !== ZIP64_END_OF_CENTRAL_DIRECTORY || numberFromUInt64(zip.readBigUInt64LE(recordOffset + 4), 'Session package ZIP64 data is invalid') < 44) {
    throw new SessionPackageFormatError('Session package ZIP64 data is invalid');
  }
  if (zip.readUInt32LE(recordOffset + 16) !== 0 || zip.readUInt32LE(recordOffset + 20) !== 0) throw new SessionPackageFormatError('Session package ZIP64 data is invalid');
  return {
    entriesOnDisk: numberFromUInt64(zip.readBigUInt64LE(recordOffset + 24), 'Session package ZIP64 data is invalid'),
    entryCount: numberFromUInt64(zip.readBigUInt64LE(recordOffset + 32), 'Session package ZIP64 data is invalid'),
    centralSize: numberFromUInt64(zip.readBigUInt64LE(recordOffset + 40), 'Session package ZIP64 data is invalid'),
    centralOffset: numberFromUInt64(zip.readBigUInt64LE(recordOffset + 48), 'Session package ZIP64 data is invalid'),
  };
}

function parseZip64Extra(extra: Buffer, needsCompressed: boolean, needsUncompressed: boolean, needsOffset: boolean): Zip64Values {
  let offset = 0;
  while (offset < extra.length) {
    ensureRange(extra, offset, 4);
    const id = extra.readUInt16LE(offset);
    const size = extra.readUInt16LE(offset + 2);
    ensureRange(extra, offset + 4, size);
    if (id === ZIP64_EXTRA_FIELD) {
      let valueOffset = offset + 4;
      const end = valueOffset + size;
      const next = (): number => {
        ensureRange(extra, valueOffset, 8);
        const value = numberFromUInt64(extra.readBigUInt64LE(valueOffset), 'Session package ZIP64 data is invalid');
        valueOffset += 8;
        return value;
      };
      const uncompressedSize = needsUncompressed ? next() : undefined;
      const compressedSize = needsCompressed ? next() : undefined;
      const localOffset = needsOffset ? next() : undefined;
      if (valueOffset > end) throw new SessionPackageFormatError('Session package ZIP64 data is invalid');
      return { uncompressedSize, compressedSize, localOffset };
    }
    offset += 4 + size;
  }
  throw new SessionPackageFormatError('Session package ZIP64 data is invalid');
}

function writeZip(entries: readonly ZipEntry[]): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let localOffset = 0;

  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const compressed = deflateRawSync(entry.bytes);
    const crc = crc32(entry.bytes);
    const requiresZip64Sizes = entry.bytes.length > UINT32_MAX || compressed.length > UINT32_MAX;
    const requiresZip64Offset = localOffset > UINT32_MAX;
    const localExtra = requiresZip64Sizes ? zip64Extra(entry.bytes.length, compressed.length) : ZIP_EPOCH_TIMESTAMP_EXTRA;
    const centralExtraParts: Buffer[] = [ZIP_EPOCH_TIMESTAMP_EXTRA];
    if (requiresZip64Sizes || requiresZip64Offset) {
      centralExtraParts.push(zip64Extra(
        requiresZip64Sizes ? entry.bytes.length : undefined,
        requiresZip64Sizes ? compressed.length : undefined,
        requiresZip64Offset ? localOffset : undefined,
      ));
    }
    const centralExtra = Buffer.concat(centralExtraParts);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(ZIP_LOCAL_FILE_HEADER, 0);
    local.writeUInt16LE(requiresZip64Sizes ? 45 : 20, 4);
    local.writeUInt16LE(ZIP_UTF8_FLAG, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(ZIP_EPOCH_DOS_DATE, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(requiresZip64Sizes ? UINT32_MAX : compressed.length, 18);
    local.writeUInt32LE(requiresZip64Sizes ? UINT32_MAX : entry.bytes.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(localExtra.length, 28);
    locals.push(local, name, localExtra, compressed);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(ZIP_CENTRAL_DIRECTORY_HEADER, 0);
    central.writeUInt16LE(45, 4);
    central.writeUInt16LE(requiresZip64Sizes || requiresZip64Offset ? 45 : 20, 6);
    central.writeUInt16LE(ZIP_UTF8_FLAG, 8);
    central.writeUInt16LE(8, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(ZIP_EPOCH_DOS_DATE, 14);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(requiresZip64Sizes ? UINT32_MAX : compressed.length, 20);
    central.writeUInt32LE(requiresZip64Sizes ? UINT32_MAX : entry.bytes.length, 24);
    central.writeUInt16LE(name.length, 28);
    central.writeUInt16LE(centralExtra.length, 30);
    central.writeUInt32LE(requiresZip64Offset ? UINT32_MAX : localOffset, 42);
    centrals.push(central, name, centralExtra);
    localOffset += local.length + name.length + localExtra.length + compressed.length;
  }

  const centralSize = centrals.reduce((sum, part) => sum + part.length, 0);
  const requiresZip64End = entries.length > UINT16_MAX || centralSize > UINT32_MAX || localOffset > UINT32_MAX;
  const endParts = [...locals, ...centrals];
  if (requiresZip64End) {
    const zip64Offset = localOffset + centralSize;
    const zip64End = Buffer.alloc(56);
    zip64End.writeUInt32LE(ZIP64_END_OF_CENTRAL_DIRECTORY, 0);
    zip64End.writeBigUInt64LE(44n, 4);
    zip64End.writeUInt16LE(45, 12);
    zip64End.writeUInt16LE(45, 14);
    zip64End.writeBigUInt64LE(BigInt(entries.length), 24);
    zip64End.writeBigUInt64LE(BigInt(entries.length), 32);
    zip64End.writeBigUInt64LE(BigInt(centralSize), 40);
    zip64End.writeBigUInt64LE(BigInt(localOffset), 48);
    const locator = Buffer.alloc(20);
    locator.writeUInt32LE(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR, 0);
    locator.writeBigUInt64LE(BigInt(zip64Offset), 8);
    locator.writeUInt32LE(1, 16);
    endParts.push(zip64End, locator);
  }
  const end = Buffer.alloc(22);
  end.writeUInt32LE(ZIP_END_OF_CENTRAL_DIRECTORY, 0);
  end.writeUInt16LE(requiresZip64End ? UINT16_MAX : entries.length, 8);
  end.writeUInt16LE(requiresZip64End ? UINT16_MAX : entries.length, 10);
  end.writeUInt32LE(requiresZip64End || centralSize > UINT32_MAX ? UINT32_MAX : centralSize, 12);
  end.writeUInt32LE(requiresZip64End || localOffset > UINT32_MAX ? UINT32_MAX : localOffset, 16);
  endParts.push(end);
  return Buffer.concat(endParts);
}

function zip64Extra(uncompressedSize?: number, compressedSize?: number, localOffset?: number): Buffer {
  const values = [uncompressedSize, compressedSize, localOffset].filter((value): value is number => value !== undefined);
  const extra = Buffer.alloc(4 + values.length * 8);
  extra.writeUInt16LE(ZIP64_EXTRA_FIELD, 0);
  extra.writeUInt16LE(values.length * 8, 2);
  values.forEach((value, index) => extra.writeBigUInt64LE(BigInt(value), 4 + index * 8));
  return extra;
}

function findEndOfCentralDirectory(zip: Buffer): number {
  const earliest = Math.max(0, zip.length - 65_557);
  for (let offset = zip.length - 22; offset >= earliest; offset -= 1) {
    if (zip.readUInt32LE(offset) === ZIP_END_OF_CENTRAL_DIRECTORY) return offset;
  }
  throw new SessionPackageFormatError('Session package ZIP data is invalid: end record is missing');
}

function decodeZipName(bytes: Buffer, flags: number): string {
  if ((flags & ZIP_DATA_DESCRIPTOR_FLAG) !== 0 || (flags & ZIP_UTF8_FLAG) !== 0) return bytes.toString('utf8');
  return bytes.toString('utf8');
}

function safeArchivePath(path: string, directory: boolean): void {
  const candidate = directory ? path.slice(0, -1) : path;
  const segments = candidate.split('/');
  if (
    candidate.trim().length === 0 ||
    candidate.startsWith('/') ||
    candidate.includes('\\') ||
    segments.some((part) => part.length === 0 || part === '.' || part === '..')
  ) {
    throw new SessionPackageFormatError('Unsafe session package entry: ' + path);
  }
}

function safeOutputPath(root: string, path: string, directory = false): string {
  safeArchivePath(path, directory);
  const normalizedPath = directory ? path.slice(0, -1) : path;
  const output = resolve(root, ...normalizedPath.split('/'));
  if (output !== root && !output.startsWith(root + sep)) throw new SessionPackageFormatError('Unsafe session package entry: ' + path);
  return output;
}

function portableRelativePath(root: string, path: string): string {
  const result = relative(root, path).split(sep).join('/');
  if (result.length === 0 || result.startsWith('../')) throw new SessionPackageFormatError('Unsafe session package source path: ' + path);
  return result;
}

function sessionName(archive: string): string {
  const name = basename(archive);
  return name.replace(/\.apsession\.zip$/, '').replace(/\.zip$/, '') || 'session';
}

async function reserveDestination(root: string, base: string): Promise<string> {
  for (let suffix = 0; ; suffix += 1) {
    const candidate = join(root, suffix === 0 ? base : `${base}-${suffix}`);
    try {
      await mkdir(candidate);
      return candidate;
    } catch (error) {
      if (isAlreadyExists(error)) continue;
      throw error;
    }
  }
}

async function removeTree(path: string): Promise<void> {
  const metadata = await lstat(path).catch(() => undefined);
  if (metadata === undefined) return;
  if (metadata.isDirectory() && !metadata.isSymbolicLink()) {
    for (const entry of await readdir(path)) await removeTree(join(path, entry));
    await rmdir(path);
  } else {
    await unlink(path);
  }
}

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

function crc32(bytes: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) === 0 ? 0 : 0xedb88320);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function ensureRange(bytes: Buffer, offset: number, length: number): void {
  if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(length) || offset < 0 || length < 0 || offset > bytes.length - length) {
    throw new SessionPackageFormatError('Session package ZIP data is invalid');
  }
}

function checkedSum(total: number, next: number, maximum: number, message: string): number {
  const result = total + next;
  if (!Number.isSafeInteger(result) || result > maximum) throw new SessionPackageFormatError(message);
  return result;
}

function positiveSafeInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) throw new RangeError(`${name} must be a positive safe integer`);
  return value;
}

function numberFromUInt64(value: bigint, message: string): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new SessionPackageFormatError(message);
  return Number(value);
}

function isAlreadyExists(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'code' in error && error.code === 'EEXIST';
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function packageError(message: string, cause: unknown): SessionPackageFormatError {
  return cause instanceof SessionPackageFormatError ? cause : new SessionPackageFormatError(message, { cause });
}
