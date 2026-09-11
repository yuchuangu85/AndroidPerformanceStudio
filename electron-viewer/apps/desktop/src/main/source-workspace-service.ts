import { fail, ok, type StudioResult } from '@aps/contracts';
import {
  indexSourceFile,
  resolveEvidence,
  sourceLanguage,
  type BuildIdentityMatch,
  type ResolutionCandidate,
  type SourceFile,
  type SourceIndexView,
  type SourceResolutionEvidence,
  type SourceSnapshot,
  type SourceSymbol,
} from '@aps/source-workspace';
import {
  readSourceFile,
  sha256Bytes,
  sha256Text,
  walkSourceFiles,
  type WalkedSourceFile,
} from '@aps/source-workspace/node';

export interface SourceIndexDependencies {
  readonly walk: (root: string) => WalkedSourceFile[];
  readonly readFile: (root: string, relativePath: string) => Uint8Array;
  readonly revisionOf: (root: string) => Promise<string>;
  readonly now: () => number;
}

export interface SourceIndex {
  readonly snapshot: SourceSnapshot;
  readonly files: readonly SourceFile[];
  readonly symbols: readonly SourceSymbol[];
}

export const DEFAULT_SOURCE_INDEX_DEPENDENCIES: SourceIndexDependencies = {
  walk: walkSourceFiles,
  readFile: readSourceFile,
  revisionOf: async () => 'unversioned',
  now: () => Date.now(),
};

const UTF8_DECODER = new TextDecoder('utf-8');

/**
 * Walks a local root, reads every indexable file, and builds the structural
 * index for one immutable snapshot. The manifest hash covers every file path
 * and content hash, so two runs over the same tree produce the same snapshot
 * identity.
 */
export async function indexLocalWorkspace(
  workspaceId: string,
  root: string,
  dependencies: SourceIndexDependencies = DEFAULT_SOURCE_INDEX_DEPENDENCIES,
): Promise<StudioResult<SourceIndex>> {
  if (root.trim().length === 0) {
    return fail('CONFIGURATION', 'SOURCE_ROOT_REQUIRED', 'A source root is required');
  }
  let walked: WalkedSourceFile[];
  try {
    walked = dependencies.walk(root);
  } catch (error) {
    return fail(
      'IO',
      'SOURCE_ROOT_UNREADABLE',
      'Unable to walk the source root: ' + (error instanceof Error ? error.message : String(error)),
    );
  }
  if (walked.length === 0) {
    return fail(
      'DATA_VALIDATION',
      'SOURCE_ROOT_EMPTY',
      'No indexable source files were found under ' + root,
    );
  }

  let revision: string;
  try {
    revision = await dependencies.revisionOf(root);
  } catch {
    revision = 'unversioned';
  }

  const files: SourceFile[] = [];
  const symbols: SourceSymbol[] = [];
  const manifest: string[] = [];
  const skipped: string[] = [];
  const snapshotId = sha256Text(workspaceId + ':' + root + ':' + revision).slice(0, 32);

  for (const entry of walked) {
    let bytes: Uint8Array;
    try {
      bytes = dependencies.readFile(root, entry.relativePath);
    } catch {
      skipped.push(entry.relativePath);
      continue;
    }
    const contentHash = sha256Bytes(bytes);
    const content = UTF8_DECODER.decode(bytes);
    files.push({
      snapshotId,
      relativePath: entry.relativePath,
      language: sourceLanguage(entry.relativePath),
      contentHash,
      sizeBytes: bytes.length,
    });
    symbols.push(...indexSourceFile(snapshotId, entry.relativePath, content));
    manifest.push(entry.relativePath + ':' + contentHash);
  }

  if (files.length === 0) {
    return fail('DATA_VALIDATION', 'SOURCE_ROOT_UNREADABLE', 'Every source file under ' + root + ' failed to read');
  }

  const createdAtEpochMillis = dependencies.now();
  return ok({
    snapshot: {
      id: snapshotId,
      workspaceId,
      immutableRevision: revision,
      manifestHash: sha256Text(manifest.join('\n')),
      createdAtEpochMillis,
      indexVersion: createdAtEpochMillis,
      // A partially readable tree still produces a usable index, marked partial.
      indexComplete: skipped.length === 0,
    },
    files,
    symbols,
  });
}

export function indexViewOf(index: SourceIndex | undefined): SourceIndexView {
  return {
    snapshot: (snapshotId) => (index?.snapshot.id === snapshotId ? index.snapshot : undefined),
    files: (snapshotId) => (index?.snapshot.id === snapshotId ? index.files : []),
    symbols: (snapshotId) => (index?.snapshot.id === snapshotId ? index.symbols : []),
  };
}

export function resolveInIndex(
  index: SourceIndex | undefined,
  evidence: readonly SourceResolutionEvidence[],
  buildIdentityMatch: BuildIdentityMatch = 'UNVERIFIED',
): ResolutionCandidate[] {
  if (index === undefined || evidence.length === 0) return [];
  return resolveEvidence(indexViewOf(index), [index.snapshot.id], evidence, buildIdentityMatch);
}

export interface SourceFileContent {
  readonly relativePath: string;
  readonly text: string;
  readonly language: string;
  readonly state: 'CURRENT' | 'STALE';
}

/** Reads a file back and reports whether it still matches the indexed hash. */
export function readIndexedSource(
  root: string,
  file: SourceFile,
  dependencies: SourceIndexDependencies = DEFAULT_SOURCE_INDEX_DEPENDENCIES,
): StudioResult<SourceFileContent> {
  try {
    const bytes = dependencies.readFile(root, file.relativePath);
    return ok({
      relativePath: file.relativePath,
      text: UTF8_DECODER.decode(bytes),
      language: file.language,
      state: sha256Bytes(bytes) === file.contentHash ? 'CURRENT' : 'STALE',
    });
  } catch (error) {
    return fail(
      'IO',
      'SOURCE_FILE_UNREADABLE',
      error instanceof Error ? error.message : 'Unable to read ' + file.relativePath,
    );
  }
}
