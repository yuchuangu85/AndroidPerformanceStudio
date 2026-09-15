import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
  CAPTURE_ARCHIVE_PATHS,
  captureArchiveLimitsFor,
  CaptureArchiveCodec,
  CaptureArchiveFormatError,
  type CaptureArchiveMetadata,
} from './capture-archive-codec.js';

const metadata: CaptureArchiveMetadata = {
  producerVersion: '0.4.6', packageName: 'com.example', capturedAtEpochMillis: 1234, protocolMajor: 1, protocolMinor: 1,
};
async function target(name: string): Promise<string> { return join(await mkdtemp(join(tmpdir(), 'aps-archive-')), name); }

function crc32(bytes: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) === 0 ? 0 : 0xedb88320);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function storedZip(entries: readonly { readonly name: string; readonly bytes: Buffer }[]): Buffer {
  const localParts: Buffer[] = [];
  const centralParts: Buffer[] = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name);
    const crc = crc32(entry.bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(entry.bytes.length, 18); local.writeUInt32LE(entry.bytes.length, 22); local.writeUInt16LE(name.length, 26);
    localParts.push(local, name, entry.bytes);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(entry.bytes.length, 20); central.writeUInt32LE(entry.bytes.length, 24); central.writeUInt16LE(name.length, 28); central.writeUInt32LE(offset, 42);
    centralParts.push(central, name);
    offset += local.length + name.length + entry.bytes.length;
  }
  const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(centralSize, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...localParts, ...centralParts, end]);
}

function manifest(entries: readonly { readonly path: string; readonly bytes: Buffer; readonly required?: boolean }[], archiveVersion = 1): Buffer {
  return Buffer.from(JSON.stringify({
    format: 'agentperf-inspector-capture', archiveVersion, ...metadata,
    entries: entries.map((entry) => ({ path: entry.path, size: entry.bytes.length, sha256: createHash('sha256').update(entry.bytes).digest('hex'), required: entry.required ?? false })),
  }));
}

async function writeArchive(path: string, entries: readonly { readonly path: string; readonly bytes: Buffer; readonly required?: boolean }[], archiveVersion = 1): Promise<void> {
  await writeFile(path, storedZip([{ name: 'manifest.json', bytes: manifest(entries, archiveVersion) }, ...entries.map((entry) => ({ name: entry.path, bytes: entry.bytes }))]));
}

