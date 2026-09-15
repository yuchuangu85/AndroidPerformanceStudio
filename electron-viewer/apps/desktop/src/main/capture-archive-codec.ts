import { createHash } from 'node:crypto';
import { parseCaptureArchiveSnapshotSizeMultiplier } from '@aps/settings';
import { readFile, rename, stat, unlink, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { deflateRawSync, inflateRawSync } from 'node:zlib';

export const CAPTURE_ARCHIVE_FORMAT = 'agentperf-inspector-capture';
export const LEGACY_CAPTURE_ARCHIVE_VERSION = 1;
export const CAPTURE_ARCHIVE_VERSION = 2;

export const CAPTURE_ARCHIVE_PATHS = {
  manifest: 'manifest.json',
  snapshot: 'capture/layout-snapshot.json',
  composeInspection: 'capture/compose-inspection.json',
  screenshot: 'capture/screenshot.png',
  analysisReport: 'report/analysis-report.json',
  aiAnalysisReport: 'report/ai-analysis-report.json',
  timelineHistory: 'timeline/history.json',
  rawZip: 'raw/visible-window-views.zip',
  rawText: 'raw/visible-window-views.txt',
} as const;

export interface CaptureArchiveLimits {
  readonly snapshotSizeMultiplier?: number;
}

export interface CaptureArchiveMetadata {
  readonly producerVersion: string;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly protocolMajor: number;
  readonly protocolMinor: number;
}

export interface CaptureArchivePayload {
  readonly snapshotJson: string;
  readonly screenshotPng?: Buffer;
  readonly rawArtifacts?: { readonly zip: Buffer; readonly text: string };
  readonly analysisReportJson?: string;
  readonly aiAnalysisReportJson?: string;
  readonly timelineHistoryJson?: string;
  readonly composeInspectionJson?: string;
}

export interface CaptureArchiveDocument {
  readonly metadata: CaptureArchiveMetadata;
  readonly payload: CaptureArchivePayload;
  readonly archiveVersion: number;
}

interface ManifestEntry {
  readonly path: string;
  readonly size: number;
  readonly sha256: string;
  readonly required: boolean;
}

interface Manifest extends CaptureArchiveMetadata {
  readonly format: string;
  readonly archiveVersion: number;
  readonly entries: readonly ManifestEntry[];
}

export class CaptureArchiveFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CaptureArchiveFormatError';
  }
}

const MIB = 1024 * 1024;
const MAX_ENTRY_COUNT = 17;
const MAX_MANIFEST_BYTES = 256 * 1024;
const MAX_SCREENSHOT_BYTES = 32 * MIB;
const MAX_RAW_ZIP_BYTES = 32 * MIB;
const MAX_RAW_TEXT_BYTES = 8 * MIB;
const MAX_ANALYSIS_REPORT_BYTES = 4 * MIB;
const MAX_TIMELINE_HISTORY_BYTES = 4 * MIB;
const MAX_COMPOSE_INSPECTION_BYTES = 64 * MIB;
const MAX_UNKNOWN_OPTIONAL_BYTES = MIB;
const BASE_MAX_TOTAL_BYTES = 156 * MIB;
const BASE_MAX_ARCHIVE_BYTES = 172 * MIB;
const REQUIRED_PATHS = new Set<string>([CAPTURE_ARCHIVE_PATHS.snapshot]);
const KNOWN_PATHS = new Set<string>(Object.values(CAPTURE_ARCHIVE_PATHS).filter((path) => path !== CAPTURE_ARCHIVE_PATHS.manifest));

export interface ResolvedCaptureArchiveLimits {
  readonly snapshot: number;
  readonly total: number;
  readonly archive: number;
}

/** Kotlin-compatible archive limits for a persisted snapshot-size multiplier. */
export function captureArchiveLimitsFor(input: CaptureArchiveLimits = {}): ResolvedCaptureArchiveLimits {
  const multiplier = parseCaptureArchiveSnapshotSizeMultiplier(input.snapshotSizeMultiplier);
  const increase = (108 * multiplier - 108) * MIB;
  return {
    snapshot: 108 * multiplier * MIB,
    total: BASE_MAX_TOTAL_BYTES + increase,
    archive: BASE_MAX_ARCHIVE_BYTES + increase,
  };
}

