import type { UiNode } from './model.js';

export interface TreeFlattenOptions {
  /** Node ids whose children are currently shown. */
  readonly expanded: ReadonlySet<string>;
  /** Effective hidden subtree; hidden rows are still listed but marked. */
  readonly hiddenSubtree?: ReadonlySet<string>;
  /** Drops nodes the platform reports as not visible (View display option). */
  readonly hideInvisibleViews?: boolean;
}

export interface TreeRow {
  readonly node: UiNode;
  readonly depth: number;
  readonly hasChildren: boolean;
  readonly expanded: boolean;
  readonly hidden: boolean;
  /** Hidden because an ancestor is hidden rather than by its own id. */
  readonly hiddenByAncestor: boolean;
}

export function flattenVisibleTree(root: UiNode, options: TreeFlattenOptions): TreeRow[] {
  const hidden = options.hiddenSubtree ?? new Set<string>();
  const rows: TreeRow[] = [];
  const visit = (node: UiNode, depth: number, ancestorHidden: boolean): void => {
    if (options.hideInvisibleViews === true && !node.visible && depth > 0) return;
    const selfHidden = hidden.has(node.id) && !ancestorHidden;
    rows.push({
      node,
      depth,
      hasChildren: node.children.length > 0,
      expanded: options.expanded.has(node.id),
      hidden: hidden.has(node.id),
      hiddenByAncestor: ancestorHidden,
      ...(selfHidden ? {} : {}),
    });
    if (!options.expanded.has(node.id)) return;
    for (const child of node.children) visit(child, depth + 1, ancestorHidden || hidden.has(node.id));
  };
  visit(root, 0, false);
  return rows;
}

/** Keeps only expanded ids that still exist, so a new snapshot starts collapsed. */
export function sanitizeExpanded(expanded: ReadonlySet<string>, root: UiNode): Set<string> {
  const present = new Set<string>();
  const visit = (node: UiNode): void => {
    present.add(node.id);
    for (const child of node.children) visit(child);
  };
  visit(root);
  const next = new Set<string>();
  for (const id of expanded) {
    if (present.has(id)) next.add(id);
  }
  return next;
}
