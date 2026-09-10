import { describe, expect, it } from 'vitest';
import { SimpleperfProfileNormalizer, resolveExecutionType } from './normalizer.js';
import { normalizeSimpleperfReport, requireNormalizedProfile } from './report.js';
import {
  fileEntry,
  fileRecord,
  lostEntry,
  lostRecord,
  metaInfoEntry,
  metaInfoRecord,
  sample,
  sampleRecord,
  stream,
  threadEntry,
  threadRecord,
} from './report-builder.js';
import type { NormalizedSample } from './model.js';

function sampleOf(record: ReturnType<SimpleperfProfileNormalizer['normalize']>): NormalizedSample {
  if (record.kind !== 'SAMPLE') throw new Error('expected a SAMPLE record');
  return record.value;
}

describe('SimpleperfProfileNormalizer', () => {
  it('resolves files, symbols, threads, and event types for a sample', () => {
    const normalizer = new SimpleperfProfileNormalizer();
    normalizer.normalize({
      kind: 'META_INFO',
      metaInfo: { eventTypes: ['cpu-cycles', 'cpu-clock'], traceOffCpu: false },
    });
    normalizer.normalize({
      kind: 'FILE',
      file: { id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy', 'malloc'], mangledSymbols: [] },
    });
    normalizer.normalize({ kind: 'THREAD', thread: { threadId: 42, processId: 7, threadName: 'RenderThread' } });

    const sample = sampleOf(
      normalizer.normalize({
        kind: 'SAMPLE',
        sample: {
          time: 1234567890n,
          threadId: 42,
          eventCount: 1000n,
          eventTypeId: 1,
          callchain: [
            { vaddrInFile: 0x1000n, fileId: 0, symbolId: 1, executionType: 0 },
            { vaddrInFile: 0x2000n, fileId: 0, symbolId: -1, executionType: 1 },
          ],
        },
      }),
    );

    expect(sample.threadName).toBe('RenderThread');
    expect(sample.processId).toBe(7);
    expect(sample.eventType).toBe('cpu-clock');
    expect(sample.timestampNanos).toBe(1234567890n);
    expect(sample.frames[0]?.symbolName).toBe('malloc');
    expect(sample.frames[0]?.executionType).toBe('NATIVE');
    // symbol_id -1 means simpleperf found no function, not index zero.
    expect(sample.frames[1]?.symbolName).toBe('<unknown-symbol>');
    expect(sample.frames[1]?.executionType).toBe('INTERPRETED_JVM');
    expect(sample.unwindError).toBeUndefined();
  });

  it('names unknown files, threads, and events instead of dropping them', () => {
    const normalizer = new SimpleperfProfileNormalizer();
    const sample = sampleOf(
      normalizer.normalize({
        kind: 'SAMPLE',
        sample: {
          time: 1n,
          threadId: 99,
          eventCount: 1n,
          eventTypeId: 3,
          callchain: [{ vaddrInFile: 16n, fileId: 5, symbolId: 0, executionType: 2 }],
        },
      }),
    );
    expect(sample.threadName).toBe('<unknown-thread:99>');
    expect(sample.processId).toBe(0);
    expect(sample.eventType).toBe('<unknown-event:3>');
    expect(sample.frames[0]?.filePath).toBe('<unknown-file:5>');
    expect(sample.frames[0]?.executionType).toBe('UNKNOWN');
  });

  it('classifies kernel frames and reports unwind errors', () => {
    const normalizer = new SimpleperfProfileNormalizer();
    normalizer.normalize({
      kind: 'FILE',
      file: { id: 0, path: '[kernel.kallsyms]', symbols: ['schedule'], mangledSymbols: [] },
    });
    const sample = sampleOf(
      normalizer.normalize({
        kind: 'SAMPLE',
        sample: {
          time: 2n,
          threadId: 1,
          eventCount: 1n,
          eventTypeId: 0,
          callchain: [{ vaddrInFile: 32n, fileId: 0, symbolId: 0, executionType: 0 }],
          unwindingResult: { rawErrorCode: 6, errorAddr: 4096n, errorCode: 6 },
        },
      }),
    );
    // The kernel mapping wins over the reported NATIVE execution type.
    expect(sample.frames[0]?.executionType).toBe('KERNEL');
    expect(sample.unwindError).toEqual({ code: 'ERROR_MAX_FRAME_EXCEEDED', rawCode: 6, address: 4096n });
  });

  it('maps execution types, including an out-of-range enum value', () => {
    expect(resolveExecutionType('/data/app/libfoo.so', 0)).toBe('NATIVE');
    expect(resolveExecutionType('/data/app/libfoo.so', 1)).toBe('INTERPRETED_JVM');
    expect(resolveExecutionType('/data/app/libfoo.so', 2)).toBe('JIT_JVM');
    expect(resolveExecutionType('/data/app/libfoo.so', 3)).toBe('ART');
    expect(resolveExecutionType('/data/app/libfoo.so', 99)).toBe('NATIVE');
    expect(resolveExecutionType('/apex/com.android.art/lib64/libart.so', 3)).toBe('ART');
  });

  it('normalizes lost samples, context switches, and metadata', () => {
    const normalizer = new SimpleperfProfileNormalizer();
    expect(normalizer.normalize({ kind: 'LOST', lost: { sampleCount: 10n, lostCount: 2n } })).toEqual({
      kind: 'LOST',
      sampleCount: 10n,
      lostCount: 2n,
    });
    expect(
      normalizer.normalize({
        kind: 'CONTEXT_SWITCH',
        contextSwitch: { switchOn: true, time: 77n, threadId: 3 },
      }),
    ).toEqual({ kind: 'CONTEXT_SWITCH', threadId: 3, timestampNanos: 77n, switchedOnCpu: true });
    expect(normalizer.normalize({ kind: 'NOT_SET' })).toEqual({ kind: 'UNKNOWN' });

    const metadata = normalizer.normalize({
      kind: 'META_INFO',
      metaInfo: {
        eventTypes: ['cpu-cycles'],
        appPackageName: 'com.example.app',
        appType: 'profileable',
        androidSdkVersion: '34',
        androidBuildType: 'user',
        traceOffCpu: true,
      },
    });
    expect(metadata).toEqual({
      kind: 'METADATA',
      value: {
        eventTypes: ['cpu-cycles'],
        appPackageName: 'com.example.app',
        appType: 'profileable',
        androidSdkVersion: '34',
        androidBuildType: 'user',
        traceOffCpu: true,
      },
    });
    // Absent optional fields stay absent rather than becoming empty strings.
    const sparse = normalizer.normalize({ kind: 'META_INFO', metaInfo: { eventTypes: [], traceOffCpu: false } });
    expect(sparse).toEqual({ kind: 'METADATA', value: { eventTypes: [], traceOffCpu: false } });
  });
});

describe('normalizeSimpleperfReport', () => {
  it('collects samples, metadata, and the lost count', () => {
    const bytes = stream([
      metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'], appPackageName: 'com.example.app' })),
      fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy'] })),
      threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'main' })),
      sampleRecord(
        sample({ time: 10n, threadId: 42, eventCount: 1000n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 0 }] }),
      ),
      sampleRecord(
        sample({ time: 20n, threadId: 42, eventCount: 2000n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 0 }] }),
      ),
      lostEntry(lostRecord(100n, 3n)),
    ]);
    const result = normalizeSimpleperfReport(bytes);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.summary.sampleCount).toBe(2n);
    expect(result.value.summary.lostCount).toBe(3n);
    expect(result.value.summary.recordCount).toBe(6n);
    expect(result.value.summary.metadata?.appPackageName).toBe('com.example.app');
    expect(result.value.samples.map((entry) => entry.timestampNanos)).toEqual([10n, 20n]);
    expect(result.value.metadata).toBe(result.value.summary.metadata);
  });

  it('propagates reader failures and rejects an empty report', () => {
    const metadataOnly = normalizeSimpleperfReport(
      stream([metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'] }))]),
    );
    expect(metadataOnly.ok).toBe(true);

    const noSamples = requireNormalizedProfile(stream([metaInfoEntry(metaInfoRecord({ eventTypes: [] }))]));
    expect(noSamples.ok).toBe(false);
    if (!noSamples.ok) expect(noSamples.error.code).toBe('SIMPLEPERF_NO_SAMPLES');

    const malformed = normalizeSimpleperfReport(stream([], { magic: 'NOTPERF!!' }));
    expect(malformed.ok).toBe(false);
    if (!malformed.ok) expect(malformed.error.code).toBe('SIMPLEPERF_MAGIC_INVALID');
  });
});