function maximumEntryBytes(path: string, limits: ResolvedCaptureArchiveLimits): number {
  switch (path) {
    case CAPTURE_ARCHIVE_PATHS.snapshot: return limits.snapshot;
    case CAPTURE_ARCHIVE_PATHS.screenshot: return MAX_SCREENSHOT_BYTES;
    case CAPTURE_ARCHIVE_PATHS.rawZip: return MAX_RAW_ZIP_BYTES;
    case CAPTURE_ARCHIVE_PATHS.rawText: return MAX_RAW_TEXT_BYTES;
    case CAPTURE_ARCHIVE_PATHS.analysisReport:
    case CAPTURE_ARCHIVE_PATHS.aiAnalysisReport: return MAX_ANALYSIS_REPORT_BYTES;
    case CAPTURE_ARCHIVE_PATHS.timelineHistory: return MAX_TIMELINE_HISTORY_BYTES;
    case CAPTURE_ARCHIVE_PATHS.composeInspection: return MAX_COMPOSE_INSPECTION_BYTES;
    default: return MAX_UNKNOWN_OPTIONAL_BYTES;
  }
}

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

function safePath(path: string): void {
  const segments = path.split('/');
  if (path.trim().length === 0 || path.startsWith('/') || path.includes('\\') || segments.some((part) => part.length === 0 || part === '.' || part === '..')) {
    throw new CaptureArchiveFormatError('Archive entry path is unsafe: ' + path);
  }
}

function parseManifest(bytes: Buffer): Manifest {
  let value: unknown;
  try { value = JSON.parse(bytes.toString('utf8')); } catch (error) {
    throw new CaptureArchiveFormatError('Archive manifest is invalid: ' + describe(error));
  }
  if (value === null || typeof value !== 'object') throw new CaptureArchiveFormatError('Archive manifest is invalid');
  const raw = value as Record<string, unknown>;
  const entries = raw['entries'];
  if (
    typeof raw['format'] !== 'string' || typeof raw['archiveVersion'] !== 'number' ||
    typeof raw['producerVersion'] !== 'string' || typeof raw['packageName'] !== 'string' ||
    typeof raw['capturedAtEpochMillis'] !== 'number' || !Number.isSafeInteger(raw['capturedAtEpochMillis']) ||
    typeof raw['protocolMajor'] !== 'number' || !Number.isInteger(raw['protocolMajor']) ||
    typeof raw['protocolMinor'] !== 'number' || !Number.isInteger(raw['protocolMinor']) || !Array.isArray(entries)
  ) throw new CaptureArchiveFormatError('Archive manifest is invalid');
  const parsedEntries = entries.map((entry): ManifestEntry => {
    if (entry === null || typeof entry !== 'object') throw new CaptureArchiveFormatError('Archive manifest is invalid');
    const item = entry as Record<string, unknown>;
    if (typeof item['path'] !== 'string' || typeof item['size'] !== 'number' || !Number.isSafeInteger(item['size']) || typeof item['sha256'] !== 'string' || typeof item['required'] !== 'boolean') {
      throw new CaptureArchiveFormatError('Archive manifest is invalid');
    }
    return { path: item['path'], size: item['size'], sha256: item['sha256'], required: item['required'] };
  });
  return {
    format: raw['format'], archiveVersion: raw['archiveVersion'], producerVersion: raw['producerVersion'],
    packageName: raw['packageName'], capturedAtEpochMillis: raw['capturedAtEpochMillis'],
    protocolMajor: raw['protocolMajor'], protocolMinor: raw['protocolMinor'], entries: parsedEntries,
  };
}

function describe(error: unknown): string { return error instanceof Error ? error.message : String(error); }

interface ZipEntry { readonly name: string; readonly bytes: Buffer; }

function findEndOfCentralDirectory(zip: Buffer): number {
  const earliest = Math.max(0, zip.length - 65_557);
  for (let offset = zip.length - 22; offset >= earliest; offset -= 1) {
    if (zip.readUInt32LE(offset) === 0x06054b50) return offset;
  }
  throw new CaptureArchiveFormatError('Archive ZIP data is invalid: end record is missing');
}

