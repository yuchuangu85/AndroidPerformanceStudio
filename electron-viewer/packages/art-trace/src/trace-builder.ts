/**
 * Builds ART method-trace files for tests. Kept out of the package entry point:
 * production code only ever reads these streams.
 */
export const TRACE_MAGIC = 0x574f4c53;

export class TraceWriter {
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

export function concatBytes(chunks: readonly Uint8Array[]): Uint8Array {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

function threadInfoPacket(threadId: number, name: string): Uint8Array {
  const encoded = new TextEncoder().encode(name);
  return new TraceWriter().u8(0).u32(threadId).u16(encoded.length).raw(encoded).bytes();
}

function methodInfoPacket(methodId: bigint, info: string): Uint8Array {
  const encoded = new TextEncoder().encode(info);
  return new TraceWriter().u8(1).u64(methodId).u16(encoded.length).raw(encoded).bytes();
}

/**
 * A streaming trace with one thread and two methods. With the dual clock
 * (version 5) the block records method, time, and CPU deltas per record.
 */
export function streamingTrace(options: { readonly version?: number } = {}): Uint8Array {
  const version = options.version ?? 5;
  const dualClock = version === 5;
  // magic(4) + version(2) + start time(8) + padding(18) = the 32 byte header.
  const header = new TraceWriter().u32(TRACE_MAGIC).u16(version).u64(1_000_000n).u32(0).u32(0).u32(0).u32(0).u16(0);
  // Method words are (method index << 2) | action: 4 is "enter method 1" and
  // 5 is "exit method 1", so the second record is a delta of one.
  const entryBlock = new TraceWriter().u8(2).u32(7).u24(2).u32(dualClock ? 6 : 4).sleb(4n).uleb(100n);
  if (dualClock) entryBlock.uleb(10n);
  entryBlock.sleb(1n).uleb(50n);
  if (dualClock) entryBlock.uleb(20n);
  const summary = new TraceWriter().u8(3).u16(0).bytes();
  return concatBytes([
    header.bytes(),
    threadInfoPacket(7, 'main'),
    methodInfoPacket(1n, 'Lcom/example/Foo\tbar\t()V\tFoo.java'),
    methodInfoPacket(2n, 'Lcom/example/Baz\tqux\t()V\tBaz.java'),
    entryBlock.bytes(),
    summary,
  ]);
}

/** A classic (version 2) trace with a text method and thread table. */
export function classicTrace(): Uint8Array {
  // magic(4) + version(2) + data offset(2) + start time(8) + padding(16) = 32 bytes.
  const header = new TraceWriter().u32(TRACE_MAGIC).u16(2).u16(32).u64(1234n).u32(0).u32(0).u32(0).u32(0);
  const text = '*threads\n7\tmain\n*methods\n4\tLcom/example/Foo\tbar\t()V\tFoo.java\n*end\n';
  const record = (threadId: number, methodValue: number, cpuMicros: number): Uint8Array =>
    new TraceWriter().u16(threadId).u32(methodValue).u32(cpuMicros).bytes();
  return concatBytes([
    header.bytes(),
    new TextEncoder().encode(text),
    record(7, 1 << 2, 100),
    record(7, (1 << 2) | 1, 300),
  ]);
}
