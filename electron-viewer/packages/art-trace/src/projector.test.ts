import { describe, expect, it } from 'vitest';
import { parseArtTrace } from './parser.js';
import { toCallStackTable, topMethods, threadKeyOf, buildArtTraceFlameGraph } from './projector.js';
import { DEFAULT_CALL_STACK_QUERY } from '@aps/profile-analysis';
import type { ArtTraceAnalysis } from './model.js';

const TRACE_MAGIC = 0x574f4c53;

class Writer {
  private readonly chunks: number[] = [];
  u8(value: number): this {
    this.chunks.push(value & 0xff);
    return this;
  }
  u16(value: number): this {
    this.chunks.push(value & 0xff, (value >> 8) & 0xff);
    return this;
  }
  u24(value: number): this {
    this.chunks.push(value & 0xff, (value >> 8) & 0xff, (value >> 16) & 0xff);
    return this;
  }
  u32(value: number): this {
    this.chunks.push(value & 0xff, (value >> 8) & 0xff, (value >> 16) & 0xff, (value >>> 24) & 0xff);
    return this;
  }
  u64(value: bigint): this {
    const low = Number(BigInt.asUintN(32, value));
    const high = Number(BigInt.asUintN(32, value >> 32n));
    return this.u32(low).u32(high);
  }
  uleb(value: bigint): this {
    let remaining = value;
    for (;;) {
      const byte = Number(remaining & 0x7fn);
      remaining >>= 7n;
      if (remaining === 0n) {
        this.chunks.push(byte);
        break;
      }
      this.chunks.push(byte | 0x80);
    }
    return this;
  }
  sleb(value: bigint): this {
    let remaining = value;
    for (;;) {
      const byte = Number(remaining & 0x7fn);
      remaining >>= 7n;
      const signBit = (byte & 0x40) !== 0;
      if ((remaining === 0n && !signBit) || (remaining === -1n && signBit)) {
        this.chunks.push(byte);
        break;
      }
      this.chunks.push(byte | 0x80);
    }
    return this;
  }
  text(value: string): this {
    for (const byte of new TextEncoder().encode(value)) this.chunks.push(byte);
    return this;
  }
  raw(value: Uint8Array): this {
    for (const byte of value) this.chunks.push(byte);
    return this;
  }
  bytes(): Uint8Array {
    return Uint8Array.from(this.chunks);
  }
}

function streamingTrace(options: { version?: number } = {}): Uint8Array {
  const version = options.version ?? 5;
  const dualClock = version === 5;
  // magic(4) + version(2) + start time(8) + padding(18) = the 32 byte header.
  const header = new Writer().u32(TRACE_MAGIC).u16(version).u64(1_000_000n).u32(0).u32(0).u32(0).u32(0).u16(0);
  const threadInfo = new Writer().u8(0).u32(7).u16(4).text('main').bytes();
  const methodInfo = (id: bigint, info: string): Uint8Array => {
    const name = new TextEncoder().encode(info);
    return new Writer().u8(1).u64(id).u16(name.length).raw(name).bytes();
  };
  // Method words are (method index << 2) | action: 4 is "enter method 1" and
  // 5 is "exit method 1", so the second record is a delta of one.
  const entryBlock = new Writer()
    .u8(2)
    .u32(7)
    .u24(2)
    // Two records: 2 bytes each single clock, 3 bytes each dual clock.
    .u32(dualClock ? 6 : 4)
    .sleb(4n)
    .uleb(100n);
  if (dualClock) entryBlock.uleb(10n);
  entryBlock.sleb(1n).uleb(50n);
  if (dualClock) entryBlock.uleb(20n);
  const summary = new Writer().u8(3).u16(0).bytes();
  return new Uint8Array(
    Buffer.concat([
      Buffer.from(header.bytes()),
      Buffer.from(threadInfo),
      Buffer.from(methodInfo(1n, 'Lcom/example/Foo\tbar\t()V\tFoo.java')),
      Buffer.from(methodInfo(2n, 'Lcom/example/Baz\tqux\t()V\tBaz.java')),
      Buffer.from(entryBlock.bytes()),
      Buffer.from(summary),
    ]),
  );
}

