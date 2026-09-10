import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { analyzeSession, type FrameSession } from '@aps/frame-profiler';
import type { FrameSessionSummary } from '../shared/ipc.js';

const INDEX_FILE = 'index.json';

export type { FrameSessionSummary };

/** Stores raw frame sessions and derives a summary for the index. */
export class FrameSessionStore {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  pathFor(id: string): string {
    return join(this.directory, id + '.json');
  }

  async add(session: FrameSession): Promise<FrameSessionSummary> {
    await mkdir(this.directory, { recursive: true });
    const summary = summarizeSession(session);
    await writeFile(this.pathFor(session.id), JSON.stringify(session));
    const records = (await this.list()).filter((existing) => existing.id !== session.id);
    await writeFile(join(this.directory, INDEX_FILE), JSON.stringify([summary, ...records], null, 2));
    return summary;
  }

  async list(): Promise<FrameSessionSummary[]> {
    try {
      const parsed: unknown = JSON.parse(await readFile(join(this.directory, INDEX_FILE), 'utf8'));
      if (!Array.isArray(parsed)) return [];
      return parsed.filter(isSummary);
    } catch {
      return [];
    }
  }

  async load(id: string): Promise<FrameSession | undefined> {
    try {
      const parsed = JSON.parse(await readFile(this.pathFor(id), 'utf8')) as FrameSession;
      if (typeof parsed.id !== 'string' || !Array.isArray(parsed.frames)) return undefined;
      return { ...parsed, warnings: parsed.warnings ?? [] };
    } catch {
      return undefined;
    }
  }
}

export function summarizeSession(session: FrameSession): FrameSessionSummary {
  const analysis = analyzeSession(session);
  const summary = analysis.summary;
  return {
    id: session.id,
    packageName: session.packageName,
    capturedAtEpochMillis: session.capturedAtEpochMillis,
    frameCount: summary.totalFrames,
    ...(summary.deadlineMissRate !== undefined ? { deadlineMissRate: summary.deadlineMissRate } : {}),
    ...(summary.platformJankRate !== undefined ? { platformJankRate: summary.platformJankRate } : {}),
    ...(summary.worstDurationNs !== undefined ? { worstDurationNs: summary.worstDurationNs } : {}),
  };
}

function isSummary(value: unknown): value is FrameSessionSummary {
  if (value === null || typeof value !== 'object') return false;
  const record = value as Record<string, unknown>;
  return (
    typeof record['id'] === 'string' &&
    typeof record['packageName'] === 'string' &&
    typeof record['capturedAtEpochMillis'] === 'number' &&
    typeof record['frameCount'] === 'number'
  );
}
