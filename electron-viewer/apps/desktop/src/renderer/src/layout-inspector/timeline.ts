import { effectiveWindows, type LayoutSnapshot } from '@aps/layout-inspector';
import type { UiNode } from '@aps/layout-inspector';
import type { LayoutCaptureDetail } from '../../../shared/ipc';

export const MAX_TIMELINE_FRAMES = 50;

export type TimelineChangeType = 'added' | 'removed' | 'changed';

export interface TimelineNodeChange {
  readonly type: TimelineChangeType;
  readonly windowId: string;
  readonly nodeId: string;
  readonly nodeKey: string;
  readonly className: string;
  readonly changedProperties: readonly string[];
}

export interface TimelineDiff {
  readonly previousCapturedAtEpochMillis: number;
  readonly currentCapturedAtEpochMillis: number;
  readonly addedNodes: number;
  readonly removedNodes: number;
  readonly boundsChangedNodes: number;
  readonly changes: readonly TimelineNodeChange[];
}

export interface TimelineFrame {
  readonly index: number;
  readonly detail: LayoutCaptureDetail;
  readonly captureId?: string;
  readonly diffFromPrevious: TimelineDiff | null;
}

interface NodeFingerprint {
  readonly windowId: string;
  readonly nodeId: string;
  readonly nodeKey: string;
  readonly className: string;
  readonly bounds: UiNode['bounds'];
  readonly visible: boolean;
  readonly alpha: number;
  readonly text?: string;
  readonly resourceName?: string;
  readonly contentDescription?: string;
  readonly semanticsRole?: string;
  readonly semanticProperties: Readonly<Record<string, string>>;
  readonly node: UiNode;
}

interface NodeMatch {
  readonly previous: NodeFingerprint;
  readonly current: NodeFingerprint;
}

interface IndexedNode {
  readonly index: number;
  readonly node: UiNode;
}

export interface RemovedTimelineFrame {
  readonly frames: readonly TimelineFrame[];
  readonly selectedIndex: number | null;
  readonly selectedFrame: TimelineFrame | null;
}

export function appendTimelineFrame(
  frames: readonly TimelineFrame[],
  detail: LayoutCaptureDetail,
  captureId?: string,
): { readonly frames: readonly TimelineFrame[]; readonly selectedIndex: number } {
  const previous = frames.at(-1)?.detail.snapshot;
  const selectedIndex = (frames.at(-1)?.index ?? -1) + 1;
  const frame: TimelineFrame = {
    index: selectedIndex,
    detail,
    ...(captureId !== undefined ? { captureId } : {}),
    diffFromPrevious: previous === undefined ? null : diffSnapshots(previous, detail.snapshot),
  };
  return { frames: [...frames, frame].slice(-MAX_TIMELINE_FRAMES), selectedIndex };
}

export function removeTimelineFrame(
  frames: readonly TimelineFrame[],
  selectedIndex: number | null,
  removedIndex: number,
): RemovedTimelineFrame {
  if (!frames.some((frame) => frame.index === removedIndex)) {
    return {
      frames,
      selectedIndex,
      selectedFrame: frames.find((frame) => frame.index === selectedIndex) ?? null,
    };
  }

  const remaining = frames.filter((frame) => frame.index !== removedIndex);
  if (selectedIndex !== removedIndex) {
    return {
      frames: remaining,
      selectedIndex,
      selectedFrame: remaining.find((frame) => frame.index === selectedIndex) ?? null,
    };
  }

  const replacement = remaining.find((frame) => frame.index > removedIndex) ?? remaining.at(-1) ?? null;
  return {
    frames: remaining,
    selectedIndex: replacement?.index ?? null,
    selectedFrame: replacement,
  };
}

export function diffSnapshots(previous: LayoutSnapshot, current: LayoutSnapshot): TimelineDiff {
  const previousNodes = flattenNodesByAddress(previous);
  const currentNodes = flattenNodesByAddress(current);
  const matches = matchNodes(previous, current);
  const matchedPrevious = new Set(matches.map((match) => address(match.previous)));
  const matchedCurrent = new Set(matches.map((match) => address(match.current)));

  const added = [...currentNodes.entries()]
    .filter(([key]) => !matchedCurrent.has(key))
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([, node]) => toChange(node, 'added'));
  const removed = [...previousNodes.entries()]
    .filter(([key]) => !matchedPrevious.has(key))
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([, node]) => toChange(node, 'removed'));
  const changed = [...matches]
    .sort((left, right) => left.current.nodeKey.localeCompare(right.current.nodeKey))
    .flatMap((match) => {
      const changedProperties = diffProperties(match.previous, match.current);
      return changedProperties.length === 0 ? [] : [toChange(match.current, 'changed', changedProperties)];
    });

  return {
    previousCapturedAtEpochMillis: previous.capturedAtEpochMillis,
    currentCapturedAtEpochMillis: current.capturedAtEpochMillis,
    addedNodes: added.length,
    removedNodes: removed.length,
    boundsChangedNodes: matches.filter((match) => !equalBounds(match.previous.bounds, match.current.bounds)).length,
    changes: [...added, ...removed, ...changed],
  };
}

