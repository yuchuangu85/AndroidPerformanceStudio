import type { UiNode, ViewNode } from '@aps/layout-inspector';

/**
 * Port of InspectorPresenter.appendRows / ViewDisplayProjection.hierarchyLabel
 * and HierarchyTreeState.
 *
 * The difference that matters for parity is the row identity: the reference
 * numbers every row by depth and position ("1-3"), names the layout id after the
 * resource ("id/title"), and joins them with the class name. The tree also opens
 * fully expanded, because the reference stores collapsed ids, not expanded ones.
 */
export interface LayoutTreeRow {
  readonly node: UiNode;
  readonly depth: number;
  /** Depth and position within that depth, for example "2-1". */
  readonly number: string;
  /** Short class name, the last segment of the fully qualified name. */
  readonly label: string;
  /** "id/name" for a view that declares a resource id. */
  readonly resourceLabel?: string;
  /** Visible and not fully transparent. */
  readonly visible: boolean;
  readonly hasChildren: boolean;
}

export interface HierarchyLabelOptions {
  readonly hideIndex: boolean;
  readonly showId: boolean;
}

function shortClassName(className: string): string {
  const segments = className.split('.');
  return segments[segments.length - 1] ?? className;
}

function resourceLabelOf(node: UiNode): string | undefined {
  if (node.type !== 'view') return undefined;
  const resourceName = (node as ViewNode).resourceName;
  if (resourceName === undefined) return undefined;
  const segments = resourceName.split('/');
  const name = segments[segments.length - 1] ?? '';
  return name.length > 0 ? 'id/' + name : undefined;
}

/** Every node in order, numbered the way the reference numbers the tree. */
export function buildLayoutTreeRows(root: UiNode): LayoutTreeRow[] {
  const rows: LayoutTreeRow[] = [];
  const nextIndexByDepth = new Map<number, number>();
  const visit = (node: UiNode, depth: number): void => {
    const index = nextIndexByDepth.get(depth) ?? 0;
    nextIndexByDepth.set(depth, index + 1);
    rows.push({
      node,
      depth,
      number: depth + '-' + index,
      label: shortClassName(node.className),
      ...(resourceLabelOf(node) !== undefined ? { resourceLabel: resourceLabelOf(node) as string } : {}),
      visible: node.visible && node.alpha > 0,
      hasChildren: node.children.length > 0,
    });
    for (const child of node.children) visit(child, depth + 1);
  };
  visit(root, 0);
  return rows;
}

/**
 * Collapsed nodes hide their descendants; hidden views are dropped together with
 * theirs. A collapsed row stays visible (it is what the user clicks to expand),
 * an invisible row does not.
 */
export function visibleTreeRows(
  rows: readonly LayoutTreeRow[],
  collapsed: ReadonlySet<string>,
  hideInvisible: boolean,
): LayoutTreeRow[] {
  return withoutCollapsedDescendants(hideInvisible ? withoutInvisible(rows) : [...rows], collapsed);
}

function withoutInvisible(rows: readonly LayoutTreeRow[]): LayoutTreeRow[] {
  const kept: LayoutTreeRow[] = [];
  let hiddenAncestorDepth: number | undefined;
  for (const row of rows) {
    if (hiddenAncestorDepth !== undefined) {
      if (row.depth > hiddenAncestorDepth) continue;
      hiddenAncestorDepth = undefined;
    }
    if (row.visible) kept.push(row);
    else hiddenAncestorDepth = row.depth;
  }
  return kept;
}

function withoutCollapsedDescendants(
  rows: readonly LayoutTreeRow[],
  collapsed: ReadonlySet<string>,
): LayoutTreeRow[] {
  const kept: LayoutTreeRow[] = [];
  let collapsedAncestorDepth: number | undefined;
  for (const row of rows) {
    if (collapsedAncestorDepth !== undefined) {
      if (row.depth > collapsedAncestorDepth) continue;
      collapsedAncestorDepth = undefined;
    }
    kept.push(row);
    if (row.hasChildren && collapsed.has(row.node.id)) collapsedAncestorDepth = row.depth;
  }
  return kept;
}

/** "1-3  id/title  TextView", with either optional part switched off. */
export function hierarchyLabel(row: LayoutTreeRow, options: HierarchyLabelOptions): string {
  const parts: string[] = [];
  if (!options.hideIndex) parts.push(row.number);
  if (options.showId && row.resourceLabel !== undefined) parts.push(row.resourceLabel);
  parts.push(row.label);
  return parts.join('  ');
}

/** Node id to row number, which is how findings name the node they point at. */
export function nodeNumbers(rows: readonly LayoutTreeRow[]): Map<string, string> {
  const numbers = new Map<string, string>();
  for (const row of rows) {
    if (!numbers.has(row.node.id)) numbers.set(row.node.id, row.number);
  }
  return numbers;
}

export interface TreeMetrics {
  readonly nodeCount: number;
  readonly maxDepth: number;
  readonly widestLevel: number;
}

export function treeMetrics(rows: readonly LayoutTreeRow[]): TreeMetrics {
  const perDepth = new Map<number, number>();
  let maxDepth = 0;
  for (const row of rows) {
    perDepth.set(row.depth, (perDepth.get(row.depth) ?? 0) + 1);
    maxDepth = Math.max(maxDepth, row.depth);
  }
  let widestLevel = 0;
  for (const count of perDepth.values()) widestLevel = Math.max(widestLevel, count);
  return { nodeCount: rows.length, maxDepth: maxDepth + 1, widestLevel };
}
