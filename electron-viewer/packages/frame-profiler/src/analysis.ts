import { resolvedDurationNs, stageEntries, type FrameSample, type JankType } from './model.js';

export type FrameDeadlineVerdict = 'MET' | 'MISSED' | 'UNKNOWN';
export type JankSeverity = 'SMOOTH' | 'MINOR' | 'MAJOR' | 'SEVERE' | 'FROZEN' | 'UNKNOWN';

const FROZEN_FRAME_NS = 700_000_000;
const DEFAULT_MAX_SMOOTH_GAP = 2;

export interface AnalyzedFrame {
  readonly sample: FrameSample;
  readonly deadlineVerdict: FrameDeadlineVerdict;
  readonly severity: JankSeverity;
  readonly missedVsyncCount?: number;
  readonly platformJankTypes: readonly JankType[];
  readonly largestReportedStage?: string;
}

export interface FrameSummary {
  readonly totalFrames: number;
  readonly deadlineClassifiedFrames: number;
  readonly deadlineMissFrames: number;
  readonly deadlineUnknownFrames: number;
  readonly deadlineMissRate?: number;
  readonly platformClassifiedFrames: number;
  readonly platformJankFrames: number;
  readonly platformUnknownFrames: number;
  readonly platformJankRate?: number;
  readonly p50DurationNs?: number;
  readonly p95DurationNs?: number;
  readonly p99DurationNs?: number;
  readonly worstDurationNs?: number;
}

export interface DeadlineMissCluster {
  readonly id: number;
  readonly firstFrameId: number;
  readonly lastFrameId: number;
  readonly deadlineMissFrameIds: readonly number[];
  readonly durationNs: number;
  readonly worstSeverity: JankSeverity;
  readonly windowId?: string;
  readonly dominantReportedStage?: string;
}

export interface FrameAnalysisResult {
  readonly frames: readonly AnalyzedFrame[];
  readonly summary: FrameSummary;
  readonly clusters: readonly DeadlineMissCluster[];
}

function severityFor(
  verdict: FrameDeadlineVerdict,
  durationNs: number | undefined,
  expectedNs: number | undefined,
  missedVsyncCount: number | undefined,
): JankSeverity {
  if (verdict === 'UNKNOWN') return 'UNKNOWN';
  if (verdict === 'MET') return 'SMOOTH';
  if (durationNs !== undefined && durationNs >= FROZEN_FRAME_NS) return 'FROZEN';
  if (expectedNs === undefined || missedVsyncCount === undefined || missedVsyncCount <= 1) return 'MINOR';
  if (missedVsyncCount <= 3) return 'MAJOR';
  return 'SEVERE';
}

/**
 * Port of FrameJankAnalyzer. A frame-deadline miss and a platform jank signal
 * stay separate (ADR 0028): the summary reports both rates independently.
 */
export function analyzeFrame(sample: FrameSample): AnalyzedFrame {
  if (!sample.eligibleForJank) {
    return {
      sample,
      deadlineVerdict: 'UNKNOWN',
      severity: 'UNKNOWN',
      platformJankTypes: sample.platformJankTypes,
    };
  }
  const duration = resolvedDurationNs(sample);
  const expected = sample.expectedDurationNs !== undefined && sample.expectedDurationNs > 0 ? sample.expectedDurationNs : undefined;
  const verdict: FrameDeadlineVerdict =
    duration !== undefined && expected !== undefined ? (duration > expected ? 'MISSED' : 'MET') : 'UNKNOWN';
  const missedVsyncCount =
    duration !== undefined && expected !== undefined
      ? Math.max(0, Math.ceil(duration / expected) - 1)
      : undefined;
  const largest = stageEntries(sample.stages).reduce<[string, number] | undefined>((best, [name, value]) => {
    if (best === undefined || value > best[1]) return [String(name), value];
    return best;
  }, undefined);
  return {
    sample,
    deadlineVerdict: verdict,
    severity: severityFor(verdict, duration, expected, missedVsyncCount),
    ...(missedVsyncCount !== undefined ? { missedVsyncCount } : {}),
    platformJankTypes:
      sample.platformJank === true && !sample.platformJankTypes.includes('PLATFORM_REPORTED')
        ? [...sample.platformJankTypes, 'PLATFORM_REPORTED']
        : sample.platformJankTypes,
    ...(largest !== undefined ? { largestReportedStage: largest[0] } : {}),
  };
}

function percentile(sorted: readonly number[], fraction: number): number | undefined {
  if (sorted.length === 0) return undefined;
  const index = Math.min(Math.max(Math.ceil(sorted.length * fraction) - 1, 0), sorted.length - 1);
  return sorted[index];
}

function rate(numerator: number, denominator: number): number | undefined {
  return denominator > 0 ? numerator / denominator : undefined;
}