function flattenNodesByAddress(snapshot: LayoutSnapshot): Map<string, NodeFingerprint> {
  const nodes = new Map<string, NodeFingerprint>();
  for (const window of effectiveWindows(snapshot)) {
    const pending = [window.root];
    while (pending.length > 0) {
      const node = pending.shift();
      if (node === undefined) break;
      const fingerprint = toFingerprint(window.id, node);
      nodes.set(address(fingerprint), fingerprint);
      pending.push(...node.children);
    }
  }
  return nodes;
}

function matchNodes(previous: LayoutSnapshot, current: LayoutSnapshot): NodeMatch[] {
  const previousWindows = new Map(effectiveWindows(previous).map((window) => [window.id, window]));
  const currentWindows = new Map(effectiveWindows(current).map((window) => [window.id, window]));
  const pending: NodeMatch[] = [...previousWindows.keys()]
    .filter((windowId) => currentWindows.has(windowId))
    .sort()
    .map((windowId) => ({
      previous: toFingerprint(windowId, previousWindows.get(windowId)!.root),
      current: toFingerprint(windowId, currentWindows.get(windowId)!.root),
    }));
  const matches: NodeMatch[] = [];
  const matchedPrevious = new Set<string>();
  const matchedCurrent = new Set<string>();

  while (pending.length > 0) {
    const match = pending.shift();
    if (match === undefined) break;
    const previousAddress = address(match.previous);
    const currentAddress = address(match.current);
    if (matchedPrevious.has(previousAddress) || matchedCurrent.has(currentAddress)) continue;
    matchedPrevious.add(previousAddress);
    matchedCurrent.add(currentAddress);
    matches.push(match);
    for (const [before, after] of matchSiblingNodes(match.previous.node.children, match.current.node.children)) {
      pending.push({
        previous: toFingerprint(match.previous.windowId, before),
        current: toFingerprint(match.current.windowId, after),
      });
    }
  }
  return matches;
}

function matchSiblingNodes(previous: readonly UiNode[], current: readonly UiNode[]): [UiNode, UiNode][] {
  const previousNodes = previous.map((node, index): IndexedNode => ({ index, node }));
  const currentNodes = current.map((node, index): IndexedNode => ({ index, node }));
  const matchedPrevious = new Set<number>();
  const matchedCurrent = new Set<number>();
  const matches: [IndexedNode, IndexedNode][] = [];

  const matchUniqueBy = (identity: (node: UiNode) => string | undefined): void => {
    const previousByKey = uniqueCandidates(previousNodes, matchedPrevious, identity);
    const currentByKey = uniqueCandidates(currentNodes, matchedCurrent, identity);
    for (const key of [...previousByKey.keys()].filter((candidate) => currentByKey.has(candidate)).sort()) {
      const before = previousByKey.get(key);
      const after = currentByKey.get(key);
      if (before?.length === 1 && after?.length === 1) {
        const previousNode = before[0]!;
        const currentNode = after[0]!;
        matchedPrevious.add(previousNode.index);
        matchedCurrent.add(currentNode.index);
        matches.push([previousNode, currentNode]);
      }
    }
  };

  matchUniqueBy(intrinsicIdentity);
  matchUniqueBy(declaredIdentity);
  matchUniqueBy(disambiguatedIdentity);

  const structureSupportsPositionMatching =
    previous.length === current.length && matches.every(([before, after]) => before.index === after.index);
  if (structureSupportsPositionMatching) {
    for (let index = 0; index < previousNodes.length; index += 1) {
      if (!matchedPrevious.has(index) && !matchedCurrent.has(index)) {
        const before = previousNodes[index]!;
        const after = currentNodes[index]!;
        if (hasCompatibleWeakIdentity(before.node, after.node)) {
          matchedPrevious.add(index);
          matchedCurrent.add(index);
          matches.push([before, after]);
        }
      }
    }
  }

  return matches.sort(([left], [right]) => left.index - right.index).map(([before, after]) => [before.node, after.node]);
}

function uniqueCandidates(
  nodes: readonly IndexedNode[],
  matched: ReadonlySet<number>,
  identity: (node: UiNode) => string | undefined,
): Map<string, IndexedNode[]> {
  const grouped = new Map<string, IndexedNode[]>();
  for (const indexed of nodes) {
    if (matched.has(indexed.index)) continue;
    const key = identity(indexed.node);
    if (key === undefined) continue;
    grouped.set(key, [...(grouped.get(key) ?? []), indexed]);
  }
  return grouped;
}