describe('CaptureArchiveCodec', () => {
  it('derives the Kotlin archive limits from the persisted multiplier', () => {
    const mib = 1024 * 1024;
    expect(captureArchiveLimitsFor()).toEqual({
      snapshot: 108 * mib,
      total: 156 * mib,
      archive: 172 * mib,
    });
    expect(captureArchiveLimitsFor({ snapshotSizeMultiplier: 3 })).toEqual({
      snapshot: 324 * mib,
      total: 372 * mib,
      archive: 388 * mib,
    });
    expect(captureArchiveLimitsFor({ snapshotSizeMultiplier: 0 })).toEqual(captureArchiveLimitsFor());
    expect(captureArchiveLimitsFor({ snapshotSizeMultiplier: 11 })).toEqual({
      snapshot: 1080 * mib,
      total: 1128 * mib,
      archive: 1144 * mib,
    });
  });

  it('round-trips Kotlin-compatible v1 and v2 payloads', async () => {
    const codec = new CaptureArchiveCodec();
    const v1 = await target('v1.apinspect');
    await codec.write(v1, metadata, { snapshotJson: '{"protocolVersion":{"major":1,"minor":1}}', screenshotPng: Buffer.from([1, 2]), rawArtifacts: { zip: Buffer.from([0x50, 0x4b]), text: 'raw' } });
    const readV1 = await codec.read(v1);
    expect(readV1.archiveVersion).toBe(1);
    expect(readV1.payload.rawArtifacts?.text).toBe('raw');

    const v2 = await target('v2.apinspect');
    const optionalPayloads = {
      analysisReportJson: '{"metrics":{"nodeCount":1,"maxDepth":1,"widestLevel":1},"findings":[]}',
      aiAnalysisReportJson: '{"model":"gpt-test","summary":"ok","findings":[]}',
      timelineHistoryJson: '{"frames":[{"index":0,"capturedAtEpochMillis":1}]}',
    };
    await codec.write(v2, metadata, { snapshotJson: '{}', composeInspectionJson: '{"nodes":[]}', ...optionalPayloads });
    const readV2 = await codec.read(v2);
    expect(readV2.archiveVersion).toBe(2);
    expect(readV2.payload.composeInspectionJson).toBe('{"nodes":[]}');
    expect(readV2.payload.analysisReportJson).toBe(optionalPayloads.analysisReportJson);
    expect(readV2.payload.aiAnalysisReportJson).toBe(optionalPayloads.aiAnalysisReportJson);
    expect(readV2.payload.timelineHistoryJson).toBe(optionalPayloads.timelineHistoryJson);
  });

  it('rejects unsafe paths, duplicate entries, and files absent from the manifest', async () => {
    const codec = new CaptureArchiveCodec();
    const unsafe = await target('unsafe.apinspect');
    const snapshot = Buffer.from('{}');
    await writeArchive(unsafe, [{ path: '../evil.json', bytes: snapshot, required: true }]);
    await expect(codec.read(unsafe)).rejects.toBeInstanceOf(CaptureArchiveFormatError);

    const duplicate = await target('duplicate.apinspect');
    const declared = [{ path: CAPTURE_ARCHIVE_PATHS.snapshot, bytes: snapshot, required: true }];
    await writeFile(duplicate, storedZip([
      { name: 'manifest.json', bytes: manifest(declared) },
      { name: CAPTURE_ARCHIVE_PATHS.snapshot, bytes: snapshot },
      { name: CAPTURE_ARCHIVE_PATHS.snapshot, bytes: snapshot },
    ]));
    await expect(codec.read(duplicate)).rejects.toThrow(/duplicate/);

    const undeclared = await target('undeclared.apinspect');
    await writeFile(undeclared, storedZip([
      { name: 'manifest.json', bytes: manifest(declared) },
      { name: CAPTURE_ARCHIVE_PATHS.snapshot, bytes: snapshot },
      { name: 'extra.txt', bytes: Buffer.from('extra') },
    ]));
    await expect(codec.read(undeclared)).rejects.toThrow(/manifest/);
  });

  it('rejects integrity mismatches, unsupported versions, and incomplete raw pairs', async () => {
    const codec = new CaptureArchiveCodec();
    const snapshot = Buffer.from('{}');
    const integrity = await target('integrity.apinspect');
    const declared = [{ path: CAPTURE_ARCHIVE_PATHS.snapshot, bytes: snapshot, required: true }];
    await writeFile(integrity, storedZip([
      { name: 'manifest.json', bytes: manifest(declared) },
      { name: CAPTURE_ARCHIVE_PATHS.snapshot, bytes: Buffer.from('[]') },
    ]));
    await expect(codec.read(integrity)).rejects.toThrow(/integrity/);

    const version = await target('version.apinspect');
    await writeArchive(version, declared, 3);
    await expect(codec.read(version)).rejects.toThrow(/version 3/);

    const raw = await target('raw.apinspect');
    await writeArchive(raw, [
      ...declared,
      { path: CAPTURE_ARCHIVE_PATHS.rawZip, bytes: Buffer.from('PK') },
    ]);
    await expect(codec.read(raw)).rejects.toThrow(/incomplete/);
  });

  it('rejects an entry declared above the configurable snapshot limit before integrity validation', async () => {
    const codec = new CaptureArchiveCodec();
    const path = await target('oversized.apinspect');
    const snapshot = Buffer.from('{}');
    const manifestBytes = Buffer.from(JSON.stringify({
      format: 'agentperf-inspector-capture', archiveVersion: 1, ...metadata,
      entries: [{
        path: CAPTURE_ARCHIVE_PATHS.snapshot,
        size: 108 * 1024 * 1024 + 1,
        sha256: createHash('sha256').update(snapshot).digest('hex'),
        required: true,
      }],
    }));
    await writeFile(path, storedZip([
      { name: CAPTURE_ARCHIVE_PATHS.manifest, bytes: manifestBytes },
      { name: CAPTURE_ARCHIVE_PATHS.snapshot, bytes: snapshot },
    ]));
    await expect(codec.read(path)).rejects.toThrow(/size is out of bounds/);
  });

  it('omits an oversized optional raw pair without writing an incomplete archive', async () => {
    const codec = new CaptureArchiveCodec();
    const path = await target('raw-too-large.apinspect');
    const result = await codec.write(path, metadata, {
      snapshotJson: '{}',
      rawArtifacts: { zip: Buffer.from('PK'), text: 'x'.repeat(8 * 1024 * 1024 + 1) },
    });
    expect(result.rawArtifactsIncluded).toBe(false);
    expect((await codec.read(path)).payload.rawArtifacts).toBeUndefined();
  });

  it('preserves an existing target when replacement fails and removes the temporary file', async () => {
    const path = await target('existing.apinspect');
    await writeFile(path, 'existing');
    const codec = new CaptureArchiveCodec({}, async () => { throw new Error('move failed'); });
    await expect(codec.write(path, metadata, { snapshotJson: '{}' })).rejects.toThrow('move failed');
    expect(await readFile(path, 'utf8')).toBe('existing');
  });
});
