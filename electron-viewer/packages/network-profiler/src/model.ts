export type NetworkEvidenceSource = 'OKHTTP_EVENT_LISTENER' | 'HAR_IMPORT' | 'RAW_EVENT_BUNDLE';
export type NetworkConfidence = 'EXACT' | 'DERIVED' | 'INFERRED' | 'APPROXIMATED' | 'PARTIAL' | 'UNKNOWN';
export type EvidenceCompleteness = 'COMPLETE' | 'PARTIAL' | 'UNKNOWN';
export type InstrumentationMode = 'EXPLICIT_FACTORY' | 'INSTRUMENTED_PARTIAL' | 'HAR_IMPORT' | 'RAW_IMPORT';
export type NetworkSessionStatus = 'CAPTURING' | 'COMPLETE' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
export type CallOutcome = 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INCOMPLETE';
export type CacheDisposition = 'HIT' | 'MISS' | 'CONDITIONAL_HIT' | 'UNKNOWN';
export type ConnectionUse = 'NEW' | 'REUSED' | 'UNKNOWN';
export type NetworkTimeDomain = 'DEVICE_MONOTONIC' | 'HAR_WALL_CLOCK' | 'SESSION_RELATIVE';
export type TimingAvailability = 'VALUE' | 'NOT_APPLICABLE' | 'UNAVAILABLE' | 'INVALID';
export type NetworkPhaseKind =
  | 'DISPATCHER_QUEUE'
  | 'PROXY_SELECT'
  | 'DNS'
  | 'CONNECT'
  | 'TLS'
  | 'REQUEST_HEADERS'
  | 'REQUEST_BODY'
  | 'SERVER_WAIT'
  | 'RESPONSE_HEADERS'
  | 'RESPONSE_BODY'
  | 'CONNECTION_HELD'
  | 'TOTAL';

export const NETWORK_REDACTION_POLICY_VERSION = 1;

export interface NetworkPhase {
  readonly kind: NetworkPhaseKind;
  readonly startNs?: number;
  readonly endNs?: number;
  readonly confidence: NetworkConfidence;
  readonly reportedDurationNs?: number;
  readonly availability: TimingAvailability;
  readonly parentKind?: NetworkPhaseKind;
}

export function phaseDurationNs(phase: NetworkPhase): number | undefined {
  if (phase.reportedDurationNs !== undefined) return phase.reportedDurationNs;
  if (phase.startNs === undefined || phase.endNs === undefined) return undefined;
  const duration = phase.endNs - phase.startNs;
  return duration >= 0 ? duration : undefined;
}

export interface NetworkFailure {
  readonly type: string;
  readonly message?: string;
  readonly lastReliableEvent?: string;
}

export interface TlsHandshake {
  readonly tlsVersion?: string;
  readonly cipherSuite?: string;
  readonly confidence: NetworkConfidence;
}

export interface HttpExchange {
  readonly exchangeIndex: number;
  readonly connectionId?: string;
  readonly connectionUse: ConnectionUse;
  readonly protocol?: string;
  readonly statusCode?: number;
  readonly requestBytes?: number;
  readonly responseBytes?: number;
  readonly decodedResponseBytes?: number;
  readonly phases: readonly NetworkPhase[];
  readonly cacheDisposition: CacheDisposition;
  readonly failure?: NetworkFailure;
  readonly requestHeaders: Readonly<Record<string, string>>;
  readonly responseHeaders: Readonly<Record<string, string>>;
  readonly tlsHandshake?: TlsHandshake;
  readonly sourceAttributes: Readonly<Record<string, string>>;
}

export interface HttpCall {
  readonly callId: string;
  readonly instrumentationId?: string;
  readonly method: string;
  readonly redactedUrl: string;
  readonly startedNs: number;
  readonly endedNs?: number;
  readonly exchanges: readonly HttpExchange[];
  readonly outcome: CallOutcome;
  readonly source: NetworkEvidenceSource;
}

export function callDurationNs(call: HttpCall): number | undefined {
  if (call.endedNs === undefined) return undefined;
  const duration = call.endedNs - call.startedNs;
  return duration >= 0 ? duration : undefined;
}

export interface NetworkCoverage {
  readonly processIds: readonly number[];
  readonly observedLibraries: readonly string[];
  readonly observedInstrumentationIds: readonly string[];
  readonly instrumentationMode: InstrumentationMode;
  readonly supportedEventKinds: readonly string[];
  readonly knownLimitations: readonly string[];
  readonly windowStartedNs?: number;
  readonly windowEndedNs?: number;
}

export interface NetworkEvidenceCompleteness {
  readonly status: EvidenceCompleteness;
  readonly droppedEvents: number;
  readonly sequenceGaps: number;
  readonly unpairedEvents: number;
  readonly skippedRecords: number;
}

export interface NetworkSession {
  readonly id: string;
  readonly deviceSerial?: string;
  readonly packageName?: string;
  readonly startedAtEpochMillis: number;
  readonly endedAtEpochMillis?: number;
  readonly coverage: NetworkCoverage;
  readonly completeness: NetworkEvidenceCompleteness;
  readonly sourceTimeDomain: NetworkTimeDomain;
  readonly status: NetworkSessionStatus;
  readonly redactionPolicyVersion: number;
  readonly sourceFormatVersion?: string;
  readonly sourceProducer?: string;
  readonly sourceFingerprint?: string;
  readonly warnings: readonly string[];
}

export interface NetworkCaptureResult {
  readonly session: NetworkSession;
  readonly calls: readonly HttpCall[];
}
