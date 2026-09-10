import { describe, expect, it } from 'vitest';
import { MAX_HAR_BYTES } from '@aps/network-profiler/node';
import { importHarFile, type NetworkImportDependencies } from './network-import-service.js';

const HAR = JSON.stringify({
  log: {
    version: '1.2',
    creator: { name: 'Chrome DevTools', version: '120.0' },
    entries: [
      {
        startedDateTime: '2026-01-01T00:00:00.000Z',
        time: 10,
        request: { method: 'GET', url: 'https://api.example.com/a?token=x', headers: [], bodySize: 0 },
        response: { status: 200, headers: [], bodySize: 1 },
        timings: { blocked: 0, dns: 1, connect: 2, ssl: 3, send: 0, wait: 3, receive: 1 },
      },
    ],
  },
});

function dependencies(overrides: Partial<NetworkImportDependencies> = {}): NetworkImportDependencies {
  return {
    readFileText: async () => HAR,
    fileSize: async () => HAR.length,
    isRegularFile: () => true,
    ...overrides,
  };
}

describe('importHarFile', () => {
  it('imports and minimizes a HAR file', async () => {
    const result = await importHarFile(dependencies(), '/tmp/traffic.har');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.calls).toHaveLength(1);
    expect(result.value.calls[0]?.redactedUrl).toBe('https://api.example.com/<redacted-path>?token=<redacted>');
    expect(result.value.session.redactionPolicyVersion).toBe(1);
    expect(result.value.session.sourceFingerprint).toMatch(/^[0-9a-f]{64}$/);
  });

  it('reports a missing file, an unreadable file, and an oversized file', async () => {
    const missing = await importHarFile(dependencies({ isRegularFile: () => false }), '/tmp/none.har');
    expect(missing.ok).toBe(false);
    if (!missing.ok) expect(missing.error.code).toBe('NETWORK_HAR_NOT_FOUND');

    const unreadable = await importHarFile(
      dependencies({
        fileSize: async () => {
          throw new Error('EACCES');
        },
      }),
      '/tmp/locked.har',
    );
    expect(unreadable.ok).toBe(false);
    if (!unreadable.ok) expect(unreadable.error.code).toBe('NETWORK_HAR_UNREADABLE');

    const oversized = await importHarFile(dependencies({ fileSize: async () => MAX_HAR_BYTES + 1 }), '/tmp/big.har');
    expect(oversized.ok).toBe(false);
    if (!oversized.ok) expect(oversized.error.code).toBe('NETWORK_HAR_TOO_LARGE');
  });

  it('reports malformed JSON as a validation failure', async () => {
    const malformed = await importHarFile(dependencies({ readFileText: async () => '{oops' }), '/tmp/bad.har');
    expect(malformed.ok).toBe(false);
    if (!malformed.ok) expect(malformed.error.code).toBe('NETWORK_HAR_MALFORMED');
  });
});
