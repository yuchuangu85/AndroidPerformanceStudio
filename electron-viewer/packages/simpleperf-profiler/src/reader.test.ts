import { describe, expect, it } from 'vitest';
import { readSimpleperfReport } from './reader.js';
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

describe('readSimpleperfReport', () => {
  it('frames records, decodes each one, and reports byte offsets', () => {
    const bytes = stream([
      metaInfoEntry(metaInfoRecord({ eventTypes: ['cpu-cycles'], appPackageName: 'com.example.app', traceOffCpu: true })),
      fileEntry(fileRecord({ id: 0, path: '/system/lib64/libc.so', symbols: ['memcpy', 'malloc'] })),
      threadEntry(threadRecord({ threadId: 42, processId: 7, threadName: 'RenderThread' })),
      sampleRecord(
        sample({
          time: 1234567890n,
          threadId: 42,
          eventCount: 1000n,
          eventTypeId: 0,
          callchain: [
            { vaddrInFile: 0x1000n, fileId: 0, symbolId: 1, executionType: 0 },
            { vaddrInFile: 0x2000n, fileId: 0, symbolId: -1, executionType: 1 },
          ],
        }),
      ),
      lostEntry(lostRecord(10n, 2n)),
    ]);

    const seen: string[] = [];
    const result = readSimpleperfReport(bytes, { onRecord: (envelope) => seen.push(envelope.record.kind) });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.version).toBe(1);
    expect(result.value.recordCount).toBe(5n);
    expect(result.value.bytesRead).toBe(BigInt(bytes.length));
    expect(seen).toEqual(['META_INFO', 'FILE', 'THREAD', 'SAMPLE', 'LOST']);

    const offsets: bigint[] = [];
    readSimpleperfReport(bytes, { onRecord: (envelope) => offsets.push(envelope.byteOffset) });
    const first = offsets[0] as bigint;
    expect(first).toBe(BigInt('SIMPLEPERF'.length + 2 + 4));
    expect(offsets[1]).toBeGreaterThan(first);
  });

  it('decodes signed symbol ids, event counts, and unwind errors exactly', () => {
    const bytes = stream([
      sampleRecord(
        sample({
          threadId: 1,
          eventCount: 9007199254740993n,
          callchain: [{ vaddrInFile: 18446744073709551615n, fileId: 0, symbolId: -1, executionType: 3 }],
          unwindingResult: { rawErrorCode: 6, errorAddr: 4096n, errorCode: 6 },
        }),
      ),
    ]);
    let captured: unknown;
    readSimpleperfReport(bytes, {
      onRecord: (envelope) => {
        captured = envelope.record;
      },
    });
    const record = captured as {
      kind: string;
      sample: {
        eventCount: bigint;
        callchain: readonly { symbolId: number; vaddrInFile: bigint }[];
        unwindingResult?: { rawErrorCode: number; errorAddr: bigint; errorCode: number };
      };
    };
    expect(record.kind).toBe('SAMPLE');
    // 2^53+1 only survives because the value never passes through a double.
    expect(record.sample.eventCount).toBe(9007199254740993n);
    expect(record.sample.callchain[0]?.symbolId).toBe(-1);
    expect(record.sample.callchain[0]?.vaddrInFile).toBe(18446744073709551615n);
    expect(record.sample.unwindingResult).toEqual({ rawErrorCode: 6, errorAddr: 4096n, errorCode: 6 });
    expect(record.sample.callchain).toHaveLength(1);
  });

  it('rejects a bad magic, an unsupported version, and truncation with stable codes', () => {
    const badMagic = readSimpleperfReport(stream([], { magic: 'SIMPLEPERX' }));
    expect(badMagic.ok).toBe(false);
    if (!badMagic.ok) expect(badMagic.error.code).toBe('SIMPLEPERF_MAGIC_INVALID');

    const badVersion = readSimpleperfReport(stream([], { version: 2 }));
    expect(badVersion.ok).toBe(false);
    if (!badVersion.ok) expect(badVersion.error.code).toBe('SIMPLEPERF_VERSION_UNSUPPORTED');

    // Magic plus the two version bytes, then the stream simply stops.
    const truncatedLength = readSimpleperfReport(
      Uint8Array.from([...new TextEncoder().encode('SIMPLEPERF'), 1, 0]),
    );
    expect(truncatedLength.ok).toBe(false);
    if (!truncatedLength.ok) expect(truncatedLength.error.code).toBe('SIMPLEPERF_LENGTH_TRUNCATED');

    const truncatedPayload = stream([sampleRecord(new Uint8Array(32))]).subarray(0, 20);
    const truncated = readSimpleperfReport(truncatedPayload);
    expect(truncated.ok).toBe(false);
    if (!truncated.ok) expect(truncated.error.code).toBe('SIMPLEPERF_RECORD_TRUNCATED');
  });

  it('enforces the per-record size limit and the reader option contract', () => {
    const bytes = stream([sampleRecord(sample({ threadId: 1 }))]);
    // The smallest sample record is longer than two bytes.
    const rejected = readSimpleperfReport(bytes, { maxRecordBytes: 2 });
    expect(rejected.ok).toBe(false);
    if (!rejected.ok) expect(rejected.error.code).toBe('SIMPLEPERF_RECORD_TOO_LARGE');

    const invalidLimit = readSimpleperfReport(bytes, { maxRecordBytes: 0 });
    expect(invalidLimit.ok).toBe(false);
    if (!invalidLimit.ok) expect(invalidLimit.error.code).toBe('SIMPLEPERF_LIMIT_INVALID');
  });
});
