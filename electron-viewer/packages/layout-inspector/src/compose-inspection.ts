/**
 * Port of the Compose inspection model and redaction
 * (ComposeInspectionModels.kt, ComposeInspectionRedaction.kt).
 *
 * This is the gate every Compose archive passes before it is written: a safe
 * archive keeps structure only, and even a full fidelity archive never retains a
 * credential, a bearer token, a JWT, or a private device path. Bounds mirror
 * protocol.Bounds (left, top, right, bottom); the protocol adapter maps between
 * them once it is ported.
 */
export const COMPOSE_INSPECTION_SCHEMA_VERSION = 1;

export const COMPOSE_INSPECTION_MODES = ['FULL', 'SEMANTICS_ONLY'] as const;
export type ComposeInspectionMode = (typeof COMPOSE_INSPECTION_MODES)[number];

export const COMPOSE_CAPABILITIES = [
  'FULL_TREE',
  'PARAMETERS',
  'MODIFIERS',
  'MERGED_SEMANTICS',
  'UNMERGED_SEMANTICS',
  'SOURCE_LOCATION',
  'RECOMPOSITION_COUNTS',
  'SKIP_COUNTS',
  'STATE_READS',
] as const;
export type ComposeCapability = (typeof COMPOSE_CAPABILITIES)[number];

export const CAPABILITY_AVAILABILITIES = ['AVAILABLE', 'UNAVAILABLE', 'NOT_REQUESTED'] as const;
export type CapabilityAvailability = (typeof CAPABILITY_AVAILABILITIES)[number];

export interface ComposeCapabilityState {
  readonly capability: ComposeCapability;
  readonly availability: CapabilityAvailability;
  readonly reason?: string | null;
}

export const COMPOSE_FRAME_COMPLETENESS = ['COMPLETE', 'INCOMPLETE_RESOURCE_LIMIT', 'INCOMPLETE_CAPTURE_ERROR'] as const;
export type ComposeFrameCompleteness = (typeof COMPOSE_FRAME_COMPLETENESS)[number];

export const COMPOSE_DETAIL_COVERAGE_STATES = ['COLLECTED', 'NOT_COLLECTED', 'TRUNCATED', 'FAILED'] as const;
export type ComposeDetailCoverageState = (typeof COMPOSE_DETAIL_COVERAGE_STATES)[number];

export const COMPOSE_ARCHIVE_PRIVACY = ['SAFE_REDACTED', 'FULL_FIDELITY'] as const;
export type ComposeArchivePrivacy = (typeof COMPOSE_ARCHIVE_PRIVACY)[number];

