import { describe, expect, it } from 'vitest';
import type { NormalizedSample, ProfileExecutionType } from './model.js';
import { samplesToCallStackTable } from './analysis/table.js';
import { DEFAULT_CALL_STACK_QUERY } from '@aps/profile-analysis';
import {
  buildFlameGraphPayload,
  createCpuProfileSession,
  directionOf,
  metadataRecord,
  samplingParametersRecord,
  topFunctions,
  transformFromRequest,
  type CpuProfileSession,
} from './session.js';
import { samplingParameters } from './toolchain.js';
import { normalizeSimpleperfReport } from './report.js';
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
} from './report-builder.js';
import type { SimpleperfCaptureResult } from './capture.js';

function profile() {
  const bytes = stream([
    metaInfoEntry(
      metaInfoRecord({
        eventTypes: ['cpu-cycles'],
        appPackageName: 'com.example.app',
        appType: 'profileable',
        androidSdkVersion: '34',
        androidBuildType: 'user',
      }),
    ),
    fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy', 'malloc'] })),
    threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'main' })),
    sampleRecord(
      sample({
        time: 10n,
        threadId: 42,
        eventCount: 6n,
        eventTypeId: 0,
        callchain: [
          { fileId: 0, symbolId: 1 },
          { fileId: 0, symbolId: 0 },
        ],
      }),
    ),
    sampleRecord(
      // memcpy alone, so the tree has one root with one child.
      sample({ time: 20n, threadId: 42, eventCount: 4n, eventTypeId: 0, callchain: [{ fileId: 0, symbolId: 0 }] }),
    ),
  ]);
  const parsed = normalizeSimpleperfReport(bytes);
  if (!parsed.ok) throw new Error('fixture failed to parse');
  return parsed.value;
}

function captureResult(): SimpleperfCaptureResult {
  return {
    id: 'cpu-1',
    capturedAtEpochMillis: 1726000000000,
    serial: 'emulator-5554',
    parameters: samplingParameters({ target: { kind: 'APP', packageName: 'com.example.app' } }),
    profile: profile(),
    protobufTrace: '/tmp/aps-cpu-1.pb',
    perfDataBytes: 4096,
    simpleperfVersion: 'simpleperf 35.0.0',
    devicePath: '/data/local/tmp/aps/perf.data',
  };
}

function session(): CpuProfileSession {
  const result = captureResult();
  return createCpuProfileSession(result, {
    reportFile: 'cpu-1/report.pb',
    table: samplesToCallStackTable(result.profile.samples),
  });
}

describe('cpu profile session record', () => {
  it('summarizes the capture for persistence', () => {
    const record = session().record;
    expect(record.id).toBe('cpu-1');
    expect(record.reportFile).toBe('cpu-1/report.pb');
    expect(record.sampleCount).toBe(2);
    expect(record.perfDataBytes).toBe(4096);
    expect(record.simpleperfVersion).toBe('simpleperf 35.0.0');
    expect(record.eventTypes).toEqual(['cpu-cycles']);
    expect(record.threadKeys).toEqual(['main (tid 42)']);
    expect(record.packageName).toBe('com.example.app');
    expect(record.metadata).toEqual({
      appPackageName: 'com.example.app',
      appType: 'profileable',
      androidSdkVersion: '34',
      androidBuildType: 'user',
      traceOffCpu: false,
    });
    expect(record.parameters).toEqual({
      target: 'com.example.app',
      event: 'cpu-clock',
      rate: '1000 Hz',
      durationSeconds: 10,
      callGraph: 'DWARF',
      scope: 'BOTH',
    });
    // Everything persisted is JSON safe: no bigint survives JSON.stringify.
    expect(() => JSON.stringify(record)).not.toThrow();
  });

  it('omits absent optional metadata and describes every target kind', () => {
    expect(metadataRecord(undefined)).toEqual({ traceOffCpu: false });
    expect(
      metadataRecord({ eventTypes: [], traceOffCpu: true }),
    ).toEqual({ traceOffCpu: true });
    const targets = [
      { kind: 'PROCESS', pid: 42 },
      { kind: 'PROCESS_NAME', name: 'surfaceflinger' },
      { kind: 'THREAD', tid: 7 },
      { kind: 'SYSTEM_WIDE' },
    ] as const;
    expect(targets.map((target) => samplingParametersRecord(samplingParameters({ target })).target)).toEqual([
      'pid 42',
      'surfaceflinger',
      'tid 7',
      'system wide',
    ]);
    expect(samplingParametersRecord(samplingParameters({ rate: { kind: 'PERIOD', events: 100000n } })).rate).toBe(
      '100000 events',
    );
    expect(directionOf('INVERTED')).toBe('INVERTED');
    expect(directionOf('anything else')).toBe('FORWARD');
  });

  it('maps renderer transform requests back to analysis transforms', () => {
    expect(transformFromRequest({ kind: 'FOCUS_CALL_NODE', path: ['1', '2'] })).toEqual({
      kind: 'FOCUS_CALL_NODE',
      path: [1n, 2n],
    });
    expect(transformFromRequest({ kind: 'DROP_FUNCTION', functionId: '7' })).toEqual({
      kind: 'DROP_FUNCTION',
      function: 7n,
    });
    expect(transformFromRequest({ kind: 'COLLAPSE_RESOURCE', resource: '/lib.so' })).toEqual({
      kind: 'COLLAPSE_RESOURCE',
      resource: '/lib.so',
    });
    expect(() => transformFromRequest({ kind: 'FOCUS_FUNCTION', functionId: 'not-a-number' })).toThrow(
      /Invalid function id/,
    );
  });
});

