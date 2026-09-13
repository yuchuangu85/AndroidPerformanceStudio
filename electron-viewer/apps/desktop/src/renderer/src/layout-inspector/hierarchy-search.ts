import type { LayoutTreeRow } from './tree';

/**
 * Port of HierarchySearchState: the query, the match cursor, and the summary the
 * search bar shows. A row matches on its label, its layout id and its row
 * number, case-insensitively, exactly as the reference tests it.
 */
export interface HierarchySearchState {
  readonly query: string;
  /** Index into the matched ids; -1 whenever there is no match to point at. */
  readonly currentIndex: number;
}

export const NO_HIERARCHY_SEARCH: HierarchySearchState = { query: '', currentIndex: -1 };

/** Kotlin's String.isNotBlank: a query of spaces searches for nothing. */
export function isSearching(state: HierarchySearchState): boolean {
  return state.query.trim().length > 0;
}

export function searchMatches(row: LayoutTreeRow, query: string): boolean {
  if (query.trim().length === 0) return false;
  const needle = query.toLowerCase();
  return (
    row.label.toLowerCase().includes(needle) ||
    (row.resourceLabel !== undefined && row.resourceLabel.toLowerCase().includes(needle)) ||
    row.number.toLowerCase().includes(needle)
  );
}

export function matchedNodeIds(rows: readonly LayoutTreeRow[], state: HierarchySearchState): string[] {
  if (!isSearching(state)) return [];
  return rows.filter((row) => searchMatches(row, state.query)).map((row) => row.node.id);
}

/** Typing resets the cursor to the first match, the way withQuery does. */
export function withQuery(state: HierarchySearchState, query: string): HierarchySearchState {
  return { ...state, query, currentIndex: query.trim().length === 0 ? -1 : 0 };
}

export function navigateNext(
  state: HierarchySearchState,
  matchedIds: readonly string[],
): HierarchySearchState {
  if (matchedIds.length === 0) return { ...state, currentIndex: -1 };
  const next = state.currentIndex < 0 ? 0 : (state.currentIndex + 1) % matchedIds.length;
  return { ...state, currentIndex: next };
}

export function navigatePrevious(
  state: HierarchySearchState,
  matchedIds: readonly string[],
): HierarchySearchState {
  if (matchedIds.length === 0) return { ...state, currentIndex: -1 };
  const previous = state.currentIndex <= 0 ? matchedIds.length - 1 : state.currentIndex - 1;
  return { ...state, currentIndex: previous };
}

export function currentMatchedNodeId(
  state: HierarchySearchState,
  matchedIds: readonly string[],
): string | undefined {
  if (state.currentIndex < 0 || state.currentIndex >= matchedIds.length) return undefined;
  return matchedIds[state.currentIndex];
}

/** "1/3", or "0/0" when the query matches nothing. */
export function matchSummary(
  state: HierarchySearchState,
  matchedIds: readonly string[],
): string | undefined {
  if (!isSearching(state)) return undefined;
  if (matchedIds.length === 0) return '0/0';
  const displayIndex = Math.min(Math.max(state.currentIndex + 1, 1), matchedIds.length);
  return displayIndex + '/' + matchedIds.length;
}

export interface SearchTextSegment {
  readonly text: string;
  readonly match: boolean;
}

/**
 * The label split into runs, so the search bar can highlight the parts that
 * matched without rebuilding the string (the reference's
 * HierarchySearchHighlightText).
 */
export function searchSegments(text: string, query: string): SearchTextSegment[] {
  const needle = query.trim().toLowerCase();
  if (needle.length === 0) return [{ text, match: false }];
  const haystack = text.toLowerCase();
  const segments: SearchTextSegment[] = [];
  let cursor = 0;
  for (;;) {
    const found = haystack.indexOf(needle, cursor);
    if (found < 0) break;
    if (found > cursor) segments.push({ text: text.slice(cursor, found), match: false });
    segments.push({ text: text.slice(found, found + needle.length), match: true });
    cursor = found + needle.length;
  }
  if (segments.length === 0) return [{ text, match: false }];
  if (cursor < text.length) segments.push({ text: text.slice(cursor), match: false });
  return segments;
}
