import { describe, expect, it } from 'vitest';
import { executionTypeOf, parseLocation, readGeckoProfileText } from './gecko.js';
import { importOfflineProfile, normalizeSimpleperfReportWithLookups, offlineFormatOf } from './offline.js';
import { normalizeSimpleperfReport } from './report.js';
import { fileEntry, fileRecord, metaInfoEntry, metaInfoRecord, sample, sampleRecord, stream, threadEntry, threadRecord } from './report-builder.js';

describe('offline format detection', () => {
  it('recognises the three formats by file name', () => {
    expect(offlineFormatOf('perf.data')).toBe('PERF_DATA');
    expect(offlineFormatOf('/tmp/capture.PERF.DATA')).toBe('PERF_DATA');
    expect(offlineFormatOf('simpleperf.protobuf')).toBe('SIMPLEPERF_PROTOBUF');
    expect(offlineFormatOf('report.pb')).toBe('SIMPLEPERF_PROTOBUF');
    expect(offlineFormatOf('profile.simpleperf')).toBe('SIMPLEPERF_PROTOBUF');
    expect(offlineFormatOf('aosp-gecko-profile.json.gz')).toBe('GECKO_PROFILE_JSON_GZIP');
    expect(offlineFormatOf('notes.txt')).toBeUndefined();
  });
});

describe('gecko profile reader', () => {
  const profile = {
    meta: { interval: 1 },
    threads: [
      {
        pid: 100,
        tid: 101,
        name: 'GeckoMain',
        stringTable: ['main (in /system/lib64/libapp.so)', 'render (in /system/lib64/libgui.so)', '', 'jit (in [JIT app cache])'],
        frameTable: { schema: { location: 0, category: 1 }, data: [[0, 2], [1, 0], [3, 0]] },
        stackTable: { schema: { prefix: 0, frame: 1 }, data: [[null, 0], [0, 1], [1, 2]] },
        samples: { schema: { stack: 0, time: 1 }, data: [[2, 1.5], [0, 2.25], [null, 3]] },
      },
    ],
  };

  it('converts stack chains into leaf first frames', () => {
    const result = readGeckoProfileText(JSON.stringify(profile));
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.threadCount).toBe(1);
    expect(result.value.samples).toHaveLength(3);

    // Stack 2 is prefix 1 -> prefix 0, so the frames are leaf first.
    const deepest = result.value.samples[0];
    expect(deepest?.frames.map((frame) => frame.symbolName)).toEqual(['jit', 'render', 'main']);
    expect(deepest?.frames.map((frame) => frame.executionType)).toEqual(['JIT_JVM', 'NATIVE', 'NATIVE']);
    expect(deepest?.timestampNanos).toBe(1_500_000n);
    expect(deepest?.threadName).toBe('GeckoMain');
    expect(deepest?.eventCount).toBe(1n);

    // A sample without a stack keeps an empty chain.
    expect(result.value.samples[2]?.frames).toEqual([]);
    expect(result.value.samples[2]?.timestampNanos).toBe(3_000_000n);
  });

  it('rejects malformed profiles with a stable code', () => {
    const noThreads = readGeckoProfileText('{"meta":{}}');
    expect(noThreads.ok).toBe(false);
    if (!noThreads.ok) {
      expect(noThreads.error.code).toBe('GECKO_PROFILE_INVALID');
      expect(noThreads.error.message).toContain('threads array');
    }

    const notJson = readGeckoProfileText('not json');
    expect(notJson.ok).toBe(false);
    if (!notJson.ok) expect(notJson.error.code).toBe('GECKO_PROFILE_INVALID');

    const forwardPrefix = {
      threads: [
        {
          pid: 1,
          tid: 1,
          stringTable: ['a (in b.so)'],
          frameTable: { schema: { location: 0 }, data: [[0]] },
          stackTable: { schema: { prefix: 0, frame: 1 }, data: [[1, 0], [0, 0]] },
          samples: { schema: { stack: 0, time: 1 }, data: [[0, 1]] },
        },
      ],
    };
    const forward = readGeckoProfileText(JSON.stringify(forwardPrefix));
    expect(forward.ok).toBe(false);
    if (!forward.ok) expect(forward.error.message).toContain('Invalid stack prefix');

    const badStack = {
      threads: [
        {
          pid: 1,
          tid: 1,
          stringTable: ['a (in b.so)'],
          frameTable: { schema: { location: 0 }, data: [[0]] },
          stackTable: { schema: { prefix: 0, frame: 1 }, data: [[null, 0]] },
          samples: { schema: { stack: 0, time: 1 }, data: [[5, 1]] },
        },
      ],
    };
    const stackOutOfRange = readGeckoProfileText(JSON.stringify(badStack));
    expect(stackOutOfRange.ok).toBe(false);
    if (!stackOutOfRange.ok) expect(stackOutOfRange.error.message).toContain('Invalid stack 5');
  });

  it('parses locations and classifies execution types like the reference', () => {
    expect(parseLocation('main (in /system/lib/libc.so)')).toEqual({
      symbolName: 'main',
      filePath: '/system/lib/libc.so',
    });
    expect(parseLocation('orphan')).toEqual({ symbolName: 'orphan', filePath: '<gecko-profile>' });
    expect(parseLocation(' (in /lib.so)')).toEqual({ symbolName: ' (in /lib.so)', filePath: '<gecko-profile>' });
    expect(parseLocation('main (in )')).toEqual({ symbolName: 'main', filePath: '<unknown-file>' });

    expect(executionTypeOf('[kernel.kallsyms]', undefined)).toBe('KERNEL');
    expect(executionTypeOf('/lib/modules/foo.ko', undefined)).toBe('KERNEL');
    expect(executionTypeOf('/data/app/x.vdex', undefined)).toBe('INTERPRETED_JVM');
    expect(executionTypeOf('/data/app/x.oat', undefined)).toBe('ART');
    expect(executionTypeOf('[JIT app cache]', undefined)).toBe('JIT_JVM');
    expect(executionTypeOf('/system/lib64/libc.so', undefined)).toBe('NATIVE');
    expect(executionTypeOf('libart.so', 1)).toBe('NATIVE');
    expect(executionTypeOf('unknown', 1)).toBe('KERNEL');
    expect(executionTypeOf('unknown', undefined)).toBe('UNKNOWN');
  });
});