export interface ComposeBounds {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

export interface ComposeInspectorArtifact {
  readonly group: string;
  readonly artifact: string;
  readonly version: string;
  readonly sha256: string;
  readonly source: string;
  readonly certified: boolean;
}

export interface ComposeSourceLocation {
  readonly packageHash: number;
  readonly fileName: string;
  readonly lineNumber: number;
  readonly offset: number;
}

export interface ComposeParameterReference {
  readonly composableId: number;
  readonly parameterIndex: number;
  readonly compositeIndex: readonly number[];
  readonly kind: string;
  readonly anchorHash: number;
}

export interface ComposeValue {
  readonly name: string;
  readonly type: string;
  readonly value?: string | null;
  readonly elements: readonly ComposeValue[];
  readonly reference?: ComposeParameterReference | null;
  readonly originalSize?: number | null;
  readonly truncated: boolean;
}

export interface ComposableNode {
  readonly id: number;
  readonly anchorHash: number;
  readonly name: string;
  readonly bounds: ComposeBounds;
  readonly hostedViewId?: number | null;
  readonly source?: ComposeSourceLocation | null;
  readonly systemCreated: boolean;
  readonly flags: readonly string[];
  readonly recomposeCount?: number | null;
  readonly skipCount?: number | null;
  readonly children: readonly ComposableNode[];
}

export interface ComposableRoot {
  readonly viewId: number;
  readonly nodes: readonly ComposableNode[];
  readonly viewsToSkip: readonly number[];
}

export interface ComposableDetail {
  readonly nodeId: number;
  readonly anchorHash: number;
  readonly parameters: readonly ComposeValue[];
  readonly modifiers: readonly ComposeValue[];
  readonly mergedSemantics: readonly ComposeValue[];
  readonly unmergedSemantics: readonly ComposeValue[];
}

export interface ComposeDetailCoverage {
  readonly nodeId: number;
  readonly field: string;
  readonly state: ComposeDetailCoverageState;
  readonly recursionDepth: number;
  readonly loadedElements: number;
  readonly totalElements?: number | null;
  readonly reason?: string | null;
}

export interface ComposeTruncation {
  readonly nodeId?: number | null;
  readonly field: string;
  readonly reason: string;
  readonly originalSize?: number | null;
  readonly retainedSize?: number | null;
}

export interface RecompositionObservation {
  readonly startedAtEpochMillis: number;
  readonly stoppedAtEpochMillis?: number | null;
  readonly active: boolean;
  readonly continuous: boolean;
}

export interface ComposeInspectionFrame {
  readonly frameId: string;
  readonly generation: number;
  readonly mode: ComposeInspectionMode;
  readonly capabilities: readonly ComposeCapabilityState[];
  readonly roots: readonly ComposableRoot[];
  readonly details: ReadonlyMap<number, ComposableDetail>;
  readonly coverage: readonly ComposeDetailCoverage[];
  readonly completeness: ComposeFrameCompleteness;
  readonly truncations: readonly ComposeTruncation[];
  readonly recompositionObservation?: RecompositionObservation | null;
}

export interface ComposeInspectionDocument {
  readonly schemaVersion: number;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly frame: ComposeInspectionFrame;
  readonly artifact?: ComposeInspectorArtifact | null;
  readonly privacy: ComposeArchivePrivacy;
}

export const SENSITIVE_REDACTION = '<redacted:sensitive>';
export const SAFE_REDACTION = '<redacted>';
export const REDACTED_ARTIFACT_SOURCE = 'local-redacted';

const SENSITIVE_NAMES = [
  'password',
  'passwd',
  'secret',
  'token',
  'credential',
  'authorization',
  'authentication',
  'cookie',
  'sessionid',
  'apikey',
  'privatekey',
];

const JWT = /^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}$/;
const PRIVATE_DEVICE_PATH =
  /(?:^|[\s"'=:,(])\/(?:data|sdcard|storage|mnt|system|vendor|product|apex)(?:\/|$)\S*/;

/** A safe archive keeps structure only: every value becomes a placeholder. */
export function redactedDocument(document: ComposeInspectionDocument): ComposeInspectionDocument {
  return {
    ...document,
    privacy: 'SAFE_REDACTED',
    frame: { ...document.frame, details: mapDetails(document.frame.details, redactValue) },
  };
}

/**
 * The exclusions shared by both privacy levels. Full fidelity keeps ordinary
 * runtime values but never a credential, a token, or a private device path.
 */
export function sanitizedForExport(
  document: ComposeInspectionDocument,
  privacy: ComposeArchivePrivacy,
): ComposeInspectionDocument {
  if (privacy === 'SAFE_REDACTED') return redactedDocument(document);
  return {
    ...document,
    privacy: 'FULL_FIDELITY',
    artifact:
      document.artifact === undefined || document.artifact === null
        ? document.artifact
        : { ...document.artifact, source: sanitizedArtifactSource(document.artifact.source) },
    frame: { ...document.frame, details: mapDetails(document.frame.details, redactSensitiveValue) },
  };
}

function mapDetails(
  details: ReadonlyMap<number, ComposableDetail>,
  redact: (value: ComposeValue) => ComposeValue,
): ReadonlyMap<number, ComposableDetail> {
  const mapped = new Map<number, ComposableDetail>();
  for (const [key, detail] of details) {
    mapped.set(key, {
      ...detail,
      parameters: detail.parameters.map(redact),
      modifiers: detail.modifiers.map(redact),
      mergedSemantics: detail.mergedSemantics.map(redact),
      unmergedSemantics: detail.unmergedSemantics.map(redact),
    });
  }
  return mapped;
}

function redactValue(value: ComposeValue): ComposeValue {
  return {
    ...value,
    value: value.value === undefined || value.value === null ? value.value : SAFE_REDACTION,
    elements: value.elements.map(redactValue),
  };
}

function redactSensitiveValue(value: ComposeValue): ComposeValue {
  const sensitive = isSensitiveName(value.name) || (value.value !== undefined && value.value !== null && isSensitiveValue(value.value));
  return {
    ...value,
    value: sensitive && value.value !== undefined && value.value !== null ? SENSITIVE_REDACTION : value.value,
    elements: value.elements.map(redactSensitiveValue),
  };
}

export function isSensitiveName(name: string): boolean {
  const normalized = [...name.toLowerCase()]
    .filter((character) => /[\p{L}\p{N}]/u.test(character))
    .join('');
  return SENSITIVE_NAMES.some((sensitive) => normalized.includes(sensitive));
}

export function isSensitiveValue(value: string): boolean {
  const trimmed = value.trim();
  return (
    trimmed.toLowerCase().startsWith('bearer ') ||
    trimmed.toLowerCase().startsWith('basic ') ||
    JWT.test(trimmed) ||
    PRIVATE_DEVICE_PATH.test(trimmed)
  );
}

export function sanitizedArtifactSource(source: string): string {
  return PRIVATE_DEVICE_PATH.test(source) || source.startsWith('/') ? REDACTED_ARTIFACT_SOURCE : source;
}