function readZip(zip: Buffer, maximumArchiveBytes: number): ZipEntry[] {
  if (zip.length > maximumArchiveBytes) throw new CaptureArchiveFormatError('Archive file is too large');
  if (zip.length < 22) throw new CaptureArchiveFormatError('Archive ZIP data is invalid');
  const eocd = findEndOfCentralDirectory(zip);
  const disk = zip.readUInt16LE(eocd + 4);
  const centralDisk = zip.readUInt16LE(eocd + 6);
  const count = zip.readUInt16LE(eocd + 10);
  const centralSize = zip.readUInt32LE(eocd + 12);
  const centralOffset = zip.readUInt32LE(eocd + 16);
  if (disk !== 0 || centralDisk !== 0 || count === 0 || count > MAX_ENTRY_COUNT || centralOffset + centralSize > eocd) {
    throw new CaptureArchiveFormatError('Archive entry count is out of bounds');
  }
  const entries: ZipEntry[] = [];
  let offset = centralOffset;
  for (let index = 0; index < count; index += 1) {
    if (offset + 46 > zip.length || zip.readUInt32LE(offset) !== 0x02014b50) throw new CaptureArchiveFormatError('Archive ZIP data is invalid');
    const flags = zip.readUInt16LE(offset + 8);
    const method = zip.readUInt16LE(offset + 10);
    const compressedSize = zip.readUInt32LE(offset + 20);
    const uncompressedSize = zip.readUInt32LE(offset + 24);
    const nameLength = zip.readUInt16LE(offset + 28);
    const extraLength = zip.readUInt16LE(offset + 30);
    const commentLength = zip.readUInt16LE(offset + 32);
    const localOffset = zip.readUInt32LE(offset + 42);
    const end = offset + 46 + nameLength + extraLength + commentLength;
    if (end > zip.length || (flags & 1) !== 0 || (method !== 0 && method !== 8)) throw new CaptureArchiveFormatError('Archive ZIP data is invalid');
    const name = zip.subarray(offset + 46, offset + 46 + nameLength).toString((flags & 0x800) !== 0 ? 'utf8' : 'utf8');
    safePath(name);
    if (name.endsWith('/')) throw new CaptureArchiveFormatError('Archive contains a directory entry: ' + name);
    if (localOffset + 30 > zip.length || zip.readUInt32LE(localOffset) !== 0x04034b50) throw new CaptureArchiveFormatError('Archive ZIP data is invalid');
    const localNameLength = zip.readUInt16LE(localOffset + 26);
    const localExtraLength = zip.readUInt16LE(localOffset + 28);
    const dataOffset = localOffset + 30 + localNameLength + localExtraLength;
    if (dataOffset + compressedSize > zip.length) throw new CaptureArchiveFormatError('Archive ZIP data is invalid');
    const compressed = zip.subarray(dataOffset, dataOffset + compressedSize);
    let bytes: Buffer;
    try {
      bytes = method === 0 ? Buffer.from(compressed) : inflateRawSync(compressed, { maxOutputLength: uncompressedSize + 1 });
    } catch (error) {
      throw new CaptureArchiveFormatError('Archive ZIP data is invalid: ' + describe(error));
    }
    if (bytes.length !== uncompressedSize) throw new CaptureArchiveFormatError('Archive ZIP data is invalid');
    entries.push({ name, bytes });
    offset = end;
  }
  if (new Set(entries.map((entry) => entry.name)).size !== entries.length) throw new CaptureArchiveFormatError('Archive contains duplicate entries');
  return entries;
}

function crc32(bytes: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) === 0 ? 0 : 0xedb88320);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function writeZip(entries: readonly ZipEntry[]): Buffer {
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let localOffset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8');
    const compressed = deflateRawSync(entry.bytes);
    const crc = crc32(entry.bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6); local.writeUInt16LE(8, 8);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(compressed.length, 18); local.writeUInt32LE(entry.bytes.length, 22); local.writeUInt16LE(name.length, 26);
    locals.push(local, name, compressed);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8); central.writeUInt16LE(8, 10);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(compressed.length, 20); central.writeUInt32LE(entry.bytes.length, 24); central.writeUInt16LE(name.length, 28); central.writeUInt32LE(localOffset, 42);
    centrals.push(central, name);
    localOffset += local.length + name.length + compressed.length;
  }
  const centralSize = centrals.reduce((sum, part) => sum + part.length, 0);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralSize, 12); eocd.writeUInt32LE(localOffset, 16);
  return Buffer.concat([...locals, ...centrals, eocd]);
}