export function summarize(frames: readonly AnalyzedFrame[]): FrameSummary {
  const durations = frames
    .map((frame) => resolvedDurationNs(frame.sample))
    .filter((value): value is number => value !== undefined)
    .sort((left, right) => left - right);
  const deadlineClassified = frames.filter((frame) => frame.deadlineVerdict !== 'UNKNOWN').length;
  const deadlineMisses = frames.filter((frame) => frame.deadlineVerdict === 'MISSED').length;
  const platformClassified = frames.filter((frame) => frame.sample.platformJank !== undefined).length;
  const platformJank = frames.filter((frame) => frame.sample.platformJank === true).length;
  const missRate = rate(deadlineMisses, deadlineClassified);
  const jankRate = rate(platformJank, platformClassified);
  const p50 = percentile(durations, 0.5);
  const p95 = percentile(durations, 0.95);
  const p99 = percentile(durations, 0.99);
  const worst = durations.at(-1);
  return {
    totalFrames: frames.length,
    deadlineClassifiedFrames: deadlineClassified,
    deadlineMissFrames: deadlineMisses,
    deadlineUnknownFrames: frames.length - deadlineClassified,
    ...(missRate !== undefined ? { deadlineMissRate: missRate } : {}),
    platformClassifiedFrames: platformClassified,
    platformJankFrames: platformJank,
    platformUnknownFrames: frames.length - platformClassified,
    ...(jankRate !== undefined ? { platformJankRate: jankRate } : {}),
    ...(p50 !== undefined ? { p50DurationNs: p50 } : {}),
    ...(p95 !== undefined ? { p95DurationNs: p95 } : {}),
    ...(p99 !== undefined ? { p99DurationNs: p99 } : {}),
    ...(worst !== undefined ? { worstDurationNs: worst } : {}),
  };
}

const SEVERITY_ORDER: readonly JankSeverity[] = ['UNKNOWN', 'SMOOTH', 'MINOR', 'MAJOR', 'SEVERE', 'FROZEN'];

export function clusterDeadlineMisses(
  frames: readonly AnalyzedFrame[],
  maxSmoothGap: number = DEFAULT_MAX_SMOOTH_GAP,
): DeadlineMissCluster[] {
  const clusters: DeadlineMissCluster[] = [];
  let cursor = 0;
  while (cursor < frames.length) {
    const firstIndex = frames.findIndex((frame, index) => index >= cursor && frame.deadlineVerdict === 'MISSED');
    if (firstIndex === -1) break;
    const first = frames[firstIndex] as AnalyzedFrame;
    const jankFrames: AnalyzedFrame[] = [first];
    let scan = firstIndex + 1;
    let smoothGap = 0;
    while (scan < frames.length) {
      const candidate = frames[scan] as AnalyzedFrame;
      const sameContext =
        candidate.sample.windowId === first.sample.windowId &&
        candidate.sample.activityName === first.sample.activityName;
      if (!sameContext || candidate.deadlineVerdict === 'UNKNOWN') break;
      if (candidate.deadlineVerdict === 'MISSED') {
        jankFrames.push(candidate);
        smoothGap = 0;
      } else {
        smoothGap += 1;
        if (smoothGap > maxSmoothGap) break;
      }
      scan += 1;
    }
    const last = jankFrames.at(-1) as AnalyzedFrame;
    const start = first.sample.intendedVsyncNs;
    const end = last.sample.presentNs ?? last.sample.frameCompletedNs;
    const stageCounts = new Map<string, number>();
    for (const frame of jankFrames) {
      if (frame.largestReportedStage === undefined) continue;
      stageCounts.set(frame.largestReportedStage, (stageCounts.get(frame.largestReportedStage) ?? 0) + 1);
    }
    const dominant = [...stageCounts.entries()].sort((left, right) => right[1] - left[1])[0]?.[0];
    clusters.push({
      id: clusters.length,
      firstFrameId: first.sample.frameId,
      lastFrameId: last.sample.frameId,
      deadlineMissFrameIds: jankFrames.map((frame) => frame.sample.frameId),
      durationNs: start !== undefined && end !== undefined && end >= start ? end - start : (resolvedDurationNs(last.sample) ?? 0),
      worstSeverity: jankFrames.reduce<JankSeverity>(
        (worst, frame) => (SEVERITY_ORDER.indexOf(frame.severity) > SEVERITY_ORDER.indexOf(worst) ? frame.severity : worst),
        'UNKNOWN',
      ),
      ...(first.sample.windowId !== undefined ? { windowId: first.sample.windowId } : {}),
      ...(dominant !== undefined ? { dominantReportedStage: dominant } : {}),
    });
    const lastIndex = frames.lastIndexOf(last);
    cursor = lastIndex + 1;
  }
  return clusters;
}

export function analyzeFrames(
  samples: readonly FrameSample[],
  maxSmoothGap: number = DEFAULT_MAX_SMOOTH_GAP,
): FrameAnalysisResult {
  const analyzed = [...samples]
    .sort((left, right) => (left.intendedVsyncNs ?? left.frameId) - (right.intendedVsyncNs ?? right.frameId))
    .map(analyzeFrame);
  return { frames: analyzed, summary: summarize(analyzed), clusters: clusterDeadlineMisses(analyzed, maxSmoothGap) };
}
