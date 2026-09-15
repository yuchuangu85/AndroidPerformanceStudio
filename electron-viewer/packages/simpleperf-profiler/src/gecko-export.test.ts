import { describe, expect, it } from 'vitest';
import { exportGeckoProfile } from './gecko-export.js';
import { readGeckoProfileText } from './gecko.js';
import type { NormalizedSample } from './model.js';

const samples: readonly NormalizedSample[] = [
  {
    timestampNanos: 1_500_000n,
    processId: 100,
    threadId: 101,
    threadName: 'RenderThread',
    eventType: 'cpu-clock',
    eventCount: 7n,
    // Simpleperf stores the leaf first.
    frames: [
      { virtualAddress: 3n, fileId: 3, symbolId: 3, filePath: '[JIT app cache]', symbolName: 'jit', executionType: 'JIT_JVM' },
      { virtualAddress: 2n, fileId: 2, symbolId: 2, filePath: '/system/lib64/libgui.so', symbolName: 'render', executionType: 'NATIVE' },
      { virtualAddress: 1n, fileId: 1, symbolId: 1, filePath: '/system/lib64/libapp.so', symbolName: 'main', executionType: 'NATIVE' },
    ],
  },
  {
    timestampNanos: 3_000_000n,
    processId: 100,
    threadId: 101,
    threadName: 'RenderThread',
    eventType: 'cpu-clock',
    eventCount: 1n,
    frames: [],
  },
  {
    timestampNanos: 4_000_000n,
    processId: 100,
    threadId: 102,
    threadName: 'Worker',
    eventType: 'cpu-clock',
    eventCount: 1n,
    frames: [
      { virtualAddress: 4n, fileId: 4, symbolId: 4, filePath: '/proc/kallsyms', symbolName: '__schedule', executionType: 'KERNEL' },
    ],
  },
];

describe('Gecko profile export', () => {
  it('writes Kotlin-compatible v24 tables and round-trips normalized samples', () => {
    const exported = exportGeckoProfile(samples);
    const root = JSON.parse(exported.json) as { meta: { version: number; presymbolicated: boolean }; threads: unknown[] };
    expect(exported.threadCount).toBe(2);
    expect(exported.sampleCount).toBe(3);
    expect(root.meta).toMatchObject({ version: 24, presymbolicated: true });
    expect(root.threads).toHaveLength(2);

    const parsed = readGeckoProfileText(exported.json);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;
    expect(parsed.value.samples.map((sample) => ({
      timestampNanos: sample.timestampNanos,
      processId: sample.processId,
      threadId: sample.threadId,
      threadName: sample.threadName,
      eventType: sample.eventType,
      eventCount: sample.eventCount,
      frames: sample.frames.map((frame) => ({
        filePath: frame.filePath,
        symbolName: frame.symbolName,
        executionType: frame.executionType,
      })),
    }))).toEqual([
      {
        timestampNanos: 1_500_000n,
        processId: 100,
        threadId: 101,
        threadName: 'RenderThread',
        eventType: 'samples',
        eventCount: 1n,
        frames: [
          { filePath: '[JIT app cache]', symbolName: 'jit', executionType: 'JIT_JVM' },
          { filePath: '/system/lib64/libgui.so', symbolName: 'render', executionType: 'NATIVE' },
          { filePath: '/system/lib64/libapp.so', symbolName: 'main', executionType: 'NATIVE' },
        ],
      },
      {
        timestampNanos: 3_000_000n,
        processId: 100,
        threadId: 101,
        threadName: 'RenderThread',
        eventType: 'samples',
        eventCount: 1n,
        frames: [],
      },
      {
        timestampNanos: 4_000_000n,
        processId: 100,
        threadId: 102,
        threadName: 'Worker',
        eventType: 'samples',
        eventCount: 1n,
        frames: [{ filePath: '/proc/kallsyms', symbolName: '__schedule', executionType: 'KERNEL' }],
      },
    ]);
  });

  it('interns root-to-leaf stack prefixes and preserves frame categories', () => {
    const root = JSON.parse(exportGeckoProfile(samples).json) as {
      threads: Array<{ frameTable: { data: unknown[][] }; stackTable: { data: unknown[][] } }>;
    };
    const render = root.threads[0];
    expect(render?.stackTable.data).toEqual([[null, 0, 0], [0, 1, 0], [1, 2, 0]]);
    expect(render?.frameTable.data.map((frame) => frame[7])).toEqual([2, 2, 7]);
    const worker = root.threads[1];
    expect(worker?.frameTable.data.map((frame) => frame[7])).toEqual([5]);
  });
});