describe('two-pass protobuf indexing', () => {
  /** The sample comes first, so its file and thread records are not known yet. */
  function sampleBeforeLookups(): Uint8Array {
    return stream([
      sampleRecord(
        sample({ time: 10n, threadId: 42, eventCount: 5n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 1 }] }),
      ),
      metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'] })),
      fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy', 'malloc'] })),
      threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'RenderThread' })),
    ]);
  }

  it('resolves samples that precede the records they reference', () => {
    const bytes = sampleBeforeLookups();

    // A single pass sees the sample first and cannot resolve it.
    const single = normalizeSimpleperfReport(bytes);
    expect(single.ok).toBe(true);
    if (single.ok) {
      expect(single.value.samples[0]?.threadName).toBe('<unknown-thread:42>');
      expect(single.value.samples[0]?.frames[0]?.symbolName).toBe('<unknown-symbol>');
      expect(single.value.samples[0]?.eventType).toBe('<unknown-event:0>');
    }

    const twoPass = normalizeSimpleperfReportWithLookups(bytes);
    expect(twoPass.ok).toBe(true);
    if (!twoPass.ok) return;
    expect(twoPass.value.samples).toHaveLength(1);
    expect(twoPass.value.samples[0]?.threadName).toBe('RenderThread');
    expect(twoPass.value.samples[0]?.processId).toBe(7);
    expect(twoPass.value.samples[0]?.eventType).toBe('cpu-cycles');
    expect(twoPass.value.samples[0]?.frames[0]?.symbolName).toBe('malloc');
    expect(twoPass.value.samples[0]?.frames[0]?.filePath).toBe('/system/lib64/libc.so');
  });

  it('reports lost samples and rejects malformed input', () => {
    const bytes = stream([
      metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'] })),
      sampleRecord(sample({ time: 1n, threadId: 1, eventCount: 2n, eventTypeId: 0, callchain: [] })),
    ]);
    const normalized = normalizeSimpleperfReportWithLookups(bytes);
    expect(normalized.ok).toBe(true);

    const malformed = normalizeSimpleperfReportWithLookups(new Uint8Array([1, 2, 3]));
    expect(malformed.ok).toBe(false);
    if (!malformed.ok) expect(malformed.error.code).toBe('SIMPLEPERF_MAGIC_INVALID');
  });
});

describe('importOfflineProfile', () => {
  it('imports a converted protobuf report', () => {
    const bytes = sampleBeforeLookupsForImport();
    const imported = importOfflineProfile({ format: 'SIMPLEPERF_PROTOBUF', bytes });
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;
    expect(imported.value.format).toBe('SIMPLEPERF_PROTOBUF');
    expect(imported.value.samples).toHaveLength(1);
    expect(imported.value.threadCount).toBe(1);
    expect(imported.value.lostCount).toBe(0n);
  });

  it('imports a gecko profile from text', () => {
    const text = JSON.stringify({
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
    const imported = importOfflineProfile({ format: 'GECKO_PROFILE_JSON_GZIP', text });
    expect(imported.ok).toBe(true);
    if (!imported.ok) return;
    expect(imported.value.samples).toHaveLength(1);
    expect(imported.value.threadCount).toBe(1);
    expect(imported.value.samples[0]?.frames[0]?.symbolName).toBe('work');
  });

  it('refuses perf.data and missing input', () => {
    const perfData = importOfflineProfile({ format: 'PERF_DATA' });
    expect(perfData.ok).toBe(false);
    if (!perfData.ok) expect(perfData.error.code).toBe('OFFLINE_PERF_DATA_NEEDS_CONVERSION');

    const emptyGecko = importOfflineProfile({ format: 'GECKO_PROFILE_JSON_GZIP' });
    expect(emptyGecko.ok).toBe(false);
    if (!emptyGecko.ok) expect(emptyGecko.error.code).toBe('OFFLINE_INPUT_EMPTY');

    const emptyProtobuf = importOfflineProfile({ format: 'SIMPLEPERF_PROTOBUF' });
    expect(emptyProtobuf.ok).toBe(false);
    if (!emptyProtobuf.ok) expect(emptyProtobuf.error.code).toBe('OFFLINE_INPUT_EMPTY');
  });
});

function sampleBeforeLookupsForImport(): Uint8Array {
  return stream([
    sampleRecord(
      sample({ time: 10n, threadId: 42, eventCount: 5n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 0 }] }),
    ),
    metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'] })),
    fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy'] })),
    threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'RenderThread' })),
  ]);
}
