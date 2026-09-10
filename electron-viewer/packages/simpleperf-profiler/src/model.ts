/**
 * Port of the simpleperf profile model (profile-model/ProfileRecords.kt and
 * NormalizedProfile.kt). Execution types stay a closed union so the UI can map
 * them to colours without guessing at unknown strings.
 */
export const PROFILE_EXECUTION_TYPES = [
  'NATIVE',
  'INTERPRETED_JVM',
  'JIT_JVM',
  'ART',
  'KERNEL',
  'UNKNOWN',
] as const;

export type ProfileExecutionType = (typeof PROFILE_EXECUTION_TYPES)[number];

export const PROFILE_UNWIND_ERROR_CODES = [
  'ERROR_NONE',
  'ERROR_UNKNOWN',
  'ERROR_NOT_ENOUGH_STACK',
  'ERROR_MEMORY_INVALID',
  'ERROR_UNWIND_INFO',
  'ERROR_INVALID_MAP',
  'ERROR_MAX_FRAME_EXCEEDED',
  'ERROR_REPEATED_FRAME',
  'ERROR_INVALID_ELF',
] as const;

export type ProfileUnwindErrorCode = (typeof PROFILE_UNWIND_ERROR_CODES)[number];

export interface ProfileFrame {
  readonly virtualAddress: bigint;
  readonly fileId: number;
  readonly symbolId: number;
  readonly filePath: string;
  readonly symbolName: string;
  readonly executionType: ProfileExecutionType;
}

export interface ProfileUnwindError {
  readonly code: ProfileUnwindErrorCode;
  /** libunwindstack code, kept for diagnosis when simpleperf cannot interpret it. */
  readonly rawCode: number;
  readonly address: bigint;
}

export interface NormalizedSample {
  readonly timestampNanos: bigint;
  readonly processId: number;
  readonly threadId: number;
  readonly threadName: string;
  readonly eventType: string;
  readonly eventCount: bigint;
  readonly frames: readonly ProfileFrame[];
  readonly unwindError?: ProfileUnwindError;
}

export interface ProfileFile {
  readonly id: number;
  readonly path: string;
  readonly symbols: readonly string[];
  readonly mangledSymbols: readonly string[];
}

export interface ProfileThread {
  readonly processId: number;
  readonly threadId: number;
  readonly name: string;
}

export interface ProfileMetadata {
  readonly eventTypes: readonly string[];
  readonly appPackageName?: string;
  readonly appType?: string;
  readonly androidSdkVersion?: string;
  readonly androidBuildType?: string;
  readonly traceOffCpu: boolean;
}

export type NormalizedProfileRecord =
  | { readonly kind: 'SAMPLE'; readonly value: NormalizedSample }
  | { readonly kind: 'LOST'; readonly sampleCount: bigint; readonly lostCount: bigint }
  | { readonly kind: 'FILE'; readonly value: ProfileFile }
  | { readonly kind: 'THREAD'; readonly value: ProfileThread }
  | { readonly kind: 'METADATA'; readonly value: ProfileMetadata }
  | { readonly kind: 'CONTEXT_SWITCH'; readonly threadId: number; readonly timestampNanos: bigint; readonly switchedOnCpu: boolean }
  | { readonly kind: 'UNKNOWN' };

export interface NormalizedProfileSummary {
  readonly version: number;
  readonly recordCount: bigint;
  readonly bytesRead: bigint;
  readonly sampleCount: bigint;
  readonly lostCount: bigint;
  readonly metadata?: ProfileMetadata;
}

/** Names simpleperf uses for frames it could not resolve; kept verbatim. */
export const UNKNOWN_SYMBOL = '<unknown-symbol>';
export const UNKNOWN_PROCESS_ID = 0;
