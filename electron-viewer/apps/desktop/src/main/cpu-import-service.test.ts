import { describe, expect, it } from 'vitest';
import { gzipSync } from 'node:zlib';
import { formatOfFile, importCpuProfile } from './cpu-import-service.js';
import { fileEntry, fileRecord, metaInfoEntry, metaInfoRecord, sample, sampleRecord, stream, threadEntry, threadRecord } from '@aps/simpleperf-profiler/testing';

function reportBytes(): Uint8Array {
  return stream([
    sampleRecord(sample({ time: 10n, threadId: 42, eventCount: 5n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 0 }] })),
    metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'] })),
    fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy'] })),
    threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'RenderThread' })),
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

describe('formatOfFile', () => {
  it('maps extensions to formats and rejects the rest', () => {
    expect(formatOfFile('perf.data').ok).toBe(true);
    expect(formatOfFile('report.pb').ok).toBe(true);
    expect(formatOfFile('profile.json.gz').ok).toBe(true);
    const unknown = formatOfFile('README.md');
    expect(unknown.ok).toBe(false);
    if (!unknown.ok) expect(unknown.error.code).toBe('CPU_IMPORT_FORMAT_UNKNOWN');
  });
});

describe('importCpuProfile', () => {
  it('imports a protobuf report into a call stack table', () => {
    const imported = importCpuProfile({ fileName: 'simpleperf.protobuf', bytes: reportBytes() });
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;
    expect(imported.value.format).toBe('SIMPLEPERF_PROTOBUF');
    expect(imported.value.samples).toHaveLength(1);
    expect(imported.value.table.stacks).toHaveLength(1);
    expect(imported.value.table.stacks[0]?.threadKey).toBe('RenderThread (tid 42)');
    expect(imported.value.threadKeys).toEqual(['RenderThread (tid 42)']);
    expect(imported.value.metadata?.eventTypes).toEqual(['cpu-cycles']);
  });

  it('imports a decompressed gecko profile', () => {
    const imported = importCpuProfile({ fileName: 'aosp-gecko-profile.json.gz', text: geckoText() });
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;
    expect(imported.value.format).toBe('GECKO_PROFILE_JSON_GZIP');
    expect(imported.value.table.stacks[0]?.frameIdsRootToLeaf.length).toBe(1);
    expect(imported.value.threadKeys).toEqual(['Main (tid 8)']);
  });

  it('rejects an unknown file, missing content, and an empty profile', () => {
    const unknown = importCpuProfile({ fileName: 'notes.txt', bytes: reportBytes() });
    expect(unknown.ok).toBe(false);
    if (!unknown.ok) expect(unknown.error.code).toBe('CPU_IMPORT_FORMAT_UNKNOWN');

    const empty = importCpuProfile({ fileName: 'report.pb' });
    expect(empty.ok).toBe(false);
    if (!empty.ok) expect(empty.error.code).toBe('OFFLINE_INPUT_EMPTY');

    const noSamples = importCpuProfile({
      fileName: 'empty.pb',
      bytes: stream([metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'] }))]),
    });
    expect(noSamples.ok).toBe(false);
    if (!noSamples.ok) expect(noSamples.error.code).toBe('CPU_IMPORT_NO_SAMPLES');
  });

  it('reads a gzipped gecko profile through the node helper', async () => {
    const { gunzipProfileText } = await import('@aps/simpleperf-profiler/node');
    const gzipped = new Uint8Array(gzipSync(Buffer.from(geckoText(), 'utf8')));
    const text = gunzipProfileText(gzipped);
    expect(text.ok).toBe(true);
    if (!text.ok) return;
    const imported = importCpuProfile({ fileName: 'profile.json.gz', text: text.value });
    expect(imported.ok).toBe(true);

    const notGzip = gunzipProfileText(new Uint8Array([1, 2, 3]));
    expect(notGzip.ok).toBe(false);
    if (!notGzip.ok) expect(notGzip.error.code).toBe('GECKO_PROFILE_INVALID');
  });
});
