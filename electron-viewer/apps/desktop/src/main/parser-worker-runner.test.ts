import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { mkdtemp, rm } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { build } from 'electron-vite';
import { parseArtTrace, toCallStackTable } from '@aps/art-trace';
import { streamingTrace } from '../../../../packages/art-trace/src/trace-builder.js';
import { createMemorySession, parseHprof } from '@aps/memory-profiler';
import { importOfflineProfile, normalizeSimpleperfReport, samplesToCallStackTable } from '@aps/simpleperf-profiler';
import {
  fileEntry,
  fileRecord,
  metaInfoEntry,
  metaInfoRecord,
  sample,
  sampleRecord,
  stream,
  threadEntry,
  threadRecord,
} from '../../../../packages/simpleperf-profiler/src/report-builder.js';
import type {
  ArtTraceWorkerResult,
  HprofSessionMetadata,
  HprofWorkerValue,
  OfflineCpuImportWorkerInput,
  OfflineCpuWorkerResult,
  SimpleperfWorkerResult,
} from './parser-worker-protocol.js';

interface BuiltBridge {
  readonly parseHeapDumpInWorker: (bytes: Uint8Array, session: HprofSessionMetadata) => Promise<HprofWorkerValue>;
  readonly parseMethodTraceInWorker: (bytes: Uint8Array) => Promise<ArtTraceWorkerResult>;
  readonly parseSimpleperfReportInWorker: (bytes: Uint8Array) => Promise<SimpleperfWorkerResult>;
  readonly importOfflineCpuProfileInWorker: (input: OfflineCpuImportWorkerInput) => Promise<OfflineCpuWorkerResult>;
}

const TEST_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const DESKTOP_ROOT = resolve(TEST_DIRECTORY, '..', '..');
const REPO_ROOT = resolve(DESKTOP_ROOT, '..', '..', '..');
const HPROF_FIXTURE = join(
  REPO_ROOT,
  'desktop-viewer',
  'memory-profiler',
  'parser-hprof',
  'src',
  'test',
  'resources',
  'hprof',
  'android-converted-sample.hprof',
);

let buildDirectory = '';
let bridge: BuiltBridge;

beforeAll(async () => {
  buildDirectory = await mkdtemp(join(DESKTOP_ROOT, '.aps-parser-worker-'));
  const entry = join(buildDirectory, 'bridge-entry.ts');
  const config = join(buildDirectory, 'electron.vite.config.mjs');
  const output = join(buildDirectory, 'out');
  writeFileSync(
    entry,
    `export { parseHeapDumpInWorker, parseMethodTraceInWorker, parseSimpleperfReportInWorker, importOfflineCpuProfileInWorker } from ${JSON.stringify(join(TEST_DIRECTORY, 'parser-worker-runner.ts'))};\n`,
  );
  writeFileSync(
    config,
    `import { defineConfig } from 'electron-vite';\nexport default defineConfig({ main: { build: { outDir: ${JSON.stringify(output)}, emptyOutDir: true, rollupOptions: { input: { bridge: ${JSON.stringify(entry)} }, output: { format: 'cjs' } } }, externalizeDeps: false } });\n`,
  );
  await build({ root: DESKTOP_ROOT, configFile: config, mode: 'test', logLevel: 'silent' });
  const emittedFiles = readdirSync(buildDirectory, { recursive: true }).map(String);
  const bridgeFile = emittedFiles.find((file) => file.endsWith('bridge.js'));
  if (bridgeFile === undefined) throw new Error(`electron-vite did not emit bridge.js: ${emittedFiles.join(', ')}`);
  bridge = (await import(pathToFileURL(join(buildDirectory, bridgeFile)).href)) as BuiltBridge;
}, 60_000);

afterAll(async () => {
  if (buildDirectory.length > 0) await rm(buildDirectory, { recursive: true, force: true });
});

function hprofBytes(): Uint8Array {
  return Uint8Array.from(readFileSync(HPROF_FIXTURE));
}

function simpleperfBytes(): Uint8Array {
  return stream([
    metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'], appPackageName: 'com.example.worker' })),
    fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy'] })),
    threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'RenderThread' })),
    sampleRecord(
      sample({
        time: 10n,
        threadId: 42,
        eventCount: 5n,
        eventTypeId: 0,
        callchain: [{ fileId: 0, symbolId: 0 }],
      }),
    ),
  ]);
}

