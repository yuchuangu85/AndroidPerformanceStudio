import type { AnalysisReport, Finding, Severity } from './analysis.js';

/** Kotlin's serialized `report/ai-analysis-report.json` contract. */
export interface ArchivedAiAnalysisReport {
  readonly model: string;
  readonly summary: string;
  readonly findings: readonly ArchivedAiFinding[];
  readonly provenance?: ArchivedAiAnalysisProvenance;
}

export interface ArchivedAiFinding {
  readonly ruleId: string;
  readonly severity: Severity;
  readonly nodeId: string;
  readonly title: string;
  readonly message: string;
  readonly recommendation: string;
  readonly confidence: number;
  readonly performanceEvidenceIds: readonly string[];
  readonly sourceCandidateIds: readonly string[];
}

export interface ArchivedAiAnalysisProvenance {
  readonly sessionId: string;
  readonly provider: string;
  readonly scope: string;
  readonly promptVersion: string;
  readonly payloadPolicyVersion: string;
  readonly sourceSnapshotIds: readonly string[];
  readonly buildEvidenceBundleIds: readonly string[];
  readonly evidence: readonly ArchivedAiEvidenceReference[];
  readonly sourceCandidates: readonly ArchivedAiSourceCandidateReference[];
}

export interface ArchivedAiEvidenceReference {
  readonly id: string;
  readonly kind: string;
  readonly summary: string;
  readonly payloadHash: string;
}

export interface ArchivedAiSourceCandidateReference {
  readonly id: string;
  readonly relativePath: string;
  readonly startLine?: number;
  readonly endLine?: number;
  readonly resolutionConfidence: string;
  readonly contentHash?: string;
  readonly workspaceId?: string;
  readonly snapshotId?: string;
  readonly providerKind?: string;
  readonly repositoryIdentity?: string;
  readonly revision?: string;
}

/** Kotlin's serialized `timeline/history.json` contract. */
export interface ArchivedTimelineHistory {
  readonly frames: readonly ArchivedTimelineFrame[];
}

export interface ArchivedTimelineFrame {
  readonly index: number;
  readonly capturedAtEpochMillis: number;
  readonly diffFromPrevious?: ArchivedTimelineDiff;
}

export interface ArchivedTimelineDiff {
  readonly previousCapturedAtEpochMillis: number;
  readonly currentCapturedAtEpochMillis: number;
  readonly addedNodes: number;
  readonly removedNodes: number;
  readonly boundsChangedNodes: number;
  readonly changes: readonly ArchivedTimelineNodeChange[];
}

export interface ArchivedTimelineNodeChange {
  readonly type: 'ADDED' | 'REMOVED' | 'CHANGED';
  readonly windowId: string;
  readonly nodeId: string;
  readonly nodeKey: string;
  readonly className: string;
  readonly changedProperties: readonly string[];
}

/**
 * Decode the Kotlin archive payloads before persisting them or presenting them.
 * Their source JSON remains untouched for a lossless future export; these
 * decoders solely enforce the interchange contract at Electron's trust edge.
 */
export function parseArchivedAnalysisReport(encoded: string): AnalysisReport {
  const value = parseObject(encoded, 'analysis report');
  const metrics = objectField(value, 'metrics', 'analysis report');
  const findings = arrayField(value, 'findings', 'analysis report').map((entry, index) => {
    const finding = objectValue(entry, `analysis report findings[${index}]`);
    return {
      ruleId: stringField(finding, 'ruleId', 'analysis finding'),
      severity: severityField(finding, 'severity', 'analysis finding'),
      nodeId: stringField(finding, 'nodeId', 'analysis finding'),
      message: stringField(finding, 'message', 'analysis finding'),
      arguments: stringRecord(optionalField(finding, 'arguments'), 'analysis finding arguments'),
    } satisfies Finding;
  });
  return {
    metrics: {
      nodeCount: integerField(metrics, 'nodeCount', 'analysis metrics'),
      maxDepth: integerField(metrics, 'maxDepth', 'analysis metrics'),
      widestLevel: integerField(metrics, 'widestLevel', 'analysis metrics'),
    },
    findings,
  };
}

export function parseArchivedAiAnalysisReport(encoded: string): ArchivedAiAnalysisReport {
  const value = parseObject(encoded, 'AI analysis report');
  return {
    model: stringField(value, 'model', 'AI analysis report'),
    summary: stringField(value, 'summary', 'AI analysis report'),
    findings: optionalArrayField(value, 'findings', 'AI analysis report').map((entry, index) => parseAiFinding(entry, index)),
    // Kotlin's nullable provenance field is emitted as `null` when archive JSON
    // uses encodeDefaults=true; both a missing and null provenance mean no
    // provenance was captured, rather than a malformed archive.
    ...(optionalField(value, 'provenance') === undefined || optionalField(value, 'provenance') === null
      ? {}
      : { provenance: parseAiProvenance(optionalField(value, 'provenance')) }),
  };
}

