import { describe, expect, it } from 'vitest';
import {
  decodeCaptureArtifact,
  encodeCaptureArtifact,
} from './capture-artifact';

const SHA_A = 'a'.repeat(64);

const minimalArtifact = {
  id: 'capture-1',
  kind: 'perfetto-trace',
  location: '/tmp/a.pftrace',
  sha256: SHA_A,
  provenance: {
    acquisition: { kind: 'CAPTURE', application: 'APS', performedAtEpochMillis: 0 },
  },
};

describe('CaptureArtifact', () => {
  it('fills Kotlin defaults on decode', () => {
    const artifact = decodeCaptureArtifact(JSON.stringify(minimalArtifact));
    expect(artifact.contractVersion).toBe(1);
    expect(artifact.provenance.producer).toEqual({ producerType: 'unknown' });
    expect(artifact.provenance.processors).toEqual([]);
    expect(artifact.completeness).toBe('UNKNOWN');
    expect(artifact.warnings).toEqual([]);
    expect(artifact.privacy).toEqual({ containsSensitiveIdentity: false, redactions: ['DEVICE_SERIAL'] });
  });

  it('encodes in declaration order with defaults and without nulls', () => {
    const encoded = encodeCaptureArtifact(minimalArtifact);
    expect(encoded.startsWith('{"contractVersion":1,"id":"capture-1","kind":"perfetto-trace"')).toBe(true);
    expect(encoded).not.toContain('"format"');
    expect(encoded).not.toContain('"capturedAt"');
    expect(encoded).not.toContain('"device"');
    expect(encoded).not.toContain('"process"');
    expect(encoded).not.toContain('"requestedCapabilities"');
    expect(encoded).toContain('"producer":{"producerType":"unknown"}');
  });

  it('round-trips a fully populated artifact', () => {
    const full = {
      id: 'capture-2',
      kind: 'perfetto-trace',
      location: '/tmp/b.pftrace',
      sha256: SHA_A,
      format: { name: 'perfetto', version: 'v57.2' },
      provenance: {
        producer: { producerType: 'known', name: 'perfetto', version: 'v57.2' },
        acquisition: {
          kind: 'IMPORT',
          application: 'APS',
          applicationVersion: '0.0.0',
          performedAtEpochMillis: 1_700_000_000_000,
        },
        processors: [],
      },
      capturedAt: { clockDomain: 'BOOTTIME', timestampNanos: 123 },
      device: { localId: 'b'.repeat(64), manufacturer: 'Google', model: 'Pixel' },
      clockDomains: ['BOOTTIME'],
      clockMappings: [],
      availableCapabilities: ['perfetto.trace'],
      completeness: 'UNKNOWN' as const,
      limitations: [],
      warnings: [],
    };
    const decoded = decodeCaptureArtifact(encodeCaptureArtifact(full));
    expect(decoded.id).toBe('capture-2');
    expect(decoded.format).toEqual({ name: 'perfetto', version: 'v57.2' });
    expect(decoded.capturedAt).toEqual({ clockDomain: 'BOOTTIME', timestampNanos: 123n });
    expect(decoded.device?.model).toBe('Pixel');
    expect(decoded.clockDomains).toEqual(['BOOTTIME']);
    expect(decoded.availableCapabilities).toEqual(['perfetto.trace']);
  });

  it('preserves 64-bit nanosecond timestamps without precision loss', () => {
    const artifact = decodeCaptureArtifact(
      encodeCaptureArtifact({
        ...minimalArtifact,
        clockDomains: ['BOOTTIME'],
        capturedAt: { clockDomain: 'BOOTTIME', timestampNanos: 1_700_000_000_000_000_000n },
      }),
    );
    expect(artifact.capturedAt?.timestampNanos).toBe(1_700_000_000_000_000_000n);
    expect(encodeCaptureArtifact(artifact)).toContain('"timestampNanos":1700000000000000000');
  });

  it('ignores unknown keys when decoding', () => {
    const artifact = decodeCaptureArtifact(JSON.stringify({ ...minimalArtifact, futureField: 'ignored' }));
    expect(artifact.id).toBe('capture-1');
    expect('futureField' in artifact).toBe(false);
  });

  it('rejects blank artifact ids', () => {
    expect(() => encodeCaptureArtifact({ ...minimalArtifact, id: '   ' })).toThrow();
  });

  it('rejects undeclared clock domains', () => {
    expect(() =>
      encodeCaptureArtifact({
        ...minimalArtifact,
        capturedAt: { clockDomain: 'BOOTTIME', timestampNanos: 1 },
      }),
    ).toThrow();
  });

  it('rejects complete completeness without every requested capability', () => {
    expect(() =>
      encodeCaptureArtifact({
        ...minimalArtifact,
        completeness: 'COMPLETE',
        requestedCapabilities: ['perfetto.trace'],
        availableCapabilities: [],
      }),
    ).toThrow();
  });

  it('rejects partial completeness without a limitation per missing capability', () => {
    expect(() =>
      encodeCaptureArtifact({
        ...minimalArtifact,
        completeness: 'PARTIAL',
        requestedCapabilities: ['perfetto.trace'],
        availableCapabilities: [],
        limitations: [],
      }),
    ).toThrow();
  });

  it('accepts partial completeness with a limitation', () => {
    const artifact = decodeCaptureArtifact(
      encodeCaptureArtifact({
        ...minimalArtifact,
        completeness: 'PARTIAL',
        requestedCapabilities: ['perfetto.trace'],
        availableCapabilities: [],
        limitations: [{ capability: 'perfetto.trace', code: 'CAPTURE_FAILED', message: 'capture failed' }],
      }),
    );
    expect(artifact.completeness).toBe('PARTIAL');
  });

  it('rejects unknown completeness with requested capabilities', () => {
    expect(() =>
      encodeCaptureArtifact({ ...minimalArtifact, requestedCapabilities: ['perfetto.trace'] }),
    ).toThrow();
  });

  it('rejects a process identity from another device', () => {
    expect(() =>
      encodeCaptureArtifact({
        ...minimalArtifact,
        device: { localId: 'b'.repeat(64) },
        process: { pid: 10, deviceLocalId: 'c'.repeat(64) },
      }),
    ).toThrow();
  });

  it('computes process identity strength and rejects a mismatch', () => {
    const artifact = decodeCaptureArtifact(
      JSON.stringify({
        ...minimalArtifact,
        clockDomains: ['BOOTTIME'],
        device: { localId: 'b'.repeat(64) },
        process: {
          pid: 10,
          deviceLocalId: 'b'.repeat(64),
          startedAt: { clockDomain: 'BOOTTIME', timestampNanos: 5 },
        },
      }),
    );
    expect(artifact.process?.strength).toBe('STRONG');

    // A device + start marker computes STRONG, so an explicit WEAK is a mismatch.
    expect(() =>
      encodeCaptureArtifact({
        ...minimalArtifact,
        clockDomains: ['BOOTTIME'],
        device: { localId: 'b'.repeat(64) },
        process: {
          pid: 10,
          deviceLocalId: 'b'.repeat(64),
          startedAt: { clockDomain: 'BOOTTIME', timestampNanos: 5 },
          strength: 'WEAK',
        },
      }),
    ).toThrow();
  });
});
