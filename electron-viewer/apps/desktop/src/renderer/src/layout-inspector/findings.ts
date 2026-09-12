import { RULE_DEEP_HIERARCHY, RULE_EXCESSIVE_CHILDREN, RULE_INVISIBLE_NODE, RULE_OVERLAPPING_SIBLINGS, type AnalysisReport, type Finding } from '@aps/layout-inspector';
import type { UiLanguage } from '../../../shared/i18n';
import { layoutText, type LayoutTextKey } from './labels';
import { visibleTreeRows, type LayoutTreeRow } from './tree';

/**
 * Port of InspectorPresenter's finding rows, ViewDisplayProjection.findings and
 * FindingsLayout: the FINDINGS pane lists the analysis rules in the tree's own
 * numbering, filtered and measured the way the reference does.
 */
export type FindingTone = 'info' | 'warning' | 'error';

export interface FindingRow {
  readonly key: string;
  readonly title: string;
  readonly nodeNumber: string;
  readonly nodeId: string;
  readonly message: string;
  readonly tone: FindingTone;
}

export const FINDINGS_TYPOGRAPHY = { textSizePx: 10, lineHeightPx: 12, verticalPaddingPx: 2 } as const;
export const FINDINGS_LAYOUT = { defaultHeight: 89, minHeight: 56, splitterHeight: 7 } as const;

const MAX_HEIGHT_RATIO = 0.5;

const RULE_TITLES: Readonly<Record<string, LayoutTextKey>> = {
  [RULE_INVISIBLE_NODE]: 'finding.invisibleNode',
  [RULE_EXCESSIVE_CHILDREN]: 'finding.excessiveChildren',
  [RULE_OVERLAPPING_SIBLINGS]: 'finding.overlappingSiblings',
  [RULE_DEEP_HIERARCHY]: 'finding.deepHierarchy',
};

const RULE_MESSAGES: Readonly<Record<string, LayoutTextKey>> = {
  [RULE_INVISIBLE_NODE]: 'finding.invisibleNode.message',
  [RULE_EXCESSIVE_CHILDREN]: 'finding.excessiveChildren.message',
  [RULE_OVERLAPPING_SIBLINGS]: 'finding.overlappingSiblings.message',
  [RULE_DEEP_HIERARCHY]: 'finding.deepHierarchy.message',
};

/** Localizes a rule by its arguments, falling back to the analyzer's message. */
export function findingMessage(finding: Finding, language: UiLanguage): string {
  const key = RULE_MESSAGES[finding.ruleId];
  if (key === undefined) return finding.message;
  const args = finding.arguments;
  switch (finding.ruleId) {
    case RULE_INVISIBLE_NODE: {
      const className = args['className'];
      return className === undefined ? finding.message : layoutText(key, language, className);
    }
    case RULE_EXCESSIVE_CHILDREN: {
      const count = args['count'];
      const threshold = args['threshold'];
      return count === undefined || threshold === undefined
        ? finding.message
        : layoutText(key, language, count, threshold);
    }
    case RULE_OVERLAPPING_SIBLINGS: {
      const count = args['count'];
      const ratio = args['ratioPercent'];
      return count === undefined || ratio === undefined
        ? finding.message
        : layoutText(key, language, count, ratio);
    }
    case RULE_DEEP_HIERARCHY: {
      const depth = args['depth'];
      const threshold = args['threshold'];
      return depth === undefined || threshold === undefined
        ? finding.message
        : layoutText(key, language, depth, threshold);
    }
    default:
      return finding.message;
  }
}

export function findingRows(
  report: AnalysisReport,
  treeRows: readonly LayoutTreeRow[],
  language: UiLanguage,
): FindingRow[] {
  const numbers = new Map<string, string>();
  for (const row of treeRows) {
    if (!numbers.has(row.node.id)) numbers.set(row.node.id, row.number);
  }
  return report.findings.map((finding, index) => ({
    key: finding.ruleId + ':' + finding.nodeId + ':' + String(index),
    title: RULE_TITLES[finding.ruleId] === undefined ? finding.ruleId : layoutText(RULE_TITLES[finding.ruleId] as LayoutTextKey, language),
    nodeNumber: numbers.get(finding.nodeId) ?? '—',
    nodeId: finding.nodeId,
    message: findingMessage(finding, language),
    tone: finding.severity === 'INFO' ? 'info' : finding.severity === 'WARNING' ? 'warning' : 'error',
  }));
}

/**
 * Port of ViewDisplayProjection.findings: with "hide invisible views" on, a
 * finding whose node the option removed from the tree is dropped with it.
 */
export function filterHiddenFindings(
  findings: readonly FindingRow[],
  treeRows: readonly LayoutTreeRow[],
  hideInvisible: boolean,
): FindingRow[] {
  if (!hideInvisible) return [...findings];
  const rowIds = new Set(treeRows.map((row) => row.node.id));
  const displayed = new Set(visibleTreeRows(treeRows, new Set<string>(), true).map((row) => row.node.id));
  const hidden = new Set([...rowIds].filter((id) => !displayed.has(id)));
  return findings.filter((finding) => !hidden.has(finding.nodeId));
}

export interface SeveritySummary {
  readonly info: number;
  readonly warning: number;
  readonly error: number;
}

export function severitySummary(findings: readonly FindingRow[]): SeveritySummary {
  return {
    info: findings.filter((finding) => finding.tone === 'info').length,
    warning: findings.filter((finding) => finding.tone === 'warning').length,
    error: findings.filter((finding) => finding.tone === 'error').length,
  };
}

/** Port of FindingsLayout: min height, and never more than half the pane area. */
export function fitFindingsHeight(heightPx: number, availableHeightPx: number): number {
  const maximum = Math.max(FINDINGS_LAYOUT.minHeight, availableHeightPx * MAX_HEIGHT_RATIO);
  return Math.min(Math.max(heightPx, FINDINGS_LAYOUT.minHeight), maximum);
}

/** Dragging the splitter up grows the pane, dragging it down shrinks it. */
export function dragFindingsHeight(heightPx: number, deltaPx: number, availableHeightPx: number): number {
  return fitFindingsHeight(heightPx - deltaPx, availableHeightPx);
}