export function parseArchivedTimelineHistory(encoded: string): ArchivedTimelineHistory {
  const value = parseObject(encoded, 'timeline history');
  return {
    frames: arrayField(value, 'frames', 'timeline history').map((entry, index) => {
      const frame = objectValue(entry, `timeline frames[${index}]`);
      const diff = optionalField(frame, 'diffFromPrevious');
      return {
        index: integerField(frame, 'index', 'timeline frame'),
        capturedAtEpochMillis: integerField(frame, 'capturedAtEpochMillis', 'timeline frame'),
        ...(diff === undefined || diff === null ? {} : { diffFromPrevious: parseTimelineDiff(diff, index) }),
      };
    }),
  };
}

function parseAiFinding(value: unknown, index: number): ArchivedAiFinding {
  const finding = objectValue(value, `AI analysis findings[${index}]`);
  return {
    ruleId: stringField(finding, 'ruleId', 'AI finding'),
    severity: severityField(finding, 'severity', 'AI finding'),
    nodeId: stringField(finding, 'nodeId', 'AI finding'),
    title: stringField(finding, 'title', 'AI finding'),
    message: stringField(finding, 'message', 'AI finding'),
    recommendation: stringField(finding, 'recommendation', 'AI finding'),
    confidence: numberField(finding, 'confidence', 'AI finding'),
    performanceEvidenceIds: optionalStringArrayField(finding, 'performanceEvidenceIds', 'AI finding'),
    sourceCandidateIds: optionalStringArrayField(finding, 'sourceCandidateIds', 'AI finding'),
  };
}

function parseAiProvenance(value: unknown): ArchivedAiAnalysisProvenance {
  const provenance = objectValue(value, 'AI analysis provenance');
  return {
    sessionId: stringField(provenance, 'sessionId', 'AI analysis provenance'),
    provider: stringField(provenance, 'provider', 'AI analysis provenance'),
    scope: stringField(provenance, 'scope', 'AI analysis provenance'),
    promptVersion: stringField(provenance, 'promptVersion', 'AI analysis provenance'),
    payloadPolicyVersion: stringField(provenance, 'payloadPolicyVersion', 'AI analysis provenance'),
    sourceSnapshotIds: optionalStringArrayField(provenance, 'sourceSnapshotIds', 'AI analysis provenance'),
    buildEvidenceBundleIds: optionalStringArrayField(provenance, 'buildEvidenceBundleIds', 'AI analysis provenance'),
    evidence: optionalArrayField(provenance, 'evidence', 'AI analysis provenance').map((entry, index) => {
      const evidence = objectValue(entry, `AI analysis evidence[${index}]`);
      return {
        id: stringField(evidence, 'id', 'AI evidence'),
        kind: stringField(evidence, 'kind', 'AI evidence'),
        summary: stringField(evidence, 'summary', 'AI evidence'),
        payloadHash: stringField(evidence, 'payloadHash', 'AI evidence'),
      };
    }),
    sourceCandidates: optionalArrayField(provenance, 'sourceCandidates', 'AI analysis provenance').map((entry, index) => {
      const candidate = objectValue(entry, `AI analysis sourceCandidates[${index}]`);
      return {
        id: stringField(candidate, 'id', 'AI source candidate'),
        relativePath: stringField(candidate, 'relativePath', 'AI source candidate'),
        ...(nullableIntegerField(candidate, 'startLine', 'AI source candidate') === undefined ? {} : { startLine: nullableIntegerField(candidate, 'startLine', 'AI source candidate') }),
        ...(nullableIntegerField(candidate, 'endLine', 'AI source candidate') === undefined ? {} : { endLine: nullableIntegerField(candidate, 'endLine', 'AI source candidate') }),
        resolutionConfidence: stringField(candidate, 'resolutionConfidence', 'AI source candidate'),
        ...(nullableStringField(candidate, 'contentHash', 'AI source candidate') === undefined ? {} : { contentHash: nullableStringField(candidate, 'contentHash', 'AI source candidate') }),
        ...(nullableStringField(candidate, 'workspaceId', 'AI source candidate') === undefined ? {} : { workspaceId: nullableStringField(candidate, 'workspaceId', 'AI source candidate') }),
        ...(nullableStringField(candidate, 'snapshotId', 'AI source candidate') === undefined ? {} : { snapshotId: nullableStringField(candidate, 'snapshotId', 'AI source candidate') }),
        ...(nullableStringField(candidate, 'providerKind', 'AI source candidate') === undefined ? { } : { providerKind: nullableStringField(candidate, 'providerKind', 'AI source candidate') }),
        ...(nullableStringField(candidate, 'repositoryIdentity', 'AI source candidate') === undefined ? {} : { repositoryIdentity: nullableStringField(candidate, 'repositoryIdentity', 'AI source candidate') }),
        ...(nullableStringField(candidate, 'revision', 'AI source candidate') === undefined ? {} : { revision: nullableStringField(candidate, 'revision', 'AI source candidate') }),
      };
    }),
  };
}