function intrinsicIdentity(node: UiNode): string | undefined {
  if (node.type === 'compose') {
    const semanticsId = node.id.split('/').at(-1);
    if (semanticsId !== undefined && /^\d+$/.test(semanticsId)) return `compose:semantics-id:${semanticsId}`;
    const nodeId = explicitNodeId(node);
    return nodeId === undefined ? undefined : `compose:node-id:${nodeId}`;
  }
  const nodeId = explicitNodeId(node);
  return nodeId === undefined ? undefined : `view:node-id:${nodeId}`;
}

function declaredIdentity(node: UiNode): string | undefined {
  if (node.type === 'view') {
    return nonBlank(node.resourceName) === undefined ? undefined : `view:resource:${node.resourceName}`;
  }
  const testTag = nonBlank(node.semanticProperties.TestTag);
  return testTag === undefined ? undefined : `compose:test-tag:${testTag}`;
}

function disambiguatedIdentity(node: UiNode): string | undefined {
  const base = declaredIdentity(node);
  if (base === undefined) return undefined;
  const auxiliaryValues = node.type === 'view'
    ? [node.text, node.attributes.contentDescription]
    : [node.text, node.semanticProperties.ContentDescription];
  const values = auxiliaryValues.map(nonBlank).filter((value): value is string => value !== undefined);
  if (values.length === 0) return undefined;
  return `${base}|class:${node.className}${values.map((value) => `|value:${value}`).join('')}`;
}

function explicitNodeId(node: UiNode): string | undefined {
  const finalSegment = node.id.split('/').at(-1) ?? '';
  return finalSegment.length > 0 && finalSegment !== 'root' && /\D/.test(finalSegment) ? node.id : undefined;
}

function hasCompatibleWeakIdentity(previous: UiNode, current: UiNode): boolean {
  if (previous.id !== current.id || previous.className !== current.className || previous.type !== current.type) return false;
  if (previous.type === 'view' && current.type === 'view') return previous.resourceName === current.resourceName;
  if (previous.type === 'compose' && current.type === 'compose') {
    return previous.semanticProperties.TestTag === current.semanticProperties.TestTag;
  }
  return false;
}

function toFingerprint(windowId: string, node: UiNode): NodeFingerprint {
  return {
    windowId,
    nodeId: node.id,
    nodeKey: `${windowId}:${node.id}`,
    className: node.className,
    bounds: node.bounds,
    visible: node.visible,
    alpha: node.alpha,
    ...(node.text !== undefined ? { text: node.text } : {}),
    ...(node.type === 'view' && node.resourceName !== undefined ? { resourceName: node.resourceName } : {}),
    ...(node.type === 'view' && node.attributes.contentDescription !== undefined
      ? { contentDescription: node.attributes.contentDescription }
      : {}),
    ...(node.type === 'compose' && node.semanticsRole !== undefined ? { semanticsRole: node.semanticsRole } : {}),
    semanticProperties: node.type === 'compose' ? node.semanticProperties : {},
    node,
  };
}

function diffProperties(previous: NodeFingerprint, current: NodeFingerprint): string[] {
  const properties: string[] = [];
  if (!equalBounds(previous.bounds, current.bounds)) properties.push('bounds');
  if (previous.className !== current.className) properties.push('className');
  if (previous.visible !== current.visible) properties.push('visible');
  if (previous.alpha !== current.alpha) properties.push('alpha');
  if (previous.text !== current.text) properties.push('text');
  if (previous.resourceName !== current.resourceName) properties.push('resourceName');
  if (previous.contentDescription !== current.contentDescription) properties.push('contentDescription');
  if (previous.semanticsRole !== current.semanticsRole) properties.push('semanticsRole');
  if (!equalProperties(previous.semanticProperties, current.semanticProperties)) properties.push('semanticProperties');
  return properties.sort();
}

function toChange(
  node: NodeFingerprint,
  type: TimelineChangeType,
  changedProperties: readonly string[] = [],
): TimelineNodeChange {
  return {
    type,
    windowId: node.windowId,
    nodeId: node.nodeId,
    nodeKey: node.nodeKey,
    className: node.className,
    changedProperties,
  };
}

function address(node: NodeFingerprint): string {
  return `${node.windowId}:${node.nodeId}`;
}

function nonBlank(value: string | undefined): string | undefined {
  return value !== undefined && value.trim().length > 0 ? value : undefined;
}

function equalBounds(left: UiNode['bounds'], right: UiNode['bounds']): boolean {
  return left.left === right.left && left.top === right.top && left.right === right.right && left.bottom === right.bottom;
}

function equalProperties(
  left: Readonly<Record<string, string>>,
  right: Readonly<Record<string, string>>,
): boolean {
  const leftKeys = Object.keys(left).sort();
  const rightKeys = Object.keys(right).sort();
  return leftKeys.length === rightKeys.length && leftKeys.every((key, index) => key === rightKeys[index] && left[key] === right[key]);
}
