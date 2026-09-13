import { describe, expect, it } from 'vitest';
import type { LayoutTreeRow } from './tree';
import {
  NO_HIERARCHY_SEARCH,
  currentMatchedNodeId,
  isSearching,
  matchSummary,
  matchedNodeIds,
  navigateNext,
  navigatePrevious,
  searchMatches,
  searchSegments,
  withQuery,
} from './hierarchy-search';

function row(id: string, number: string, label: string, resourceLabel?: string): LayoutTreeRow {
  return {
    node: { type: 'view', id, className: 'android.widget.' + label, bounds: { left: 0, top: 0, right: 1, bottom: 1 }, visible: true, alpha: 1, children: [], attributes: { rawProperties: {} } },
    depth: 0,
    number,
    label,
    ...(resourceLabel === undefined ? {} : { resourceLabel }),
    visible: true,
    hasChildren: false,
  };
}

const ROWS = [
  row('a', '0-0', 'FrameLayout', 'id/content_root'),
  row('b', '1-0', 'TextView', 'id/title'),
  row('c', '1-1', 'Button', 'id/submit'),
];

describe('hierarchy search', () => {
  it('matches the label, the layout id and the row number, ignoring case', () => {
    expect(searchMatches(ROWS[1]!, 'text')).toBe(true);
    expect(searchMatches(ROWS[2]!, 'SUBMIT')).toBe(true);
    expect(searchMatches(ROWS[1]!, '1-0')).toBe(true);
    expect(searchMatches(ROWS[0]!, 'button')).toBe(false);
    expect(matchedNodeIds(ROWS, withQuery(NO_HIERARCHY_SEARCH, '1-1'))).toEqual(['c']);
  });

  it('searches for nothing while the query is blank', () => {
    const blank = withQuery(NO_HIERARCHY_SEARCH, '   ');
    expect(isSearching(blank)).toBe(false);
    expect(matchedNodeIds(ROWS, blank)).toEqual([]);
    expect(matchSummary(blank, [])).toBeUndefined();
    expect(withQuery(blank, '')).toEqual(NO_HIERARCHY_SEARCH);
  });

  it('starts the cursor on the first match and wraps in both directions', () => {
    const typed = withQuery(NO_HIERARCHY_SEARCH, 't');
    expect(typed.currentIndex).toBe(0);
    const ids = matchedNodeIds(ROWS, typed);
    expect(navigateNext(typed, ids)).toEqual({ query: 't', currentIndex: 1 });
    expect(navigatePrevious(typed, ids)).toEqual({ query: 't', currentIndex: 2 });
    expect(navigateNext({ query: 't', currentIndex: 2 }, ids)).toEqual({ query: 't', currentIndex: 0 });
    expect(navigatePrevious({ query: 't', currentIndex: 0 }, ids)).toEqual({ query: 't', currentIndex: 2 });
    expect(navigateNext(typed, [])).toEqual({ query: 't', currentIndex: -1 });
    expect(navigatePrevious(typed, [])).toEqual({ query: 't', currentIndex: -1 });
  });

  it('reports the cursor against the match count', () => {
    expect(matchSummary({ query: 'o', currentIndex: 0 }, ['a', 'b', 'c'])).toBe('1/3');
    expect(matchSummary({ query: 'o', currentIndex: 2 }, ['a', 'b', 'c'])).toBe('3/3');
    expect(matchSummary({ query: 'o', currentIndex: -1 }, ['a'])).toBe('1/1');
    expect(matchSummary({ query: 'zzz', currentIndex: -1 }, [])).toBe('0/0');
    expect(currentMatchedNodeId({ query: 'o', currentIndex: 1 }, ['a', 'b', 'c'])).toBe('b');
    expect(currentMatchedNodeId({ query: 'o', currentIndex: 9 }, ['a'])).toBeUndefined();
  });

  it('splits a label into the runs the search bar highlights', () => {
    expect(searchSegments('android.widget.TextView', 'widget')).toEqual([
      { text: 'android.', match: false },
      { text: 'widget', match: true },
      { text: '.TextView', match: false },
    ]);
    expect(searchSegments('Button', 'button')).toEqual([{ text: 'Button', match: true }]);
    expect(searchSegments('Button', 'zzz')).toEqual([{ text: 'Button', match: false }]);
    expect(searchSegments('Button', '  ')).toEqual([{ text: 'Button', match: false }]);
  });
});