function parseTimelineDiff(value: unknown, frameIndex: number): ArchivedTimelineDiff {
  const diff = objectValue(value, `timeline frames[${frameIndex}].diffFromPrevious`);
  return {
    previousCapturedAtEpochMillis: integerField(diff, 'previousCapturedAtEpochMillis', 'timeline diff'),
    currentCapturedAtEpochMillis: integerField(diff, 'currentCapturedAtEpochMillis', 'timeline diff'),
    addedNodes: integerField(diff, 'addedNodes', 'timeline diff'),
    removedNodes: integerField(diff, 'removedNodes', 'timeline diff'),
    boundsChangedNodes: integerField(diff, 'boundsChangedNodes', 'timeline diff'),
    changes: optionalArrayField(diff, 'changes', 'timeline diff').map((entry, index) => {
      const change = objectValue(entry, `timeline changes[${index}]`);
      const type = stringField(change, 'type', 'timeline change');
      if (type !== 'ADDED' && type !== 'REMOVED' && type !== 'CHANGED') fail('timeline change type is invalid');
      return {
        type,
        windowId: stringField(change, 'windowId', 'timeline change'),
        nodeId: stringField(change, 'nodeId', 'timeline change'),
        nodeKey: stringField(change, 'nodeKey', 'timeline change'),
        className: stringField(change, 'className', 'timeline change'),
        changedProperties: optionalStringArrayField(change, 'changedProperties', 'timeline change'),
      };
    }),
  };
}

function parseObject(encoded: string, context: string): Record<string, unknown> {
  try {
    return objectValue(JSON.parse(encoded), context);
  } catch (error) {
    if (error instanceof CaptureArchivePayloadError) throw error;
    throw new CaptureArchivePayloadError(`${context} is not valid JSON`);
  }
}

function objectField(value: Record<string, unknown>, key: string, context: string): Record<string, unknown> {
  return objectValue(value[key], `${context}.${key}`);
}

function objectValue(value: unknown, context: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail(`${context} must be an object`);
  return value as Record<string, unknown>;
}

function arrayField(value: Record<string, unknown>, key: string, context: string): unknown[] {
  const field = value[key];
  if (!Array.isArray(field)) fail(`${context}.${key} must be an array`);
  return field;
}

function optionalArrayField(value: Record<string, unknown>, key: string, context: string): unknown[] {
  const field = optionalField(value, key);
  if (field === undefined) return [];
  if (!Array.isArray(field)) fail(`${context}.${key} must be an array`);
  return field;
}

function stringField(value: Record<string, unknown>, key: string, context: string): string {
  const field = value[key];
  if (typeof field !== 'string') fail(`${context}.${key} must be a string`);
  return field;
}

function nullableStringField(value: Record<string, unknown>, key: string, context: string): string | undefined {
  const field = optionalField(value, key);
  if (field === undefined || field === null) return undefined;
  if (typeof field !== 'string') fail(`${context}.${key} must be a string or null`);
  return field;
}

function numberField(value: Record<string, unknown>, key: string, context: string): number {
  const field = value[key];
  if (typeof field !== 'number' || !Number.isFinite(field)) fail(`${context}.${key} must be a finite number`);
  return field;
}

function integerField(value: Record<string, unknown>, key: string, context: string): number {
  const field = numberField(value, key, context);
  if (!Number.isSafeInteger(field)) fail(`${context}.${key} must be a safe integer`);
  return field;
}

function nullableIntegerField(value: Record<string, unknown>, key: string, context: string): number | undefined {
  const field = optionalField(value, key);
  if (field === undefined || field === null) return undefined;
  if (typeof field !== 'number' || !Number.isSafeInteger(field)) fail(`${context}.${key} must be an integer or null`);
  return field;
}

function severityField(value: Record<string, unknown>, key: string, context: string): Severity {
  const field = stringField(value, key, context);
  if (field !== 'INFO' && field !== 'WARNING' && field !== 'ERROR') fail(`${context}.${key} is invalid`);
  return field;
}

function optionalStringArrayField(value: Record<string, unknown>, key: string, context: string): readonly string[] {
  return optionalArrayField(value, key, context).map((entry, index) => {
    if (typeof entry !== 'string') fail(`${context}.${key}[${index}] must be a string`);
    return entry;
  });
}

function stringRecord(value: unknown, context: string): Readonly<Record<string, string>> {
  if (value === undefined) return {};
  const record = objectValue(value, context);
  for (const [key, entry] of Object.entries(record)) {
    if (typeof entry !== 'string') fail(`${context}.${key} must be a string`);
  }
  return record as Readonly<Record<string, string>>;
}

function optionalField(value: Record<string, unknown>, key: string): unknown {
  return value[key];
}

function fail(message: string): never {
  throw new CaptureArchivePayloadError(message);
}

export class CaptureArchivePayloadError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CaptureArchivePayloadError';
  }
}