function classicTrace(): Uint8Array {
  // magic(4) + version(2) + data offset(2) + start time(8) + padding(16) = 32 bytes.
  const header = new Writer().u32(TRACE_MAGIC).u16(2).u16(32).u64(1234n).u32(0).u32(0).u32(0).u32(0);
  const text = '*threads\n7\tmain\n*methods\n4\tLcom/example/Foo\tbar\t()V\tFoo.java\n*end\n';
  const record = (threadId: number, methodValue: number, cpuMicros: number): Uint8Array =>
    new Writer().u16(threadId).u32(methodValue).u32(cpuMicros).bytes();
  return new Uint8Array(
    Buffer.concat([
      Buffer.from(header.bytes()),
      Buffer.from(new TextEncoder().encode(text)),
      Buffer.from(record(7, 1 << 2, 100)),
      Buffer.from(record(7, (1 << 2) | 1, 300)),
    ]),
  );
}

function analysisOf(bytes: Uint8Array): ArtTraceAnalysis {
  const result = parseArtTrace(bytes);
  if (!result.ok) throw new Error('fixture failed to parse: ' + result.error.code);
  return result.value;
}

describe('parseArtTrace', () => {
  it('parses the streaming layout with thread and method tables', () => {
    const analysis = analysisOf(streamingTrace());
    expect(analysis.header.version).toBe(5);
    expect(analysis.header.clockSource).toBe('DUAL');
    expect(analysis.header.startTimeNanos).toBe(1_000_000n);
    expect(analysis.threads.get(7)?.name).toBe('main');
    expect(analysis.methods.get(1n)?.className).toBe('Lcom/example/Foo');
    expect(analysis.methods.get(1n)?.methodName).toBe('bar');
    expect(analysis.methods.get(1n)?.signature).toBe('()V');
    expect(analysis.events).toHaveLength(2);
    expect(analysis.events[0]).toEqual({
      threadId: 7,
      methodId: 1n,
      action: 'ENTER',
      timeNanos: 100n,
      cpuNanos: 10n,
    });
    // The second record is a delta back to the same method with the exit action.
    expect(analysis.events[1]?.action).toBe('EXIT');
    expect(analysis.events[1]?.methodId).toBe(1n);
    expect(analysis.events[1]?.timeNanos).toBe(150n);
    expect(analysis.events[1]?.cpuNanos).toBe(30n);
    expect(analysis.warnings).toEqual([]);
  });

  it('parses the single-clock streaming layout', () => {
    const analysis = analysisOf(streamingTrace({ version: 4 }));
    expect(analysis.header.clockSource).toBe('SINGLE');
    expect(analysis.events[0]?.cpuNanos).toBeUndefined();
  });

  it('parses the classic layout and its text tables', () => {
    const analysis = analysisOf(classicTrace());
    expect(analysis.header.version).toBe(2);
    expect(analysis.header.startTimeNanos).toBe(1_234_000n);
    expect(analysis.threads.get(7)?.name).toBe('main');
    expect(analysis.methods.get(1n)?.methodName).toBe('bar');
    expect(analysis.events).toHaveLength(2);
    expect(analysis.events[0]?.timeNanos).toBe(100_000n);
    expect(analysis.events[1]?.timeNanos).toBe(300_000n);
  });

  it('rejects a bad magic, v1, and an unknown version with stable codes', () => {
    const badMagic = parseArtTrace(new Uint8Array([1, 2, 3, 4, 5, 6]));
    expect(badMagic.ok).toBe(false);
    if (!badMagic.ok) expect(badMagic.error.code).toBe('ART_TRACE_MAGIC_INVALID');

    const v1 = parseArtTrace(new Writer().u32(TRACE_MAGIC).u16(1).u32(0).u32(0).u32(0).u32(0).u32(0).bytes());
    expect(v1.ok).toBe(false);
    if (!v1.ok) expect(v1.error.code).toBe('ART_TRACE_VERSION_UNSUPPORTED');

    const v9 = parseArtTrace(new Writer().u32(TRACE_MAGIC).u16(9).bytes());
    expect(v9.ok).toBe(false);
    if (!v9.ok) expect(v9.error.code).toBe('ART_TRACE_VERSION_UNSUPPORTED');
  });

  it('reports a truncated stream instead of throwing', () => {
    const truncated = streamingTrace().subarray(0, 40);
    const result = parseArtTrace(truncated);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.error.code).toBe('ART_TRACE_MALFORMED');
  });
});