function geckoText(): string {
  return JSON.stringify({
    threads: [
      {
        pid: 7,
        tid: 8,
        name: 'Main',
        stringTable: ['work (in /system/lib64/libwork.so)'],
        frameTable: { schema: { location: 0 }, data: [[0]] },
        stackTable: { schema: { prefix: 0, frame: 1 }, data: [[null, 0]] },
        samples: { schema: { stack: 0, time: 1 }, data: [[0, 2]] },
      },
    ],
  });
}

function tableObservation(table: { readonly framesById: ReadonlyMap<bigint, unknown>; readonly stacks: readonly unknown[] }) {
  return { frames: [...table.framesById.entries()], stacks: table.stacks };
}

describe('electron-vite parser worker bridge', () => {
  it('parses HPROF and returns both the parsed heap and derived session', async () => {
    const metadata: HprofSessionMetadata = {
      id: 'worker-hprof',
      capturedAtEpochMillis: 1_726_000_000_000,
      deviceSerial: 'emulator-5554',
      packageName: 'com.example.worker',
      deep: false,
    };
    const directParsed = parseHprof(hprofBytes());
    const directSession = createMemorySession(directParsed, metadata);

    const workerBytes = hprofBytes();
    const result = await bridge.parseHeapDumpInWorker(workerBytes, metadata);

    expect(workerBytes.byteLength).toBe(0);
    expect(result.parsed.header).toEqual(directParsed.header);
    expect([...result.parsed.classes.entries()]).toEqual([...directParsed.classes.entries()]);
    expect(result.parsed.instances).toEqual(directParsed.instances);
    expect(result.session).toEqual(directSession);
  });

  it('parses and projects an ART trace in a fresh worker', async () => {
    const direct = parseArtTrace(streamingTrace());
    expect(direct.ok).toBe(true);
    if (!direct.ok) return;

    const result = await bridge.parseMethodTraceInWorker(streamingTrace());
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.analysis).toEqual(direct.value);
    expect(tableObservation(result.value.table)).toEqual(tableObservation(toCallStackTable(direct.value)));
  });

  it('normalizes and projects a retained simpleperf protobuf report', async () => {
    const direct = normalizeSimpleperfReport(simpleperfBytes());
    expect(direct.ok).toBe(true);
    if (!direct.ok) return;

    const result = await bridge.parseSimpleperfReportInWorker(simpleperfBytes());
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.profile).toEqual(direct.value);
    expect(tableObservation(result.value.table)).toEqual(tableObservation(samplesToCallStackTable(direct.value.samples)));
  });

  it('decompresses and imports a Gecko archive in the worker', async () => {
    const text = geckoText();
    const direct = importOfflineProfile({ format: 'GECKO_PROFILE_JSON_GZIP', text });
    expect(direct.ok).toBe(true);
    if (!direct.ok) return;

    const compressed = new Uint8Array(gzipSync(Buffer.from(text, 'utf8')));
    const result = await bridge.importOfflineCpuProfileInWorker({
      format: 'GECKO_PROFILE_JSON_GZIP',
      bytes: compressed,
    });
    expect(compressed.byteLength).toBe(0);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.imported).toEqual(direct.value);
    expect(tableObservation(result.value.table)).toEqual(tableObservation(samplesToCallStackTable(direct.value.samples)));
  });

  it('preserves typed parser failures and rejects thrown HPROF parse errors', async () => {
    const art = await bridge.parseMethodTraceInWorker(new Uint8Array([1, 2, 3, 4, 5, 6]));
    expect(art.ok).toBe(false);
    if (!art.ok) expect(art.error.code).toBe('ART_TRACE_MAGIC_INVALID');

    const simpleperf = await bridge.parseSimpleperfReportInWorker(new Uint8Array([1, 2, 3]));
    expect(simpleperf.ok).toBe(false);
    if (!simpleperf.ok) expect(simpleperf.error.code).toBe('SIMPLEPERF_MAGIC_INVALID');

    await expect(
      bridge.parseHeapDumpInWorker(new Uint8Array([1, 2, 3]), {
        id: 'bad-hprof',
        capturedAtEpochMillis: 0,
        deep: false,
      }),
    ).rejects.toThrow();
  });
});
