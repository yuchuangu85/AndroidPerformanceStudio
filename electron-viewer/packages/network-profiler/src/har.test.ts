import { describe, expect, it } from 'vitest';
import { parseHar } from './har.js';
import { phaseDurationNs } from './model.js';

const HAR = JSON.stringify({
  log: {
    version: '1.2',
    creator: { name: 'Chrome DevTools', version: '120.0' },
    entries: [
      {
        startedDateTime: '2026-01-01T00:00:00.000Z',
        time: 120.5,
        request: {
          method: 'GET',
          url: 'https://api.example.com/v1/users/12345?token=abc',
          headers: [
            { name: 'Authorization', value: 'Bearer secret' },
            { name: 'Accept', value: 'application/json' },
          ],
          bodySize: 0,
        },
        response: {
          status: 200,
          httpVersion: 'HTTP/2',
          headers: [{ name: 'Set-Cookie', value: 'sid=1' }],
          bodySize: 1024,
          content: { size: 4096 },
        },
        timings: { blocked: 1, dns: 2, connect: 3, ssl: 4, send: 0.5, wait: 100, receive: 10 },
      },
      {
        startedDateTime: '2026-01-01T00:00:01.000Z',
        time: 50,
        request: { method: 'GET', url: 'http://plain.example.com/health', headers: [], bodySize: -1 },
        response: { status: 500, httpVersion: 'HTTP/1.1', headers: [], bodySize: 0 },
        timings: { blocked: -1, dns: -1, connect: -1, ssl: -1, send: 0, wait: 45, receive: 5 },
        _error: 'net::ERR_FAILED',
      },
    ],
  },
});

describe('parseHar', () => {
  it('parses entries into redacted calls with inferred phases', () => {
    const { result, warnings } = parseHar(HAR);
    expect(warnings).toEqual([]);
    expect(result.calls).toHaveLength(2);

    const first = result.calls[0];
    expect(first?.method).toBe('GET');
    expect(first?.redactedUrl).toBe('https://api.example.com/<redacted-path>?token=<redacted>');
    expect(first?.startedNs).toBe(0);
    expect(first?.endedNs).toBe(120_500_000);
    expect(first?.outcome).toBe('COMPLETED');
    expect(first?.source).toBe('HAR_IMPORT');

    const exchange = first?.exchanges[0];
    expect(exchange?.connectionUse).toBe('NEW');
    expect(exchange?.statusCode).toBe(200);
    expect(exchange?.protocol).toBe('HTTP/2');
    expect(exchange?.requestBytes).toBe(0);
    expect(exchange?.responseBytes).toBe(1024);
    expect(exchange?.decodedResponseBytes).toBe(4096);
    expect(exchange?.requestHeaders['Authorization']).toBe('<redacted>');
    expect(exchange?.requestHeaders['Accept']).toBe('application/json');
    expect(exchange?.responseHeaders['Set-Cookie']).toBe('<redacted>');

    const dns = exchange?.phases.find((phase) => phase.kind === 'DNS');
    expect(dns?.confidence).toBe('INFERRED');
    expect(dns?.availability).toBe('VALUE');
    expect(dns !== undefined ? phaseDurationNs(dns) : undefined).toBe(2_000_000);
    const total = exchange?.phases.find((phase) => phase.kind === 'TOTAL');
    expect(total?.availability).toBe('VALUE');
  });

  it('offsets later entries and detects reused connections', () => {
    const second = parseHar(HAR).result.calls[1];
    expect(second?.startedNs).toBe(1_000_000_000);
    expect(second?.outcome).toBe('FAILED');
    expect(second?.exchanges[0]?.statusCode).toBe(500);
    expect(second?.exchanges[0]?.connectionUse).toBe('REUSED');
    expect(second?.exchanges[0]?.failure?.type).toBe('HAR_ERROR');
    const tls = second?.exchanges[0]?.phases.find((phase) => phase.kind === 'TLS');
    expect(tls?.availability).toBe('NOT_APPLICABLE');
    const blocked = second?.exchanges[0]?.phases.find((phase) => phase.kind === 'DISPATCHER_QUEUE');
    expect(blocked?.availability).toBe('UNAVAILABLE');
  });

  it('reports producer, completeness, and the session fingerprint', () => {
    const { result } = parseHar(HAR, 'f'.repeat(64));
    expect(result.session.sourceProducer).toBe('Chrome DevTools 120.0');
    expect(result.session.completeness.status).toBe('COMPLETE');
    expect(result.session.status).toBe('COMPLETE');
    expect(result.session.sourceFormatVersion).toBe('1.2');
    expect(result.session.sourceFingerprint).toBe('f'.repeat(64));
    expect(result.session.coverage.instrumentationMode).toBe('HAR_IMPORT');
    expect(result.session.coverage.windowEndedNs).toBe(1_050_000_000);
  });

  it('treats invalid timings and unsupported input as incomplete or unusable', () => {
    const invalid = HAR.replace('"receive":10', '"receive":-7');
    const parsed = parseHar(invalid);
    expect(parsed.warnings.some((warning) => warning.includes('invalid timing'))).toBe(true);
    expect(parsed.result.session.completeness.status).toBe('PARTIAL');
    expect(parsed.result.session.status).toBe('PARTIAL');

    expect(() => parseHar(JSON.stringify({ log: { version: '9.9', entries: [] } }))).toThrow(/Unsupported HAR version/);
    expect(() => parseHar(JSON.stringify({ log: { version: '1.2', entries: [] } }))).toThrow(/no valid entries/);
    expect(() => parseHar(JSON.stringify({}))).toThrow(/log object is missing/);
  });
});
