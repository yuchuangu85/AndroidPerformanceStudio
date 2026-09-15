import type { ComposableNode, ComposableRoot, ComposeInspectionFrame } from './compose-inspection.js';
import type { LayoutSnapshot } from './snapshot.js';
import type { UiNode, ViewNode } from './model.js';

/** Immutable port of Kotlin TreeMerger.attachComposeTree. */
export function graftComposeInspection(snapshot: LayoutSnapshot, frame: ComposeInspectionFrame): LayoutSnapshot {
  const byView = new Map(frame.roots.map((root) => [root.viewId, root]));
  const windows = snapshot.windows.map((window) => ({ ...window, root: graftNode(window.root, byView) }));
  const defaultWindow = windows.find((window) => window.id === snapshot.defaultWindowId) ?? windows[0];
  return { ...snapshot, capabilities: { ...snapshot.capabilities, composeSemantics: true }, windows, root: defaultWindow?.root ?? graftNode(snapshot.root, byView) };
}

function graftNode(node: UiNode, roots: ReadonlyMap<number, ComposableRoot>): UiNode {
  if (node.type !== 'view') return node;
  const viewId = numericViewId(node.id);
  const matching = viewId === undefined ? undefined : roots.get(viewId);
  if (matching !== undefined) return graftRoot(node, matching);
  return { ...node, children: node.children.map((child) => graftNode(child, roots)) };
}

function graftRoot(host: ViewNode, root: ComposableRoot): ViewNode {
  const skip = new Set(root.viewsToSkip.map((id) => `view:${id}`));
  const hosted = collectHostedIds(root.nodes);
  const hostedViews = new Map<number, ViewNode>();
  const retained: UiNode[] = [];
  for (const child of host.children) {
    if (child.type === 'view' && skip.has(child.id)) continue;
    const id = numericViewId(child.id);
    if (child.type === 'view' && id !== undefined && hosted.has(id)) hostedViews.set(id, child);
    else retained.push(graftNode(child, new Map()));
  }
  return { ...host, children: [...retained, ...root.nodes.map((node) => composeNode(node, root.viewId, hostedViews))] };
}

function composeNode(node: ComposableNode, rootViewId: number, hostedViews: ReadonlyMap<number, ViewNode>): UiNode {
  const id = `compose:${rootViewId}:${node.id}`;
  const hosted = node.hostedViewId === undefined || node.hostedViewId === null ? undefined : hostedViews.get(node.hostedViewId);
  return {
    type: 'compose', id, className: node.name, bounds: node.bounds, visible: true, alpha: 1,
    ...(node.name.length > 0 ? { semanticsRole: node.name } : {}), semanticProperties: {},
    ...(node.recomposeCount !== undefined && node.recomposeCount !== null ? { recomposeCount: node.recomposeCount } : {}),
    ...(node.skipCount !== undefined && node.skipCount !== null ? { skipCount: node.skipCount } : {}),
    children: [...node.children.map((child) => composeNode(child, rootViewId, hostedViews)), ...(hosted === undefined ? [] : [hosted])],
  };
}
function collectHostedIds(nodes: readonly ComposableNode[]): Set<number> { const ids = new Set<number>(); const visit = (node: ComposableNode): void => { if (node.hostedViewId !== undefined && node.hostedViewId !== null) ids.add(node.hostedViewId); node.children.forEach(visit); }; nodes.forEach(visit); return ids; }
function numericViewId(id: string): number | undefined { const match = /^view:(\d+)$/.exec(id); return match === null ? undefined : Number(match[1]); }
