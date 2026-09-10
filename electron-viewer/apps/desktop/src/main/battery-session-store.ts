import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { totalNetworkBytes, type BatteryExperimentResult } from '@aps/battery-profiler';
import type { BatterySessionSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

/** Stores complete battery experiments: session, runs, and derived analysis. */
export class BatterySessionStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(result: BatteryExperimentResult): Promise<BatterySessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeBatteryExperiment(result);
    await writeFile(this.pathFor(result.session.id), JSON.stringify(result));
    const records = (await this.list()).filter((existing) => existing.id !== result.session.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<BatterySessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isSummary);
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<BatteryExperimentResult | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as BatteryExperimentResult;
      if (parsed.session === undefined || !Array.isArray(parsed.runs)) return undefined;
      return parsed;
    } catch {
      return undefined;
    }
  }
}

export function summarizeBatteryExperiment(result: BatteryExperimentResult): BatterySessionSummary {
  const analysis = result.analysis;
  const wakelockMedian = analysis.wakelockDurationMs.median;
  const networkMedian = analysis.networkBytes.median;
  const energyMedian = analysis.energyMah.median;
  return {
    id: result.session.id,
    packageName: result.session.packageName,
    uid: result.session.uid,
    capturedAtEpochMillis: result.session.createdAtEpochMillis,
    runCount: result.runs.length,
    warningCount: analysis.warnings.length,
    ...(wakelockMedian !== undefined ? { wakelockMedianMs: wakelockMedian } : {}),
    ...(networkMedian !== undefined ? { networkMedianBytes: networkMedian } : {}),
    ...(energyMedian !== undefined ? { energyMedianMah: energyMedian } : {}),
  };
}

/** Exported for callers that need the raw total without the analysis envelope. */
export function totalBytesOf(result: BatteryExperimentResult): number {
  return result.analysis.runs.reduce((total, run) => total + totalNetworkBytes(run.network), 0);
}

function isSummary(value: unknown): value is BatterySessionSummary {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['packageName'] === 'string' &&
    typeof record['uid'] === 'number' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['runCount'] === 'number'
  );
}
