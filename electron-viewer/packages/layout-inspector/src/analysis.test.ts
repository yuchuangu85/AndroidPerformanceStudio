import { describe, expect, it } from 'vitest';
import { analyzeLayout, type AnalysisConfig } from './analysis.js';
import type { Bounds, UiNode } from './model.js';

/**
 * Expectations ported from the reference's LayoutAnalyzerTest: same fixtures,
 * same rule ids, same arguments, same thresholds.
 */
function node(
  id: string,
  options: { visible?: boolean; bounds?: Bounds; children?: UiNode[] } = {},
): UiNode {
  return {
    type: 'view',
    id,
    className: 'View',
    bounds: options.bounds ?? { left: 0, top: 0, right: 100, bottom: 100 },
    visible: options.visible ?? true,
    alpha: 1,
    children: options.children ?? [],
    attributes: { rawProperties: {} },
  };
}

describe('layout analysis', () => {
  it('computes structural metrics in one hierarchy', () => {
    const root = node('root', { children: [node('left', { children: [node('leaf')] }), node('right')] });
    const report = analyzeLayout(root);
    expect(report.metrics.nodeCount).toBe(4);
    expect(report.metrics.maxDepth).toBe(3);
    expect(report.metrics.widestLevel).toBe(2);
  });

  it('reports invisible nodes with a stable rule id', () => {
    const report = analyzeLayout(node('root', { children: [node('hidden', { visible: false })] }));
    expect(
      report.findings.some(
        (finding) =>
          finding.ruleId === 'layout.invisible-node' &&
          finding.severity === 'INFO' &&
          finding.nodeId === 'hidden' &&
          finding.message === 'View 节点存在但当前不可见' &&
          finding.arguments['className'] === 'View',
      ),
    ).toBe(true);
  });

  it('reports hierarchy depth above the configured threshold', () => {
    const config: AnalysisConfig = { maxDepth: 2, maxChildrenPerNode: 10, minOverlappingSiblings: 3, minSiblingOverlapRatio: 0.8 };
    const report = analyzeLayout(node('root', { children: [node('child', { children: [node('leaf')] })] }), config);
    expect(
      report.findings.some(
        (finding) =>
          finding.ruleId === 'layout.deep-hierarchy' &&
          finding.severity === 'WARNING' &&
          finding.nodeId === 'root' &&
          finding.message === '层级深度 3，超过阈值 2' &&
          finding.arguments['depth'] === '3' &&
          finding.arguments['threshold'] === '2',
      ),
    ).toBe(true);
  });

  it('reports parents with excessive direct children', () => {
    const config: AnalysisConfig = { maxDepth: 10, maxChildrenPerNode: 2, minOverlappingSiblings: 3, minSiblingOverlapRatio: 0.8 };
    const report = analyzeLayout(node('root', { children: [node('a'), node('b'), node('c')] }), config);
    expect(
      report.findings.some(
        (finding) =>
          finding.ruleId === 'layout.excessive-children' &&
          finding.nodeId === 'root' &&
          finding.message === '直接子节点数量 3，超过阈值 2',
      ),
    ).toBe(true);
  });

  it('flags realistic deep and wide hierarchies with the default thresholds', () => {
    let deep = node('leaf');
    for (let level = 1; level <= 10; level += 1) deep = node('level-' + String(level), { children: [deep] });
    const wide = node('wide-root', {
      children: Array.from({ length: 11 }, (_unused, index) => node('child-' + String(index + 1))),
    });
    expect(analyzeLayout(deep).findings.some((finding) => finding.ruleId === 'layout.deep-hierarchy')).toBe(true);
    expect(analyzeLayout(wide).findings.some((finding) => finding.ruleId === 'layout.excessive-children')).toBe(true);
  });

  it('reports a structural risk when several sibling bounds substantially overlap', () => {
    const overlapping = analyzeLayout(
      node('overlapping-root', {
        children: [
          node('back', { bounds: { left: 0, top: 0, right: 100, bottom: 100 } }),
          node('middle', { bounds: { left: 5, top: 5, right: 95, bottom: 95 } }),
          node('front', { bounds: { left: 10, top: 10, right: 90, bottom: 90 } }),
        ],
      }),
    );
    const adjacent = analyzeLayout(
      node('adjacent-root', {
        children: [
          node('left', { bounds: { left: 0, top: 0, right: 50, bottom: 100 } }),
          node('center', { bounds: { left: 50, top: 0, right: 100, bottom: 100 } }),
          node('right', { bounds: { left: 100, top: 0, right: 150, bottom: 100 } }),
        ],
      }),
    );
    expect(
      overlapping.findings.some(
        (finding) =>
          finding.ruleId === 'layout.overlapping-siblings' &&
          finding.nodeId === 'overlapping-root' &&
          finding.severity === 'WARNING' &&
          finding.message === '3 个兄弟节点的边界重叠比例至少为 80%；这是结构性渲染风险，请使用 GPU 工具进一步确认' &&
          finding.arguments['count'] === '3' &&
          finding.arguments['ratioPercent'] === '80',
      ),
    ).toBe(true);
    expect(adjacent.findings.some((finding) => finding.ruleId === 'layout.overlapping-siblings')).toBe(false);
  });

  it('lists findings in level order, one parent at a time', () => {
    const report = analyzeLayout(
      node('root', {
        children: [
          node('hidden', { visible: false }),
          node('wide', { children: Array.from({ length: 11 }, (_value, index) => node('c' + String(index))) }),
        ],
      }),
    );
    // The invisible sibling is at depth 2, and the wide node reports its own
    // children count before its own overlap risk, exactly as the walk does.
    expect(report.findings.map((finding) => finding.ruleId)).toEqual([
      'layout.invisible-node',
      'layout.excessive-children',
      'layout.overlapping-siblings',
    ]);
    expect(report.findings.map((finding) => finding.nodeId)).toEqual(['hidden', 'wide', 'wide']);
  });

  it('appends the depth rule after the whole tree has been measured', () => {
    const config: AnalysisConfig = {
      maxDepth: 2,
      maxChildrenPerNode: 10,
      minOverlappingSiblings: 3,
      minSiblingOverlapRatio: 0.8,
    };
    const report = analyzeLayout(node('root', { children: [node('child', { children: [node('leaf')] })] }), config);
    const last = report.findings[report.findings.length - 1];
    expect(last?.ruleId).toBe('layout.deep-hierarchy');
    expect(last?.nodeId).toBe('root');
  });
});
