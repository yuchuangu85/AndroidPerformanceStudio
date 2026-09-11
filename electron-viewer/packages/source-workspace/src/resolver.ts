/**
 * Port of IndexedSourceResolver.kt. Evidence from a trace is matched against the
 * snapshot index, and every candidate carries the reason it matched plus a
 * confidence, so a weak match is never presented as a certain one.
 *
 * Build identity matters: if the build that produced the evidence is not
 * verified against the snapshot, an EXACT match is downgraded to PROBABLE and
 * the reason says why.
 */
import { javaAbsoluteHash, javaStringHash } from './language.js';
import type {
  BuildIdentityMatch,
  ResolutionCandidate,
  ResolutionConfidence,
  SourceFile,
  SourceResolutionEvidence,
  SourceSnapshot,
  SourceSymbol,
  SourceSymbolKind,
} from './model.js';

export interface SourceIndexView {
  snapshot(snapshotId: string): SourceSnapshot | undefined;
  files(snapshotId: string): readonly SourceFile[];
  symbols(snapshotId: string): readonly SourceSymbol[];
}

export function resolveEvidence(
  index: SourceIndexView,
  snapshotIds: readonly string[],
  evidence: readonly SourceResolutionEvidence[],
  buildIdentityMatch: BuildIdentityMatch = 'UNVERIFIED',
): ResolutionCandidate[] {
  const seen = new Set<string>();
  const candidates: ResolutionCandidate[] = [];
  for (const item of evidence) {
    for (const snapshotId of snapshotIds) {
      for (const candidate of candidatesFor(index, snapshotId, item)) {
        if (seen.has(candidate.id)) continue;
        seen.add(candidate.id);
        candidates.push(applyBuildIdentityCeiling(candidate, buildIdentityMatch));
      }
    }
  }
  return candidates;
}

function candidatesFor(
  index: SourceIndexView,
  snapshotId: string,
  evidence: SourceResolutionEvidence,
): ResolutionCandidate[] {
  const snapshot = index.snapshot(snapshotId);
  if (snapshot === undefined) return [];
  const files = new Map(index.files(snapshotId).map((file) => [file.relativePath, file] as const));
  const symbols = index.symbols(snapshotId);
  switch (evidence.kind) {
    case 'TYPE_NAME':
      return symbols
        .filter((symbol) => symbol.kind === 'TYPE' && symbol.qualifiedName === evidence.qualifiedName)
        .map((symbol) => symbolCandidate(snapshot, files, symbol, evidence.id, 'EXACT', 'Qualified type matched'));
    case 'ANDROID_RESOURCE':
      return symbols
        .filter(
          (symbol) =>
            symbol.kind === 'RESOURCE' &&
            symbol.qualifiedName === evidence.resourceType + '/' + evidence.resourceName,
        )
        .map((symbol) =>
          symbolCandidate(snapshot, files, symbol, evidence.id, 'EXACT', 'Android resource matched'),
        );
    case 'MANAGED_SYMBOL':
      return managedCandidates(snapshot, files, symbols, evidence);
    case 'NATIVE_SYMBOL':
      return nativeCandidates(snapshot, files, symbols, evidence);
    case 'SOURCE_FILE_LINE':
      return sourceFileCandidates(snapshot, files, symbols, evidence);
  }
}

function sourceFileCandidates(
  snapshot: SourceSnapshot,
  files: ReadonlyMap<string, SourceFile>,
  symbols: readonly SourceSymbol[],
  evidence: Extract<SourceResolutionEvidence, { kind: 'SOURCE_FILE_LINE' }>,
): ResolutionCandidate[] {
  const result: ResolutionCandidate[] = [];
  for (const file of files.values()) {
    if (file.relativePath.split('/').pop() !== evidence.fileName) continue;
    const packageMatched = symbols
      .filter((symbol) => symbol.relativePath === file.relativePath)
      .flatMap((symbol) => packagePrefixes(symbol.qualifiedName))
      .some((prefix) => javaAbsoluteHash(javaStringHash(prefix)) === evidence.packageHash);
    if (!packageMatched) continue;
    result.push(fileCandidate(snapshot, file, evidence.id, 'EXACT', 'Compose file and package hash matched', evidence.line));
  }
  return result;
}

