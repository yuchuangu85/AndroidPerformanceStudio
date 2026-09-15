export type SourceCandidateChoice =
  | { readonly kind: 'none' }
  | { readonly kind: 'open'; readonly candidateId: string }
  | { readonly kind: 'choose'; readonly candidateIds: readonly string[] };

/** Mirrors Kotlin's one-candidate direct-open / many-candidates chooser rule. */
export function sourceCandidateChoice(candidateIds: readonly string[]): SourceCandidateChoice {
  const candidates = [...new Set(candidateIds)];
  if (candidates.length === 0) return { kind: 'none' };
  if (candidates.length === 1) return { kind: 'open', candidateId: candidates[0] as string };
  return { kind: 'choose', candidateIds: candidates };
}
