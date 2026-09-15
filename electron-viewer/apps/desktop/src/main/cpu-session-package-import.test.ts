import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { SessionPackageCodec } from './session-package-codec.js';
import { importCpuSessionPackage, isCpuSessionPackageFileName } from './cpu-session-package-import.js';

const directories: string[] = [];

function captureArtifact(perfData: Buffer, overrides: Record<string, unknown> = {}): Buffer {
  return Buffer.from(JSON.stringify({
    contractVersion: 1,
    id: 'captured-cpu',
    kind: 'cpu.simpleperf',
    location: '/captured/perf.data',
    sha256: createHash('sha256').update(perfData).digest('hex'),
    provenance: {
      producer: { producerType: 'known', name: 'Android simpleperf' },
      acquisition: { kind: 'CAPTURE', application: 'Android Performance Studio', performedAtEpochMillis: 0 },
    },
    requestedCapabilities: ['cpu.samples'],
    availableCapabilities: ['cpu.samples'],
    completeness: 'COMPLETE',
    ...overrides,
  }));
}

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-cpu-package-'));
  directories.push(directory);
  return directory;
}

async function writeSource(root: string, files: ReadonlyMap<string, Buffer>): Promise<void> {
  for (const [path, bytes] of files) {
    const target = join(root, ...path.split('/'));
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, bytes);
  }
}

async function archiveFor(files: ReadonlyMap<string, Buffer>): Promise<{ archive: string; destinationRoot: string }> {
  const root = await temporaryDirectory();
  const source = join(root, 'source');
  await mkdir(source);
  await writeSource(source, files);
  const archive = join(root, 'captured.apsession.zip');
  await new SessionPackageCodec().export(source, archive);
  return { archive, destinationRoot: join(root, 'imported') };
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe('importCpuSessionPackage', () => {
  it('matches Kotlin session-package filename classification', () => {
    expect(isCpuSessionPackageFileName('captured.apsession.zip')).toBe(true);
    expect(isCpuSessionPackageFileName('captured.zip')).toBe(true);
    expect(isCpuSessionPackageFileName('CAPTURED.APSESSION.ZIP')).toBe(true);
    expect(isCpuSessionPackageFileName('perf.data')).toBe(false);
  });

  it('opens Kotlin-shaped perf.data, symbols, mapping, and unknown evidence after package verification', async () => {
    const perfData = Buffer.from([1, 2, 3, 4]);
    const { archive, destinationRoot } = await archiveFor(new Map([
      ['perf.data', perfData],
      ['simpleperf.protobuf', Buffer.from([8, 9])],
      ['symbols/libapp.so', Buffer.from('symbols')],
      ['mapping.txt', Buffer.from('mapping')],
      ['opaque/diagnostic.bin', Buffer.from([0, 255])],
    ]));

    const result = await importCpuSessionPackage({ archive, destinationRoot });

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.verifiedFiles).toBe(5);
    expect(await readFile(result.value.perfData)).toEqual(perfData);
    expect(result.value.symbolDirectory).toBe(join(result.value.sessionDirectory, 'symbols'));
    expect(result.value.proguardMapping).toBe(join(result.value.sessionDirectory, 'mapping.txt'));
    expect(await readFile(join(result.value.sessionDirectory, 'opaque', 'diagnostic.bin'))).toEqual(Buffer.from([0, 255]));
  });

  it('matches Kotlin capture-artifact hash enforcement before a host conversion starts', async () => {
    const perfData = Buffer.from([1, 2, 3]);
    const good = await archiveFor(new Map([
      ['perf.data', perfData],
      ['capture-artifact.json', captureArtifact(perfData)],
    ]));
    const accepted = await importCpuSessionPackage(good);
    expect(accepted.ok).toBe(true);

    const bad = await archiveFor(new Map([
      ['perf.data', perfData],
      ['capture-artifact.json', captureArtifact(perfData, { sha256: '0'.repeat(64) })],
    ]));
    const rejected = await importCpuSessionPackage(bad);
    expect(rejected.ok).toBe(false);
    if (!rejected.ok) expect(rejected.error.code).toBe('CAPTURED_SESSION_HASH_MISMATCH');
  });

  it('rejects packages without perf.data or a Kotlin-valid Capture Artifact shape', async () => {
    const missing = await archiveFor(new Map([['simpleperf.protobuf', Buffer.from([1])]]));
    const missingResult = await importCpuSessionPackage(missing);
    expect(missingResult.ok).toBe(false);
    if (!missingResult.ok) expect(missingResult.error.code).toBe('CAPTURED_SESSION_PERF_DATA_NOT_FOUND');

    const perfData = Buffer.from([1]);
    const structurallyInvalid = await archiveFor(new Map([
      ['perf.data', perfData],
      ['capture-artifact.json', Buffer.from(JSON.stringify({ sha256: createHash('sha256').update(perfData).digest('hex') }))],
    ]));
    const structurallyInvalidResult = await importCpuSessionPackage(structurallyInvalid);
    expect(structurallyInvalidResult.ok).toBe(false);
    if (!structurallyInvalidResult.ok) expect(structurallyInvalidResult.error.code).toBe('CAPTURED_SESSION_HASH_MISMATCH');

    const malformed = await archiveFor(new Map([
      ['perf.data', perfData],
      ['capture-artifact.json', Buffer.from('{"sha256":false}')],
    ]));
    const malformedResult = await importCpuSessionPackage(malformed);
    expect(malformedResult.ok).toBe(false);
    if (!malformedResult.ok) expect(malformedResult.error.code).toBe('CAPTURED_SESSION_HASH_MISMATCH');
  });
});