function payloadEntries(payload: CaptureArchivePayload, limits: ResolvedCaptureArchiveLimits): Map<string, Buffer> {
  const entries = new Map<string, Buffer>([[CAPTURE_ARCHIVE_PATHS.snapshot, Buffer.from(payload.snapshotJson, 'utf8')]]);
  if (payload.screenshotPng !== undefined) entries.set(CAPTURE_ARCHIVE_PATHS.screenshot, payload.screenshotPng);

  const addOptionalGroup = (group: readonly [string, Buffer][], required: boolean): void => {
    const currentTotal = [...entries.values()].reduce((sum, item) => sum + item.length, 0);
    const fits = group.every(([path, bytes]) => bytes.length <= maximumEntryBytes(path, limits)) &&
      currentTotal + group.reduce((sum, [, bytes]) => sum + bytes.length, 0) <= limits.total;
    if (!fits && required) throw new RangeError('Compose inspection details exceed the archive limits');
    if (fits) for (const [path, bytes] of group) entries.set(path, bytes);
  };

  if (payload.composeInspectionJson !== undefined) addOptionalGroup([
    [CAPTURE_ARCHIVE_PATHS.composeInspection, Buffer.from(payload.composeInspectionJson)],
  ], true);
  if (payload.analysisReportJson !== undefined) addOptionalGroup([
    [CAPTURE_ARCHIVE_PATHS.analysisReport, Buffer.from(payload.analysisReportJson)],
  ], false);
  if (payload.aiAnalysisReportJson !== undefined) addOptionalGroup([
    [CAPTURE_ARCHIVE_PATHS.aiAnalysisReport, Buffer.from(payload.aiAnalysisReportJson)],
  ], false);
  if (payload.timelineHistoryJson !== undefined) addOptionalGroup([
    [CAPTURE_ARCHIVE_PATHS.timelineHistory, Buffer.from(payload.timelineHistoryJson)],
  ], false);
  if (payload.rawArtifacts !== undefined) addOptionalGroup([
    [CAPTURE_ARCHIVE_PATHS.rawZip, payload.rawArtifacts.zip],
    [CAPTURE_ARCHIVE_PATHS.rawText, Buffer.from(payload.rawArtifacts.text)],
  ], false);

  for (const [path, bytes] of entries) if (bytes.length > maximumEntryBytes(path, limits)) throw new RangeError('Archive entry is too large: ' + path);
  if ([...entries.values()].reduce((sum, bytes) => sum + bytes.length, 0) > limits.total) throw new RangeError('Archive uncompressed content is too large');
  return entries;
}

export class CaptureArchiveCodec {
  private readonly limits: ResolvedCaptureArchiveLimits;
  constructor(limits: CaptureArchiveLimits = {}, private readonly replace: (temporary: string, target: string) => Promise<void> = rename) {
    this.limits = captureArchiveLimitsFor(limits);
  }

  async write(target: string, metadata: CaptureArchiveMetadata, payload: CaptureArchivePayload): Promise<{ path: string; rawArtifactsIncluded: boolean }> {
    const content = payloadEntries(payload, this.limits);
    const archiveVersion = content.has(CAPTURE_ARCHIVE_PATHS.composeInspection) ? 2 : 1;
    const manifest: Manifest = { format: CAPTURE_ARCHIVE_FORMAT, archiveVersion, ...metadata, entries: [...content].map(([path, bytes]) => ({ path, size: bytes.length, sha256: sha256(bytes), required: REQUIRED_PATHS.has(path) })) };
    const manifestBytes = Buffer.from(JSON.stringify(manifest, null, 2), 'utf8');
    if (manifestBytes.length > MAX_MANIFEST_BYTES) throw new RangeError('Archive manifest is too large');
    const temporary = join(dirname(target), '.agentperf-capture-' + process.pid + '-' + Date.now() + '.tmp');
    try {
      await writeFile(temporary, writeZip([{ name: CAPTURE_ARCHIVE_PATHS.manifest, bytes: manifestBytes }, ...[...content].map(([name, bytes]) => ({ name, bytes }))]));
      await this.replace(temporary, target);
      return { path: target, rawArtifactsIncluded: content.has(CAPTURE_ARCHIVE_PATHS.rawZip) };
    } finally { await unlink(temporary).catch(() => undefined); }
  }

