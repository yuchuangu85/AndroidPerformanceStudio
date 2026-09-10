import {
  MAX_SAFE_FRAME_VALUE,
  positiveDifference,
  type ExpectedDurationSource,
  type FrameSample,
  type FrameSource,
  type FrameStages,
} from './model.js';

export interface FrameStatsParseResult {
  readonly frames: readonly FrameSample[];
  readonly warnings: readonly string[];
}

const KNOWN_COLUMNS = new Set([
  'Flags',
  'IntendedVsync',
  'Vsync',
  'OldestInputEvent',
  'NewestInputEvent',
  'HandleInputStart',
  'AnimationStart',
  'PerformTraversalsStart',
  'DrawStart',
  'FrameDeadline',
  'FrameInterval',
  'SyncQueued',
  'SyncStart',
  'IssueDrawCommandsStart',
  'SwapBuffers',
  'FrameCompleted',
  'GpuCompleted',
  'SwapBuffersCompleted',
  'DisplayPresentTime',
  'DequeueBufferDuration',
  'QueueBufferDuration',
]);

const MIN_REFRESH_INTERVAL_NS = 4_000_000;
const MAX_REFRESH_INTERVAL_NS = 50_000_000;
const REFRESH_INTERVAL_TOLERANCE_DIVISOR = 20;

interface ParsedNumbers {
  readonly values: Readonly<Record<string, number | undefined>>;
  readonly outOfRange: boolean;
}

function parseNumbers(header: readonly string[], columns: readonly string[]): ParsedNumbers {
  const values: Record<string, number | undefined> = {};
  let outOfRange = false;
  header.forEach((name, index) => {
    const raw = columns[index];
    if (raw === undefined || raw.length === 0) {
      values[name] = undefined;
      return;
    }
    const parsed = Number(raw);
    if (!Number.isInteger(parsed) || parsed < 0) {
      values[name] = undefined;
      return;
    }
    if (parsed > MAX_SAFE_FRAME_VALUE) {
      outOfRange = true;
      values[name] = undefined;
      return;
    }
    values[name] = parsed;
  });
  return { values, outOfRange };
}

function positive(value: number | undefined): number | undefined {
  return value !== undefined && value > 0 ? value : undefined;
}

function stagesOf(values: Readonly<Record<string, number | undefined>>): FrameStages {
  // positiveDifference(end, start); the arguments mirror GfxInfoFrameStatsParser.
  return {
    inputNs: positiveDifference(values['AnimationStart'], values['HandleInputStart']),
    animationNs: positiveDifference(values['PerformTraversalsStart'], values['AnimationStart']),
    layoutMeasureNs: positiveDifference(values['DrawStart'], values['PerformTraversalsStart']),
    drawNs: positiveDifference(values['SyncQueued'], values['DrawStart']),
    syncNs: positiveDifference(values['IssueDrawCommandsStart'], values['SyncStart']),
    commandIssueNs: positiveDifference(values['SwapBuffers'], values['IssueDrawCommandsStart']),
    swapBuffersNs: positiveDifference(values['SwapBuffersCompleted'], values['SwapBuffers']),
    gpuNs: positiveDifference(values['GpuCompleted'], values['SwapBuffers']),
  };
}

/**
 * Port of GfxInfoFrameStatsParser. Parsing is tolerant: malformed rows are
 * skipped and reported as warnings rather than failing the capture.
 */
export function parseGfxInfoFrameStats(
  text: string,
  sessionId: string,
  packageName?: string,
  source: FrameSource = 'GFXINFO',
): FrameStatsParseResult {
  const frames: FrameSample[] = [];
  const warnings: string[] = [];
  const unknownColumns = new Set<string>();
  let header: string[] | null = null;
  let windowId: string | undefined;
  let inProfileData = false;
  let foundHeader = false;
  let malformedRows = 0;
  let outOfRangeRows = 0;

  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (line.startsWith('Window:')) {
      const value = line.slice('Window:'.length).trim();
      windowId = value.length > 0 ? value : undefined;
      continue;
    }
    if (line === '---PROFILEDATA---') {
      inProfileData = !inProfileData;
      if (!inProfileData) header = null;
      continue;
    }
    if (inProfileData && line.startsWith('Flags,')) {
      header = line.split(',').map((column) => column.trim());
      foundHeader = true;
      continue;
    }
    if (!inProfileData || header === null) continue;
    if (Number.isNaN(Number(line.slice(0, line.indexOf(',')))) || line.indexOf(',') < 0) continue;

    const columns = line.split(',').map((column) => column.trim());
    if (columns.length !== header.length) {
      malformedRows += 1;
      continue;
    }
    const parsed = parseNumbers(header, columns);
    if (parsed.outOfRange) {
      outOfRangeRows += 1;
      continue;
    }
    const sample = toFrameSample(parsed.values, header, sessionId, packageName, windowId, frames.length, source);
    if (sample === undefined) {
      malformedRows += 1;
      continue;
    }
    for (const name of header) {
      // A trailing comma in the header yields a blank column name, which is not
      // an unsupported column.
      if (name.length > 0 && !KNOWN_COLUMNS.has(name)) unknownColumns.add(name);
    }
    frames.push(sample);
  }

  if (!foundHeader) warnings.push('No gfxinfo framestats header was found.');
  if (malformedRows > 0) warnings.push(malformedRows + ' malformed frame row(s) were skipped.');
  if (outOfRangeRows > 0) {
    warnings.push(outOfRangeRows + ' frame row(s) exceeded the safe integer range and were skipped.');
  }
  if (frames.length === 0 && foundHeader) warnings.push('The framestats section did not contain usable frames.');
  if (unknownColumns.size > 0) {
    warnings.push('Preserved unsupported framestats column(s): ' + [...unknownColumns].join(', ') + '.');
  }

  const withExpected = fillInferredExpectedDurations(frames);
  if (distinctIntervals(withExpected).length > 1) {
    warnings.push('Multiple frame intervals were observed; legacy frame budgets were inferred per frame.');
  }
  return { frames: withExpected, warnings };
}