describe('toCallStackTable', () => {
  it('resamples intervals per thread using the event times', () => {
    const analysis = analysisOf(streamingTrace());
    const table = toCallStackTable(analysis);
    // Enter at 100, exit at 150, trace ends at 150: only one interval is closed.
    expect(table.stacks).toHaveLength(1);
    const stack = table.stacks[0];
    expect(stack?.weight).toBe(50n);
    expect(stack?.timestampNanos).toBe(100n);
    expect(stack?.threadKey).toBe('main (tid 7)');
    expect(stack?.frameIdsRootToLeaf).toEqual([1n]);
    const frame = table.frame(1n);
    // The class descriptor keeps its L prefix, exactly like the Kotlin projector.
    expect(frame.symbolName).toBe('Lcom.example.Foo.bar');
    expect(frame.resource).toBe('Lcom/example/Foo');
    expect(frame.implementation).toBe('MANAGED');
  });

  it('emits a final interval up to the end of the trace and nests frames', () => {
    const analysis: ArtTraceAnalysis = {
      header: { version: 5, startTimeNanos: 0n, clockSource: 'DUAL' },
      methods: new Map([
        [1n, { methodId: 1n, className: 'Lcom/example/Foo', methodName: 'outer', signature: '', sourceFile: '' }],
        [2n, { methodId: 2n, className: 'Lcom/example/Foo', methodName: 'inner', signature: '', sourceFile: '' }],
      ]),
      threads: new Map([[7, { threadId: 7, name: 'main' }]]),
      events: [
        { threadId: 7, methodId: 1n, action: 'ENTER', timeNanos: 100n },
        { threadId: 7, methodId: 2n, action: 'ENTER', timeNanos: 200n },
        { threadId: 7, methodId: 2n, action: 'EXIT', timeNanos: 400n },
        { threadId: 7, methodId: 1n, action: 'EXIT', timeNanos: 500n },
      ],
      startTimeNanos: 0n,
      endTimeNanos: 500n,
      warnings: [],
    };
    const table = toCallStackTable(analysis);
    // outer(100..200), inner(200..400), then outer again until it exits at 500.
    expect(table.stacks.map((stack) => stack.weight)).toEqual([100n, 200n, 100n]);
    expect(table.stacks[0]?.frameIdsRootToLeaf).toEqual([1n]);
    // The nested interval carries the whole root-to-leaf path.
    expect(table.stacks[1]?.frameIdsRootToLeaf).toEqual([1n, 2n]);
    expect(table.stacks[2]?.frameIdsRootToLeaf).toEqual([1n]);
    expect(threadKeyOf(analysis, 7)).toBe('main (tid 7)');
    expect(threadKeyOf(analysis, 99)).toBe('tid 99');
  });

  it('keeps events from threads missing from the thread table', () => {
    const analysis: ArtTraceAnalysis = {
      header: { version: 4, startTimeNanos: 0n, clockSource: 'SINGLE' },
      methods: new Map(),
      threads: new Map(),
      events: [
        { threadId: 3, methodId: 9n, action: 'ENTER', timeNanos: 0n },
        { threadId: 3, methodId: 9n, action: 'EXIT', timeNanos: 10n },
      ],
      startTimeNanos: 0n,
      endTimeNanos: 10n,
      warnings: [],
    };
    const table = toCallStackTable(analysis);
    expect(table.stacks).toHaveLength(1);
    expect(table.stacks[0]?.threadKey).toBe('tid 3');
    expect(table.frame(9n).symbolName).toBe('0x9');
  });

  it('ignores unbalanced exits', () => {
    const analysis: ArtTraceAnalysis = {
      header: { version: 4, startTimeNanos: 0n, clockSource: 'SINGLE' },
      methods: new Map([[1n, { methodId: 1n, className: 'C', methodName: 'm', signature: '', sourceFile: '' }]]),
      threads: new Map([[1, { threadId: 1, name: 't' }]]),
      events: [
        { threadId: 1, methodId: 1n, action: 'EXIT', timeNanos: 0n },
        { threadId: 1, methodId: 1n, action: 'ENTER', timeNanos: 5n },
      ],
      startTimeNanos: 0n,
      endTimeNanos: 10n,
      warnings: [],
    };
    const table = toCallStackTable(analysis);
    expect(table.stacks).toHaveLength(1);
    expect(table.stacks[0]?.weight).toBe(5n);
  });
});