function managedCandidates(
  snapshot: SourceSnapshot,
  files: ReadonlyMap<string, SourceFile>,
  symbols: readonly SourceSymbol[],
  evidence: Extract<SourceResolutionEvidence, { kind: 'MANAGED_SYMBOL' }>,
): ResolutionCandidate[] {
  return symbols
    .filter(
      (symbol) =>
        (symbol.kind === 'FUNCTION' || symbol.kind === 'METHOD') &&
        symbol.qualifiedName.split('.').pop() === evidence.methodName,
    )
    .map((symbol) => {
      const classMatches =
        evidence.className === undefined ||
        symbol.relativePath
          .slice(0, symbol.relativePath.lastIndexOf('.'))
          .endsWith(evidence.className.split('.').pop() ?? '');
      const signatureMatches = evidence.signature !== undefined && symbol.signature === evidence.signature;
      const confidence: ResolutionConfidence =
        classMatches && (evidence.signature === undefined || signatureMatches) ? 'EXACT' : 'PROBABLE';
      const matched = symbolCandidate(
        snapshot,
        files,
        symbol,
        evidence.id,
        confidence,
        classMatches ? 'Managed symbol matched' : 'Method name matched',
      );
      return matched;
    });
}

function nativeCandidates(
  snapshot: SourceSnapshot,
  files: ReadonlyMap<string, SourceFile>,
  symbols: readonly SourceSymbol[],
  evidence: Extract<SourceResolutionEvidence, { kind: 'NATIVE_SYMBOL' }>,
): ResolutionCandidate[] {
  if (evidence.sourcePath !== undefined) {
    const wanted = evidence.sourcePath.replace(/^\/+/, '');
    for (const file of files.values()) {
      if (!file.relativePath.endsWith(wanted)) continue;
      return [
        fileCandidate(
          snapshot,
          file,
          evidence.id,
          evidence.sourceLine !== undefined ? 'EXACT' : 'PROBABLE',
          'Symbolizer source path matched',
          evidence.sourceLine,
        ),
      ];
    }
  }
  return symbols
    .filter(
      (symbol) =>
        symbol.kind === 'NATIVE_SYMBOL' &&
        tail(symbol.qualifiedName, '::') === tail(evidence.symbolName, '::'),
    )
    .map((symbol) =>
      symbolCandidate(snapshot, files, symbol, evidence.id, 'PROBABLE', 'Native symbol name matched'),
    );
}

function applyBuildIdentityCeiling(
  candidate: ResolutionCandidate,
  match: BuildIdentityMatch,
): ResolutionCandidate {
  if (match === 'UNVERIFIED' && candidate.confidence === 'EXACT') {
    return {
      ...candidate,
      confidence: 'PROBABLE',
      reasons: [...candidate.reasons, 'Build identity not verified'],
    };
  }
  return candidate;
}

function symbolCandidate(
  snapshot: SourceSnapshot,
  files: ReadonlyMap<string, SourceFile>,
  symbol: SourceSymbol,
  evidenceId: string,
  confidence: ResolutionConfidence,
  reason: string,
): ResolutionCandidate {
  const file = files.get(symbol.relativePath);
  if (file === undefined) throw new Error('Indexed symbol has no file: ' + symbol.relativePath);
  return fileCandidate(snapshot, file, evidenceId, confidence, reason, symbol.startLine);
}

function fileCandidate(
  snapshot: SourceSnapshot,
  file: SourceFile,
  evidenceId: string,
  confidence: ResolutionConfidence,
  reason: string,
  line: number | undefined,
): ResolutionCandidate {
  const identity = snapshot.id + ':' + file.relativePath + ':' + String(line ?? 0) + ':' + evidenceId;
  return {
    id: identity,
    evidenceId,
    location: {
      workspaceId: snapshot.workspaceId,
      snapshotId: snapshot.id,
      relativePath: file.relativePath,
      ...(line !== undefined ? { range: { startLine: line, endLine: line } } : {}),
      contentHash: file.contentHash,
    },
    confidence,
    reasons: [reason],
    indexVersion: snapshot.indexVersion,
    indexComplete: snapshot.indexComplete,
  };
}

function packagePrefixes(qualifiedName: string): string[] {
  const parts = qualifiedName.split('.');
  const prefixes: string[] = [];
  for (let count = 1; count < parts.length; count += 1) {
    prefixes.push(parts.slice(0, count).join('.'));
  }
  return prefixes;
}

function tail(value: string, separator: string): string {
  const index = value.lastIndexOf(separator);
  return index === -1 ? value : value.slice(index + separator.length);
}

export function symbolKindMatches(symbol: SourceSymbol, kinds: readonly SourceSymbolKind[]): boolean {
  return kinds.includes(symbol.kind);
}
