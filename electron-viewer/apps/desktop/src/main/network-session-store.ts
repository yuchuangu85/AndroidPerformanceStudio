import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { NetworkCaptureResult } from '@aps/network-profiler';
import type { NetworkSessionSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

/** Stores imported network captures with a summary index. */
export class NetworkSessionStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(result: NetworkCaptureResult): Promise<NetworkSessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeNetworkCapture(result);
    await writeFile(this.pathFor(result.session.id), JSON.stringify(result));
    const records = (await this.list()).filter((existing) => existing.id !== result.session.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<NetworkSessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isSummary);
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<NetworkCaptureResult | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as NetworkCaptureResult;
      if (parsed.session === undefined || !Array.isArray(parsed.calls)) return undefined;
      return parsed;
    } catch {
      return undefined;
    }
  }
}

export function summarizeNetworkCapture(result: NetworkCaptureResult): NetworkSessionSummary {
  const failed = result.calls.filter((call) => call.outcome === 'FAILED').length;
  const incomplete = result.calls.filter((call) => call.outcome === 'INCOMPLETE').length;
  return {
    id: result.session.id,
    callCount: result.calls.length,
    failedCallCount: failed,
    incompleteCallCount: incomplete,
    status: result.session.status,
    startedAtEpochMillis: result.session.startedAtEpochMillis,
    ...(result.session.sourceProducer !== undefined ? { producer: result.session.sourceProducer } : {}),
    ...(result.session.sourceFormatVersion !== undefined
      ? { sourceFormatVersion: result.session.sourceFormatVersion }
      : {}),
  };
}

function isSummary(value: unknown): value is NetworkSessionSummary {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['callCount'] === 'number' &&
    typeof record['startedAtEpochMillis'] === 'number' &&
    typeof record['status'] === 'string'
  );
}
