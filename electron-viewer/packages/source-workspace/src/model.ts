/**
 * Port of the source workspace model: a workspace points at a source provider,
 * an immutable snapshot pins one revision of it, and the snapshot carries the
 * file and symbol index that performance evidence resolves against.
 */
export type SourceWorkspaceId = string;
export type SourceSnapshotId = string;
export type PerformanceEvidenceId = string;
export type ResolutionCandidateId = string;

export const SOURCE_PROVIDER_KINDS = ['LOCAL', 'GITHUB', 'AOSP'] as const;
export type SourceProviderKind = (typeof SOURCE_PROVIDER_KINDS)[number];

export type SourceProviderConfig =
  | { readonly kind: 'LOCAL'; readonly root: string }
  | {
      readonly kind: 'GITHUB';
      readonly owner: string;
      readonly repository: string;
      readonly ref: string;
      /** Credential store key holding the API token, when one is needed. */
      readonly credentialKey?: string | null;
    }
  | { readonly kind: 'AOSP'; readonly project: string; readonly ref: string };

export const SOURCE_WORKSPACE_PHASES = [
  'REGISTERING',
  'RESOLVING_REVISION',
  'BUILDING_MANIFEST',
  'INDEXING',
  'READY',
  'PARTIAL',
  'FAILED',
] as const;
export type SourceWorkspacePhase = (typeof SOURCE_WORKSPACE_PHASES)[number];

export interface SourceWorkspace {
  readonly id: SourceWorkspaceId;
  readonly displayName: string;
  readonly config: SourceProviderConfig;
  readonly activeSnapshotId?: SourceSnapshotId;
  readonly phase: SourceWorkspacePhase;
  readonly progress: number;
  readonly message?: string;
  /** Off by default: sending source to a model needs an explicit opt in. */
  readonly allowAiSourceUpload: boolean;
}

export interface SourceSnapshot {
  readonly id: SourceSnapshotId;
  readonly workspaceId: SourceWorkspaceId;
  /** Commit hash, or a content digest for an unversioned tree. */
  readonly immutableRevision: string;
  readonly dirtyContentDigest?: string;
  readonly manifestHash: string;
  readonly createdAtEpochMillis: number;
  readonly indexVersion: number;
  readonly indexComplete: boolean;
}

export const SOURCE_LANGUAGES = ['KOTLIN', 'JAVA', 'XML', 'C', 'CPP', 'OTHER'] as const;
export type SourceLanguage = (typeof SOURCE_LANGUAGES)[number];

export interface SourceFile {
  readonly snapshotId: SourceSnapshotId;
  readonly relativePath: string;
  readonly language: SourceLanguage;
  readonly contentHash: string;
  readonly sizeBytes: number;
}

export const SOURCE_SYMBOL_KINDS = ['PACKAGE', 'TYPE', 'FUNCTION', 'METHOD', 'RESOURCE', 'NATIVE_SYMBOL'] as const;
export type SourceSymbolKind = (typeof SOURCE_SYMBOL_KINDS)[number];

export interface SourceSymbol {
  readonly snapshotId: SourceSnapshotId;
  readonly relativePath: string;
  readonly kind: SourceSymbolKind;
  readonly qualifiedName: string;
  readonly signature?: string;
  readonly startLine: number;
  readonly endLine: number;
}

export interface SourceRange {
  readonly startLine: number;
  readonly endLine: number;
}

export interface SourceLocation {
  readonly workspaceId: SourceWorkspaceId;
  readonly snapshotId: SourceSnapshotId;
  readonly relativePath: string;
  readonly range?: SourceRange;
  readonly contentHash: string;
}

export const RESOLUTION_CONFIDENCES = ['EXACT', 'PROBABLE', 'WEAK'] as const;
export type ResolutionConfidence = (typeof RESOLUTION_CONFIDENCES)[number];

export type BuildIdentityMatch = 'VERIFIED' | 'UNVERIFIED';

export interface ResolutionCandidate {
  readonly id: ResolutionCandidateId;
  readonly evidenceId: PerformanceEvidenceId;
  readonly location: SourceLocation;
  readonly confidence: ResolutionConfidence;
  readonly reasons: readonly string[];
  readonly indexVersion: number;
  readonly indexComplete: boolean;
}

export type SourceResolutionEvidence =
  | {
      readonly kind: 'MANAGED_SYMBOL';
      readonly id: PerformanceEvidenceId;
      readonly className?: string;
      readonly methodName: string;
      readonly signature?: string;
      readonly resourcePath?: string;
    }
  | {
      readonly kind: 'NATIVE_SYMBOL';
      readonly id: PerformanceEvidenceId;
      readonly symbolName: string;
      readonly libraryPath?: string;
      readonly buildId?: string;
      readonly sourcePath?: string;
      readonly sourceLine?: number;
    }
  | {
      readonly kind: 'ANDROID_RESOURCE';
      readonly id: PerformanceEvidenceId;
      readonly resourceType: string;
      readonly resourceName: string;
    }
  | { readonly kind: 'TYPE_NAME'; readonly id: PerformanceEvidenceId; readonly qualifiedName: string }
  | {
      readonly kind: 'SOURCE_FILE_LINE';
      readonly id: PerformanceEvidenceId;
      readonly fileName: string;
      /** Java String.hashCode of a package prefix, as recorded in Compose traces. */
      readonly packageHash: number;
      readonly line: number;
    };

export type SourceContentState = 'CURRENT' | 'STALE';

export interface VerifiedSourceContent {
  readonly location: SourceLocation;
  readonly text: string;
  readonly state: SourceContentState;
}