describe('topMethods', () => {
  const analysis = analysisOf(streamingTrace());

  it('reports self and inclusive microseconds plus call counts', () => {
    const table = toCallStackTable(analysis);
    const rows = topMethods(table, analysis);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.symbolName).toBe('Lcom.example.Foo.bar');
    expect(rows[0]?.selfMicros).toBe(0);
    expect(rows[0]?.totalMicros).toBe(0);
    expect(rows[0]?.callCount).toBe(1);
    expect(rows[0]?.threadCount).toBe(1);
  });

  it('orders by the requested column and filters by search', () => {
    const big: ArtTraceAnalysis = {
      header: { version: 5, startTimeNanos: 0n, clockSource: 'DUAL' },
      methods: new Map([
        [1n, { methodId: 1n, className: 'Lcom/example/Foo', methodName: 'alpha', signature: '', sourceFile: '' }],
        [2n, { methodId: 2n, className: 'Lcom/example/Foo', methodName: 'beta', signature: '', sourceFile: '' }],
      ]),
      threads: new Map([[1, { threadId: 1, name: 'main' }]]),
      // Times are nanoseconds: 100 µs, 300 µs, and one millisecond.
      events: [
        { threadId: 1, methodId: 1n, action: 'ENTER', timeNanos: 0n },
        { threadId: 1, methodId: 2n, action: 'ENTER', timeNanos: 100_000n },
        { threadId: 1, methodId: 2n, action: 'EXIT', timeNanos: 300_000n },
        { threadId: 1, methodId: 1n, action: 'EXIT', timeNanos: 1_000_000n },
      ],
      startTimeNanos: 0n,
      endTimeNanos: 1_000_000n,
      warnings: [],
    };
    const table = toCallStackTable(big);
    const bySelf = topMethods(table, big, { sort: 'SELF_MICROS' });
    // alpha is the leaf from 0 to 100 µs and again after beta exits at 300 µs.
    expect(bySelf.map((row) => row.symbolName)).toEqual(['Lcom.example.Foo.alpha', 'Lcom.example.Foo.beta']);
    expect(bySelf[0]?.selfMicros).toBe(800);
    expect(bySelf[1]?.selfMicros).toBe(200);
    expect(bySelf[0]?.callCount).toBe(1);
    expect(bySelf[0]?.threadCount).toBe(1);
    const byTotal = topMethods(table, big, { sort: 'TOTAL_MICROS' });
    expect(byTotal[0]?.symbolName).toBe('Lcom.example.Foo.alpha');
    // alpha covers the whole run: 100 µs before beta, 200 µs around it, 700 µs after.
    expect(byTotal[0]?.totalMicros).toBe(1000);
    expect(topMethods(table, big, { search: 'beta' })).toHaveLength(1);
    expect(topMethods(table, big, { search: 'nothing' })).toHaveLength(0);
    expect(topMethods(table, big, { sort: 'SYMBOL', descending: false })[0]?.symbolName).toBe('Lcom.example.Foo.alpha');
    expect(topMethods(table, big, { limit: 1 })).toHaveLength(1);
  });
});

describe('buildArtTraceFlameGraph', () => {
  it('projects the table through the shared pipeline', () => {
    const analysis = analysisOf(streamingTrace());
    const table = toCallStackTable(analysis);
    const snapshot = buildArtTraceFlameGraph(table, DEFAULT_CALL_STACK_QUERY);
    expect(snapshot.callNodes.size).toBe(1);
    expect(snapshot.totalWeight).toBe(50n);
    expect(snapshot.rows.rowCount).toBe(1);
    expect(snapshot.emptyReason).toBeUndefined();
  });
});
