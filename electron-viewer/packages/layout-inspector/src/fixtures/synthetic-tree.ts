/**
 * Deterministic synthetic hierarchies for the D2 node-count gate.
 *
 * The shape matters more than the size: a linked list of 10,000 nodes would
 * make the flatten trivial and the hit test trivial, and the gate would prove
 * nothing. This builds a wide-and-deep Android-like tree instead, with a mix of
 * containers and leaves, invisible subtrees, ids, resource names, and bounds
 * that nest inside their parent.
 */
import type { Bounds, DisplayInfo, UiNode, ViewNode } from '../model.js';
import { CURRENT_PROTOCOL_VERSION, PROTOCOL_VERSION_1_1 } from '../model.js';
import type { LayoutSnapshot, WindowSnapshot } from '../snapshot.js';

export interface SyntheticTreeOptions {
  /** Exact node count; the generator stops as soon as it is reached. */
  readonly nodeCount?: number;
  /** Children per container. Five keeps the depth near thirty at ten thousand. */
  readonly fanOut?: number;
  /** Share of nodes marked not visible, as a real hierarchy has. */
  readonly invisibleRatio?: number;
  readonly display?: DisplayInfo;
}

export const DEFAULT_SYNTHETIC_NODES = 10_000;
export const DEFAULT_FAN_OUT = 5;
export const DEFAULT_INVISIBLE_RATIO = 0.1;

const CONTAINER_CLASSES = [
  'android.widget.FrameLayout',
  'android.widget.LinearLayout',
  'android.widget.RelativeLayout',
  'androidx.constraintlayout.widget.ConstraintLayout',
  'androidx.recyclerview.widget.RecyclerView',
  'android.widget.ScrollView',
] as const;

const LEAF_CLASSES = [
  'android.widget.TextView',
  'android.widget.ImageView',
  'android.widget.Button',
  'android.widget.ProgressBar',
  'android.view.View',
] as const;

const RESOURCE_PREFIXES = ['layout', 'id', 'drawable', 'string'] as const;

const DEFAULT_DISPLAY: DisplayInfo = { widthPx: 1080, heightPx: 2400, density: 3 };

/** A deterministic generator: the same options always produce the same tree. */
export function syntheticTree(options: SyntheticTreeOptions = {}): LayoutSnapshot {
  const nodeCount = options.nodeCount ?? DEFAULT_SYNTHETIC_NODES;
  const fanOut = options.fanOut ?? DEFAULT_FAN_OUT;
  const invisibleRatio = options.invisibleRatio ?? DEFAULT_INVISIBLE_RATIO;
  const display = options.display ?? DEFAULT_DISPLAY;
  let created = 0;

  const invisibleAt = (index: number): boolean => {
    if (index === 0) return false;
    // A fixed stride rather than a random draw, so the fixture is reproducible.
    return (index * 7) % 100 < invisibleRatio * 100;
  };

  const build = (index: number, depth: number, bounds: Bounds): UiNode => {
    created += 1;
    const isContainer = depth < 24 && (index % 3 !== 2);
    const className = isContainer
      ? (CONTAINER_CLASSES[index % CONTAINER_CLASSES.length] ?? CONTAINER_CLASSES[0])
      : (LEAF_CLASSES[index % LEAF_CLASSES.length] ?? LEAF_CLASSES[0]);
    const children: UiNode[] = [];
    if (isContainer) {
      const sliceHeight = Math.max(1, Math.floor((bounds.bottom - bounds.top) / fanOut));
      for (let child = 0; child < fanOut; child += 1) {
        // The budget is checked before every node, so the tree stops exactly
        // at nodeCount instead of overshooting its last level.
        if (created >= nodeCount) break;
        const top = bounds.top + child * sliceHeight;
        const bottom = child === fanOut - 1 ? bounds.bottom : Math.min(bounds.bottom, top + sliceHeight);
        children.push(
          build(created, depth + 1, {
            left: bounds.left + 4,
            top: top + 4,
            right: Math.max(bounds.left + 8, bounds.right - 4),
            bottom: Math.max(top + 8, bottom - 4),
          }),
        );
      }
    }
    const node: ViewNode = {
      type: 'view',
      id: 'node:' + String(index),
      className,
      bounds,
      visible: !invisibleAt(index),
      alpha: 1,
      children,
      resourceName:
        (RESOURCE_PREFIXES[index % RESOURCE_PREFIXES.length] ?? 'id') + '/view_' + String(index),
      attributes: { rawProperties: {} },
    };
    return node;
  };

  const root = build(0, 0, { left: 0, top: 0, right: display.widthPx, bottom: display.heightPx });
  return {
    protocolVersion: PROTOCOL_VERSION_1_1,
    packageName: 'com.example.synthetic',
    capturedAtEpochMillis: 0,
    display,
    capabilities: { viewHierarchy: true, composeSemantics: false, screenshots: false, timeline: false },
    root,
    windows: [
      {
        id: 'window:main',
        title: 'Synthetic',
        type: 'ACTIVITY',
        bounds: { left: 0, top: 0, right: display.widthPx, bottom: display.heightPx },
        root,
      } satisfies WindowSnapshot,
    ],
    defaultWindowId: 'window:main',
  };
}

/** The wire form the capture store reads back, so the gate measures the real path. */
export function syntheticWireSnapshot(options: SyntheticTreeOptions = {}): string {
  const snapshot = syntheticTree(options);
  return JSON.stringify({
    protocolVersion: CURRENT_PROTOCOL_VERSION,
    packageName: snapshot.packageName,
    capturedAtEpochMillis: snapshot.capturedAtEpochMillis,
    display: snapshot.display,
    capabilities: snapshot.capabilities,
    root: snapshot.root,
    windows: snapshot.windows,
    defaultWindowId: snapshot.defaultWindowId,
  });
}
