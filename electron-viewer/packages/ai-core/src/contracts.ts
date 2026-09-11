/**
 * Port of ai-core AnalysisContracts.kt.
 *
 * The AI review is evidence bound: a finding may only cite performance evidence
 * and source candidates the request actually contained, which is what keeps a
 * model from inventing a file path or a line number.
 */
export type AnalysisSessionId = string;
export type AnalysisFindingId = string;

/** A random session id; the Kotlin app uses UUID.randomUUID().toString(). */
export function createAnalysisSessionId(): AnalysisSessionId {
  return globalThis.crypto.randomUUID();
}

export const PROFILER_KINDS = ['LAYOUT_INSPECTOR', 'SIMPLEPERF'] as const;
export type ProfilerKind = (typeof PROFILER_KINDS)[number];

export const ANALYSIS_SCOPE_KINDS = ['CURRENT_SELECTION', 'REPORT_SUMMARY'] as const;
export type AnalysisScopeKind = (typeof ANALYSIS_SCOPE_KINDS)[number];

export interface AnalysisScope {
  readonly kind: AnalysisScopeKind;
  readonly description: string;
}

export interface PerformanceEvidence {
  readonly id: string;
  readonly kind: string;
  readonly summary: string;
  /** JSON text; only its SHA-256 is persisted, never the body. */
  readonly structuredPayload: string;
}

export interface AiSourceCandidate {
  readonly id: string;
  readonly relativePath: string;
  readonly symbol?: string | null;
  readonly resolutionConfidence: string;
  readonly reasons: readonly string[];
  readonly sourceSnippet?: string | null;
  readonly startLine?: number | null;
  readonly endLine?: number | null;
  readonly contentHash?: string | null;
  readonly indexVersion?: number | null;
  readonly indexComplete?: boolean | null;
}

export interface AnalysisRequest {
  readonly sessionId: AnalysisSessionId;
  readonly originProfiler: ProfilerKind;
  readonly scope: AnalysisScope;
  readonly evidence: readonly PerformanceEvidence[];
  readonly sourceCandidates: readonly AiSourceCandidate[];
  readonly promptVersion: string;
  readonly payloadPolicyVersion: string;
}

export const ANALYSIS_SEVERITIES = ['INFO', 'WARNING', 'ERROR'] as const;
export type AnalysisSeverity = (typeof ANALYSIS_SEVERITIES)[number];

export interface AnalysisFinding {
  readonly id: AnalysisFindingId;
  readonly severity: AnalysisSeverity;
  readonly title: string;
  readonly explanation: string;
  readonly recommendation: string;
  readonly analysisConfidence: number;
  readonly performanceEvidenceIds: readonly string[];
  readonly sourceCandidateIds: readonly string[];
}

export interface AnalysisResult {
  readonly sessionId: AnalysisSessionId;
  readonly model: string;
  readonly summary: string;
  readonly findings: readonly AnalysisFinding[];
}

export const ANALYSIS_SESSION_STATUSES = ['RUNNING', 'SUCCEEDED', 'CANCELLED', 'FAILED'] as const;
export type AnalysisSessionStatus = (typeof ANALYSIS_SESSION_STATUSES)[number];

export interface AnalysisSession {
  readonly id: AnalysisSessionId;
  readonly originProfiler: ProfilerKind;
  readonly scope: AnalysisScope;
  readonly model: string | null;
  readonly promptVersion: string;
  readonly payloadPolicyVersion: string;
  readonly sourceSnapshotIds: readonly string[];
  readonly buildEvidenceBundleIds: readonly string[];
  readonly status: AnalysisSessionStatus;
  /** ISO-8601, stored verbatim so rows written by the Kotlin app round trip. */
  readonly createdAt: string;
  readonly parentSessionId?: AnalysisSessionId | null;
  readonly summary?: string | null;
  readonly errorMessage?: string | null;
  readonly provider?: string | null;
}

export interface AiAnalysisGateway {
  analyze(request: AnalysisRequest): Promise<AnalysisResult>;
}
