import type { UiNode } from './model.js';

/**
 * Port of the Kotlin LayoutAnalyzer (shared-kernel/analysis-engine): the
 * structural metrics and the four findings rules the FINDINGS pane lists.
 *
 * The messages are the reference's own fallback strings; the presenter
 * re-localizes them from the rule id and arguments, so a rule that gains a
 * translation does not need this file to change.
 */
export interface AnalysisConfig {
  readonly maxDepth: number;
  readonly maxChildrenPerNode: number;
  readonly minOverlappingSiblings: number;
  readonly minSiblingOverlapRatio: number;
}

export const DEFAULT_ANALYSIS_CONFIG: AnalysisConfig = {
  maxDepth: 10,
  maxChildrenPerNode: 10,
  minOverlappingSiblings: 3,
  minSiblingOverlapRatio: 0.8,
};

export type Severity = 'INFO' | 'WARNING' | 'ERROR';

export interface Finding {
  readonly ruleId: string;
  readonly severity: Severity;
  readonly nodeId: string;
  readonly message: string;
  readonly arguments: Readonly<Record<string, string>>;
}

export interface LayoutMetrics {
  readonly nodeCount: number;
  readonly maxDepth: number;
  readonly widestLevel: number;
}

export interface AnalysisReport {
  readonly metrics: LayoutMetrics;
  readonly findings: readonly Finding[];
}

export const RULE_INVISIBLE_NODE = 'layout.invisible-node';
export const RULE_EXCESSIVE_CHILDREN = 'layout.excessive-children';
export const RULE_OVERLAPPING_SIBLINGS = 'layout.overlapping-siblings';
export const RULE_DEEP_HIERARCHY = 'layout.deep-hierarchy';

/**
 * Breadth-first, like the reference: findings come out in level order, so the
 * pane lists a shallow problem above a deeper one, and the depth rule is
 * appended once the whole tree has been measured.
 */
export function analyzeLayout(root: UiNode, config: AnalysisConfig = DEFAULT_ANALYSIS_CONFIG): AnalysisReport {
  let nodeCount = 0;
  let maxDepth = 0;
  const nodesPerDepth = new Map<number, number>();
  const findings: Finding[] = [];
  const pending: Array<{ node: UiNode; depth: number }> = [{ node: root, depth: 1 }];

  while (pending.length > 0) {
    const entry = pending.shift() as { node: UiNode; depth: number };
    const { node, depth } = entry;
    nodeCount += 1;
    maxDepth = Math.max(maxDepth, depth);
    nodesPerDepth.set(depth, (nodesPerDepth.get(depth) ?? 0) + 1);

    if (!node.visible || node.alpha <= 0) {
      findings.push({
        ruleId: RULE_INVISIBLE_NODE,
        severity: 'INFO',
        nodeId: node.id,
        message: node.className + ' 节点存在但当前不可见',
        arguments: { className: node.className },
      });
    }
    if (node.children.length > config.maxChildrenPerNode) {
      findings.push({
        ruleId: RULE_EXCESSIVE_CHILDREN,
        severity: 'WARNING',
        nodeId: node.id,
        message:
          '直接子节点数量 ' + String(node.children.length) + '，超过阈值 ' + String(config.maxChildrenPerNode),
        arguments: {
          count: String(node.children.length),
          threshold: String(config.maxChildrenPerNode),
        },
      });
    }
    const overlapping = substantiallyOverlappingSiblings(node.children, config.minSiblingOverlapRatio);
    if (overlapping >= config.minOverlappingSiblings) {
      const ratioPercent = String(Math.trunc(config.minSiblingOverlapRatio * 100));
      findings.push({
        ruleId: RULE_OVERLAPPING_SIBLINGS,
        severity: 'WARNING',
        nodeId: node.id,
        message:
          String(overlapping) +
          ' 个兄弟节点的边界重叠比例至少为 ' +
          ratioPercent +
          '%；这是结构性渲染风险，请使用 GPU 工具进一步确认',
        arguments: { count: String(overlapping), ratioPercent },
      });
    }
    for (const child of node.children) pending.push({ node: child, depth: depth + 1 });
  }

  if (maxDepth > config.maxDepth) {
    findings.push({
      ruleId: RULE_DEEP_HIERARCHY,
      severity: 'WARNING',
      nodeId: root.id,
      message: '层级深度 ' + String(maxDepth) + '，超过阈值 ' + String(config.maxDepth),
      arguments: { depth: String(maxDepth), threshold: String(config.maxDepth) },
    });
  }

  let widestLevel = 0;
  for (const count of nodesPerDepth.values()) widestLevel = Math.max(widestLevel, count);
  return { metrics: { nodeCount, maxDepth, widestLevel }, findings };
}

/** Number of siblings that take part in at least one substantial overlap. */
function substantiallyOverlappingSiblings(children: readonly UiNode[], threshold: number): number {
  const overlapping = new Set<number>();
  for (let first = 0; first < children.length; first += 1) {
    for (let second = first + 1; second < children.length; second += 1) {
      const firstChild = children[first];
      const secondChild = children[second];
      if (firstChild === undefined || secondChild === undefined) continue;
      if (overlapRatio(firstChild, secondChild) >= threshold) {
        overlapping.add(first);
        overlapping.add(second);
      }
    }
  }
  return overlapping.size;
}

function overlapRatio(first: UiNode, second: UiNode): number {
  const intersectionWidth = Math.max(
    0,
    Math.min(first.bounds.right, second.bounds.right) - Math.max(first.bounds.left, second.bounds.left),
  );
  const intersectionHeight = Math.max(
    0,
    Math.min(first.bounds.bottom, second.bounds.bottom) - Math.max(first.bounds.top, second.bounds.top),
  );
  const smallerArea = Math.min(
    (first.bounds.right - first.bounds.left) * (first.bounds.bottom - first.bounds.top),
    (second.bounds.right - second.bounds.left) * (second.bounds.bottom - second.bounds.top),
  );
  if (smallerArea === 0) return 0;
  return (intersectionWidth * intersectionHeight) / smallerArea;
}
