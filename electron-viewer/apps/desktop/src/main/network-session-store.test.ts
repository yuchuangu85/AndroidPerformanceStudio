import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { NetworkCaptureResult } from '@aps/network-profiler';
import { parseHar } from '@aps/network-profiler/node';
import { NetworkSessionStore, summarizeNetworkCapture } from './network-session-store.js';

const directories: string[] = [];

async function temporaryDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), 'aps-network-'));
  directories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

function capture(outcomes: { status: number; time: number }[]): NetworkCaptureResult {
  const entries = outcomes.map((entry, index) => ({
    startedDateTime: '2026-01-01T00:00:0' + String(index) + '.000Z',
    time: entry.time,
    request: { method: 'GET', url: 'https://api.example.com/v1/x?token=y', headers: [], bodySize: 0 },
    response: { status: entry.status, headers: [], bodySize: 10 },
    timings: { blocked: 0, dns: 1, connect: 1, ssl: 1, send: 0, wait: entry.time - 3, receive: 1 },
  }));
  return parseHar(
    JSON.stringify({ log: { version: '1.2', creator: { name: 'test' }, entries } }),
  ).result;
}

describe('NetworkSessionStore', () => {
  it('summarizes and stores a capture', async () => {
    const directory = await temporaryDirectory();
    const store = new NetworkSessionStore(join(directory, 'network'));
    expect(await store.list()).toEqual([]);

    const result = capture([{ status: 200, time: 10 }, { status: 0, time: 5 }]);
    const summary = await store.add(result);
    expect(summary.callCount).toBe(2);
    expect(summary.status).toBe('COMPLETE');
    expect(summarizeNetworkCapture(result).incompleteCallCount).toBe(0);

    const loaded = await store.load(result.session.id);
    expect(loaded?.calls).toHaveLength(2);
    expect(loaded?.calls[0]?.redactedUrl).toContain('<redacted-path>');
    expect((await store.list())[0]?.id).toBe(result.session.id);
  });

  it('counts failed and incomplete calls', async () => {
    const failed = capture([{ status: 500, time: 10 }]);
    const withError = {
      ...failed,
      calls: failed.calls.map((call) => ({
        ...call,
        outcome: 'FAILED' as const,
        exchanges: call.exchanges.map((exchange) => ({ ...exchange, failure: { type: 'HAR_ERROR' } })),
      })),
    };
    expect(summarizeNetworkCapture(withError).failedCallCount).toBe(1);

    const incomplete = { ...failed, calls: failed.calls.map((call) => ({ ...call, endedNs: undefined, outcome: 'INCOMPLETE' as const })) };
    expect(summarizeNetworkCapture(incomplete).incompleteCallCount).toBe(1);
  });

  it('keeps the newest capture first and tolerates a malformed index', async () => {
    const directory = await temporaryDirectory();
    const store = new NetworkSessionStore(join(directory, 'network'));
    await store.add(capture([{ status: 200, time: 1 }]));
    const second = capture([{ status: 200, time: 2 }]);
    await store.add(second);
    const records = await store.list();
    expect(records).toHaveLength(2);
    expect(records[0]?.id).toBe(second.session.id);
    expect(await store.load('missing')).toBeUndefined();
  });
});