function toFrameSample(
  values: Readonly<Record<string, number | undefined>>,
  header: readonly string[],
  sessionId: string,
  packageName: string | undefined,
  windowId: string | undefined,
  frameId: number,
  source: FrameSource,
): FrameSample | undefined {
  const flags = values['Flags'];
  if (flags === undefined) return undefined;
  const intendedVsync = positive(values['IntendedVsync']);
  const actualVsync = positive(values['Vsync']);
  const completed = positive(values['FrameCompleted']);
  const present = positive(values['DisplayPresentTime']);
  const deadline = positive(values['FrameDeadline']);
  const interval = positive(values['FrameInterval']);
  const deadlineBudget = positiveDifference(deadline, intendedVsync);
  const expected: { value: number; source: ExpectedDurationSource } | undefined =
    deadlineBudget !== undefined
      ? { value: deadlineBudget, source: 'PLATFORM_DEADLINE' }
      : interval !== undefined
        ? { value: interval, source: 'FRAME_INTERVAL' }
        : undefined;

  const states: Record<string, string> = {};
  for (const name of header) {
    if (name.length === 0 || KNOWN_COLUMNS.has(name)) continue;
    const value = values[name];
    if (value !== undefined) states['gfxinfo.column.' + name] = String(value);
  }
  if (flags !== 0) states['gfxinfo.flags'] = String(flags);

  return {
    frameId,
    sessionId,
    source,
    ...(packageName !== undefined ? { packageName } : {}),
    ...(windowId !== undefined ? { windowId } : {}),
    ...(intendedVsync !== undefined ? { intendedVsyncNs: intendedVsync } : {}),
    ...(actualVsync !== undefined ? { actualVsyncNs: actualVsync } : {}),
    ...(completed !== undefined ? { frameCompletedNs: completed } : {}),
    ...(present !== undefined ? { presentNs: present } : {}),
    ...(expected !== undefined
      ? { expectedDurationNs: expected.value, expectedDurationSource: expected.source }
      : { expectedDurationSource: 'UNKNOWN' as const }),
    ...(positiveDifference(completed ?? present, intendedVsync) !== undefined
      ? { totalDurationNs: positiveDifference(completed ?? present, intendedVsync) }
      : {}),
    stages: stagesOf(values),
    eligibleForJank: flags === 0,
    platformJankTypes: [],
    states,
  };
}

function validRefreshInterval(value: number): boolean {
  return value >= MIN_REFRESH_INTERVAL_NS && value <= MAX_REFRESH_INTERVAL_NS;
}

function fillInferredExpectedDurations(frames: readonly FrameSample[]): FrameSample[] {
  return frames.map((frame, index) => {
    if (frame.expectedDurationNs !== undefined) return frame;
    const current = frame.intendedVsyncNs;
    const next = frames[index + 1];
    const previous = frames[index - 1];
    const nextInterval =
      next !== undefined && next.windowId === frame.windowId
        ? positiveDifference(next.intendedVsyncNs, current)
        : undefined;
    const previousInterval =
      previous !== undefined && previous.windowId === frame.windowId
        ? positiveDifference(current, previous.intendedVsyncNs)
        : undefined;
    const interval =
      nextInterval !== undefined && validRefreshInterval(nextInterval)
        ? nextInterval
        : previousInterval !== undefined && validRefreshInterval(previousInterval)
          ? previousInterval
          : undefined;
    if (interval === undefined) return frame;
    return { ...frame, expectedDurationNs: interval, expectedDurationSource: 'INFERRED_VSYNC' };
  });
}

function distinctIntervals(frames: readonly FrameSample[]): number[] {
  const groups: number[] = [];
  for (const value of frames
    .map((frame) => frame.expectedDurationNs)
    .filter((entry): entry is number => entry !== undefined)
    .sort((left, right) => left - right)) {
    if (!groups.some((existing) => Math.abs(existing - value) <= existing / REFRESH_INTERVAL_TOLERANCE_DIVISOR)) {
      groups.push(value);
    }
  }
  return groups;
}