  async read(source: string): Promise<CaptureArchiveDocument> {
    const file = await stat(source).catch(() => undefined);
    if (file === undefined || !file.isFile()) throw new CaptureArchiveFormatError('Archive is not a regular file');
    const zipEntries = readZip(await readFile(source), this.limits.archive);
    const byName = new Map(zipEntries.map((entry) => [entry.name, entry.bytes]));
    const manifestBytes = byName.get(CAPTURE_ARCHIVE_PATHS.manifest);
    if (manifestBytes === undefined) throw new CaptureArchiveFormatError('Archive manifest is missing');
    if (manifestBytes.length > MAX_MANIFEST_BYTES) throw new CaptureArchiveFormatError('Archive manifest is too large');
    const manifest = parseManifest(manifestBytes);
    if (manifest.format !== CAPTURE_ARCHIVE_FORMAT) throw new CaptureArchiveFormatError('Unsupported archive format');
    if (manifest.archiveVersion < 1 || manifest.archiveVersion > 2) throw new CaptureArchiveFormatError('Unsupported archive version ' + manifest.archiveVersion);
    const declared = manifest.entries.map((entry) => entry.path);
    if (new Set(declared).size !== declared.length) throw new CaptureArchiveFormatError('Archive manifest contains duplicate paths');
    for (const path of declared) safePath(path);
    const expected = new Set([CAPTURE_ARCHIVE_PATHS.manifest, ...declared]);
    if (expected.size !== byName.size || [...expected].some((name) => !byName.has(name))) throw new CaptureArchiveFormatError('Archive entries do not match the manifest');
    if (manifest.archiveVersion === 1 && declared.includes(CAPTURE_ARCHIVE_PATHS.composeInspection)) throw new CaptureArchiveFormatError('Archive v1 cannot contain Compose inspection details');
    for (const required of REQUIRED_PATHS) {
      const entry = manifest.entries.find((item) => item.path === required);
      if (entry === undefined) throw new CaptureArchiveFormatError('Archive entry is missing: ' + required);
      if (!entry.required) throw new CaptureArchiveFormatError('Archive required entry is not marked required: ' + required);
    }
    const unsupported = manifest.entries.find((entry) => entry.required && !KNOWN_PATHS.has(entry.path));
    if (unsupported !== undefined) throw new CaptureArchiveFormatError('Archive requires an unsupported entry: ' + unsupported.path);
    if (declared.includes(CAPTURE_ARCHIVE_PATHS.rawZip) !== declared.includes(CAPTURE_ARCHIVE_PATHS.rawText)) throw new CaptureArchiveFormatError('Archive raw Visible Window Views files are incomplete');
    let total = 0;
    for (const entry of manifest.entries) {
      if (entry.size < 0 || entry.size > maximumEntryBytes(entry.path, this.limits)) throw new CaptureArchiveFormatError('Archive entry size is out of bounds: ' + entry.path);
      total += entry.size;
      if (total > this.limits.total) throw new CaptureArchiveFormatError('Archive uncompressed content is too large');
      const bytes = byName.get(entry.path);
      if (bytes === undefined || bytes.length !== entry.size || sha256(bytes) !== entry.sha256) throw new CaptureArchiveFormatError('Archive entry failed integrity validation: ' + entry.path);
    }
    const text = (path: string): string | undefined => byName.get(path)?.toString('utf8');
    const snapshotJson = text(CAPTURE_ARCHIVE_PATHS.snapshot);
    if (snapshotJson === undefined) throw new CaptureArchiveFormatError('Archive entry is missing: ' + CAPTURE_ARCHIVE_PATHS.snapshot);
    const rawZip = byName.get(CAPTURE_ARCHIVE_PATHS.rawZip);
    return {
      archiveVersion: manifest.archiveVersion,
      metadata: { producerVersion: manifest.producerVersion, packageName: manifest.packageName, capturedAtEpochMillis: manifest.capturedAtEpochMillis, protocolMajor: manifest.protocolMajor, protocolMinor: manifest.protocolMinor },
      payload: {
        snapshotJson,
        ...(byName.get(CAPTURE_ARCHIVE_PATHS.screenshot) !== undefined ? { screenshotPng: byName.get(CAPTURE_ARCHIVE_PATHS.screenshot) } : {}),
        ...(rawZip !== undefined ? { rawArtifacts: { zip: rawZip, text: text(CAPTURE_ARCHIVE_PATHS.rawText) as string } } : {}),
        ...(text(CAPTURE_ARCHIVE_PATHS.analysisReport) !== undefined ? { analysisReportJson: text(CAPTURE_ARCHIVE_PATHS.analysisReport) } : {}),
        ...(text(CAPTURE_ARCHIVE_PATHS.aiAnalysisReport) !== undefined ? { aiAnalysisReportJson: text(CAPTURE_ARCHIVE_PATHS.aiAnalysisReport) } : {}),
        ...(text(CAPTURE_ARCHIVE_PATHS.timelineHistory) !== undefined ? { timelineHistoryJson: text(CAPTURE_ARCHIVE_PATHS.timelineHistory) } : {}),
        ...(text(CAPTURE_ARCHIVE_PATHS.composeInspection) !== undefined ? { composeInspectionJson: text(CAPTURE_ARCHIVE_PATHS.composeInspection) } : {}),
      },
    };
  }
}
