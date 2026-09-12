import { describe, expect, it } from 'vitest';
import { analyzeLayout, type AnalysisReport, type UiNode } from '@aps/layout-inspector';
import {
  FINDINGS_LAYOUT,
  FINDINGS_TYPOGRAPHY,
  dragFindingsHeight,
  filterHiddenFindings,
  findingRows,
  fitFindingsHeight,
  severitySummary,
} from './findings';
import { buildLayoutTreeRows } from './tree';

/** Expectations ported from InspectorPresenterTest, ViewDisplayProjectionTest and FindingsLayoutTest. */
function node(id: string, options: { visible?: boolean; children?: UiNode[] } = {}): UiNode {
  return {
    type: 'view',
    id,
    className: 'android.widget.FrameLayout',
    bounds: { left: 0, top: 0, right: 100, bottom: 100 },
    visible: options.visible ?? true,
    alpha: 1,
    children: options.children ?? [],
    attributes: { rawProperties: {} },
  };
}

const TREE = node('root', { children: [node('root/0', { visible: false }), node('root/1')] });
const REPORT: AnalysisReport = analyzeLayout(TREE);
const ROWS = buildLayoutTreeRows(TREE);

describe('finding rows', () => {
  it('numbers a finding with the tree row number and localizes it', () => {
    const rows = findingRows(REPORT, ROWS, 'en');
    expect(rows).toHaveLength(1);
    const finding = rows[0];
    expect(finding?.key).toBe('layout.invisible-node:root/0:0');
    expect(finding?.nodeNumber).toBe('1-0');
    expect(finding?.title).toBe('Invisible node');
    expect(finding?.message).toBe('android.widget.FrameLayout exists but is currently invisible');
    expect(finding?.tone).toBe('info');
  });

  it('renders the same finding in Chinese from the rule arguments', () => {
    const rows = findingRows(REPORT, ROWS, 'zh');
    expect(rows[0]?.title).toBe('不可见节点');
    expect(rows[0]?.message).toBe('android.widget.FrameLayout 节点存在但当前不可见');
  });

  it('summarises severity counts for the badges', () => {
    const wide = analyzeLayout(node('wide', { children: Array.from({ length: 11 }, (_v, i) => node('c' + String(i))) }));
    const rows = findingRows(wide, buildLayoutTreeRows(node('wide', { children: Array.from({ length: 11 }, (_v, i) => node('c' + String(i))) })), 'en');
    // Eleven identical children trip both the count rule and the overlap rule.
    expect(rows.map((row) => row.tone)).toEqual(['warning', 'warning']);
    expect(severitySummary(rows)).toEqual({ info: 0, warning: 2, error: 0 });
  });

  it('drops findings whose node the invisible filter removed', () => {
    const rows = findingRows(REPORT, ROWS, 'en');
    expect(filterHiddenFindings(rows, ROWS, false)).toHaveLength(1);
    expect(filterHiddenFindings(rows, ROWS, true)).toHaveLength(0);
  });
});

describe('findings layout', () => {
  it('uses the compact typography the reference chose', () => {
    expect(FINDINGS_TYPOGRAPHY).toEqual({ textSizePx: 10, lineHeightPx: 12, verticalPaddingPx: 2 });
    expect(FINDINGS_LAYOUT).toEqual({ defaultHeight: 89, minHeight: 56, splitterHeight: 7 });
  });

  it('keeps the default height when it fits', () => {
    expect(fitFindingsHeight(FINDINGS_LAYOUT.defaultHeight, 800)).toBe(89);
  });

  it('grows upward and shrinks downward, clamped to the limits', () => {
    expect(dragFindingsHeight(89, -40, 800)).toBe(129);
    expect(dragFindingsHeight(89, 40, 800)).toBe(56);
    expect(dragFindingsHeight(89, 1000, 600)).toBe(56);
    expect(dragFindingsHeight(89, -1000, 600)).toBe(300);
  });

  it('reacts to a shrinking window', () => {
    expect(fitFindingsHeight(400, 400)).toBe(200);
    expect(fitFindingsHeight(89, 100)).toBe(56);
  });
});
