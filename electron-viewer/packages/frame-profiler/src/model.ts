export type FrameSource = 'GFXINFO' | 'FRAME_TIMELINE';
export type ExpectedDurationSource = 'PLATFORM_DEADLINE' | 'FRAME_INTERVAL' | 'INFERRED_VSYNC' | 'UNKNOWN';

/** gfxinfo columns that carry frame-timing evidence. */
export interface FrameStages {
  readonly inputNs?: number;
  readonly animationNs?: number;
  readonly layoutMeasureNs?: number;
  readonly drawNs?: number;
  readonly syncNs?: number;
  readonly commandIssueNs?: number;
  readonly swapBuffersNs?: number;
  readonly gpuNs?: number;
}

export const FRAME_STAGE_NAMES: readonly (keyof FrameStages)[] = [
  'inputNs',
  'animationNs',
  'layoutMeasureNs',
  'drawNs',
  'syncNs',
  'commandIssueNs',
  'swapBuffersNs',
  'gpuNs',
];

export function stageEntries(stages: FrameStages): Array<[keyof FrameStages, number]> {
  const entries: Array<[keyof FrameStages, number]> = [];
  for (const name of FRAME_STAGE_NAMES) {
    const value = stages[name];
    if (value !== undefined) entries.push([name, value]);
  }
  return entries;
}

export interface FrameSample {
  readonly frameId: number;
  readonly sessionId: string;
  readonly source: FrameSource;
  readonly packageName?: string;
  readonly windowId?: string;
  readonly activityName?: string;
  readonly intendedVsyncNs?: number;
  readonly actualVsyncNs?: number;
  readonly frameCompletedNs?: number;
  readonly presentNs?: number;
  readonly expectedDurationNs?: number;
  readonly expectedDurationSource: ExpectedDurationSource;
  readonly totalDurationNs?: number;
  readonly stages: FrameStages;
  readonly eligibleForJank: boolean;
  readonly platformJank?: boolean;
  readonly platformJankTypes: readonly JankType[];
  readonly states: Readonly<Record<string, string>>;
}

export type JankType = 'PLATFORM_REPORTED';

/** Duration used for analysis: the measured total, else completion minus intended vsync. */
export function resolvedDurationNs(sample: FrameSample): number | undefined {
  if (sample.totalDurationNs !== undefined) return sample.totalDurationNs;
  return positiveDifference(sample.frameCompletedNs ?? sample.presentNs, sample.intendedVsyncNs);
}

export function positiveDifference(end: number | undefined, start: number | undefined): number | undefined {
  if (end === undefined || start === undefined) return undefined;
  if (end <= 0 || start <= 0 || end < start) return undefined;
  return end - start;
}

/**
 * gfxinfo timestamps are nanoseconds and must stay inside JavaScript's safe
 * integer range; the parser skips rows that do not.
 */
export const MAX_SAFE_FRAME_VALUE = Number.MAX_SAFE_INTEGER;
