export type StartupType = 'COLD' | 'WARM' | 'HOT' | 'UNKNOWN';
export type StartupSource = 'AM_START' | 'AGENT' | 'EVENT_LOG' | 'PERFETTO';
export type EvidenceConfidence = 'EXACT' | 'ESTIMATED' | 'INFERRED' | 'UNAVAILABLE';

/** CompilationMode.commandValue; CURRENT means "leave the device state alone". */
export const COMPILATION_MODES = {
  CURRENT: null,
  RESET: 'reset',
  VERIFY: 'verify',
  SPEED_PROFILE: 'speed-profile',
  SPEED: 'speed',
} as const;

export type CompilationMode = keyof typeof COMPILATION_MODES;

export type StartupMilestoneKind =
  | 'PROCESS_START'
  | 'INITIALIZER_ENTER'
  | 'AGENT_READY'
  | 'ACTIVITY_PRE_CREATE'
  | 'ACTIVITY_CREATED'
  | 'ACTIVITY_STARTED'
  | 'ACTIVITY_RESUMED'
  | 'FIRST_FRAME'
  | 'FIRST_DRAW_CALLBACK'
  | 'FULLY_DRAWN';

export interface PlatformLaunchMetrics {
  readonly status?: string;
  readonly launchState?: string;
  readonly activity?: string;
  readonly thisTimeMs?: number;
  readonly totalTimeMs?: number;
  readonly waitTimeMs?: number;
  readonly displayedTimeMs?: number;
  readonly fullyDrawnTimeMs?: number;
  readonly complete: boolean;
}

export interface MetricEvidence {
  readonly source?: StartupSource;
  readonly confidence: EvidenceConfidence;
  readonly unavailableReason?: string;
}

export const UNAVAILABLE_EVIDENCE: MetricEvidence = { confidence: 'UNAVAILABLE' };

export interface StartupRun {
  readonly id: string;
  readonly sessionId: string;
  readonly iteration: number;
  /** False for warmup runs, which prime the mode but never enter statistics. */
  readonly measured: boolean;
  readonly requestedType: StartupType;
  readonly observedType: StartupType;
  readonly platform: PlatformLaunchMetrics;
  readonly warnings: readonly string[];
  readonly amStartOutput: string;
  readonly eventLogOutput?: string;
  /** TTID is the platform-completed first-frame metric, never an estimate. */
  readonly ttidEvidence: MetricEvidence;
  /** TTFD stays missing when reportFullyDrawn was never called. */
  readonly ttfdEvidence: MetricEvidence;
}

/**
 * Observed startup mode comes from platform evidence only. A missing or
 * unrecognised LaunchState must stay UNKNOWN rather than being assumed.
 */
export function observedStartupType(launchState: string | undefined): StartupType {
  const normalized = launchState?.trim().toUpperCase();
  if (normalized === 'COLD') return 'COLD';
  if (normalized === 'WARM') return 'WARM';
  if (normalized === 'HOT') return 'HOT';
  return 'UNKNOWN';
}

export function evidence(source: StartupSource, confidence: EvidenceConfidence): MetricEvidence {
  return { source, confidence };
}