describe('flame graph payload', () => {
  it('serializes the projected graph into JSON-safe strings', () => {
    const payload = buildFlameGraphPayload(session().table, DEFAULT_CALL_STACK_QUERY);
    expect(payload.totalWeight).toBe('10');
    expect(payload.nodeCount).toBe(2);
    expect(payload.rowCount).toBe(2);
    expect(payload.startsAtBottom).toBe(true);
    expect(payload.emptyReason).toBeUndefined();
    expect(payload.nodes.map((node) => node.symbolName)).toEqual(['memcpy', 'malloc']);
    expect(payload.nodes[0]?.parent).toBe(-1);
    expect(payload.nodes[1]?.parent).toBe(0);
    expect(payload.nodes[0]?.inclusiveWeight).toBe('10');
    expect(payload.nodes[0]?.selfWeight).toBe('4');
    expect(payload.nodes[1]?.selfWeight).toBe('6');
    expect(payload.nodes[0]?.start).toBe(0);
    expect(payload.nodes[0]?.end).toBe(1);
    expect(payload.nodes.map((node) => node.index)).toEqual([0, 1]);
    expect(payload.nodes.every((node) => typeof node.id === 'string')).toBe(true);
    expect(() => JSON.stringify(payload)).not.toThrow();
  });

  it('labels the thread and reports rejected transforms', () => {
    const table = session().table;
    const payload = buildFlameGraphPayload(
      table,
      {
        ...DEFAULT_CALL_STACK_QUERY,
        transforms: [{ kind: 'DROP_FUNCTION', function: 1234n }],
      },
      { threadKey: 'main (tid 42)' },
    );
    expect(payload.threadKey).toBe('main (tid 42)');
    expect(payload.invalidTransforms).toEqual([]);
    expect(payload.sourceStackCount).toBe(2);

    const invalid = buildFlameGraphPayload(table, {
      ...DEFAULT_CALL_STACK_QUERY,
      transforms: [{ kind: 'FOCUS_CALL_NODE', path: [999n] }],
    });
    expect(invalid.invalidTransforms).toEqual(['FOCUS_CALL_NODE(999)']);

    const described = buildFlameGraphPayload(table, {
      ...DEFAULT_CALL_STACK_QUERY,
      transforms: [
        { kind: 'COLLAPSE_RESOURCE', resource: '/system/lib64/libc.so' },
        { kind: 'FOCUS_CATEGORY', category: 'render' },
      ],
    });
    expect(described.invalidTransforms).toEqual([]);

    const empty = buildFlameGraphPayload(table, { ...DEFAULT_CALL_STACK_QUERY, searchText: 'zzz' });
    expect(empty.emptyReason).toBe('SEARCH_FILTERED_ALL');
    expect(empty.nodes).toEqual([]);
    expect(empty.totalWeight).toBe('0');
  });
});

describe('topFunctions', () => {
  it('ranks by inclusive and by self weight', () => {
    const table = session().table;
    const inclusive = topFunctions(table);
    expect(inclusive.map((entry) => entry.symbolName)).toEqual(['memcpy', 'malloc']);
    expect(inclusive.map((entry) => entry.weight)).toEqual(['10', '6']);
    expect(inclusive[0]?.sampleCount).toBe('2');

    const self = topFunctions(table, { by: 'SELF' });
    expect(self.map((entry) => entry.symbolName)).toEqual(['malloc', 'memcpy']);
    expect(self.map((entry) => entry.weight)).toEqual(['6', '4']);
    expect(self[0]?.implementation).toBe('NATIVE');

    expect(topFunctions(table, { limit: 1 })).toHaveLength(1);
    expect(topFunctions(table, { threadKey: 'other (tid 1)' })).toEqual([]);
    expect(topFunctions(table, { threadKey: 'main (tid 42)' })).toHaveLength(2);
  });

  it('ranks self weight without counting non-leaf frames', () => {
    const samples: NormalizedSample[] = [
      {
        timestampNanos: 1n,
        processId: 1,
        threadId: 1,
        threadName: 'main',
        eventType: 'cpu-cycles',
        eventCount: 3n,
        frames: [
          { virtualAddress: 2n, fileId: 0, symbolId: 1, filePath: '/lib.so', symbolName: 'leaf', executionType: 'NATIVE' as ProfileExecutionType },
          { virtualAddress: 1n, fileId: 0, symbolId: 0, filePath: '/lib.so', symbolName: 'root', executionType: 'NATIVE' as ProfileExecutionType },
        ],
      },
    ];
    const table = samplesToCallStackTable(samples);
    expect(topFunctions(table, { by: 'SELF' }).map((entry) => entry.symbolName)).toEqual(['leaf']);
    expect(topFunctions(table).map((entry) => entry.symbolName).sort()).toEqual(['leaf', 'root']);
  });
});
