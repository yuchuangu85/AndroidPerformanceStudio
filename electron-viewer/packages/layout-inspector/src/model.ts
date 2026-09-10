import { z } from 'zod';

export interface ProtocolVersion {
  readonly major: number;
  readonly minor: number;
}

export const PROTOCOL_VERSION_1_0: ProtocolVersion = { major: 1, minor: 0 };
export const PROTOCOL_VERSION_1_1: ProtocolVersion = { major: 1, minor: 1 };
export const CURRENT_PROTOCOL_VERSION: ProtocolVersion = PROTOCOL_VERSION_1_1;
export const SUPPORTED_PROTOCOL_MAJOR = 1;

export const protocolVersionSchema = z.object({
  major: z.number().int(),
  minor: z.number().int(),
});

export interface AgentCapabilities {
  readonly viewHierarchy: boolean;
  readonly composeSemantics: boolean;
  readonly screenshots: boolean;
  readonly timeline: boolean;
}

export const agentCapabilitiesSchema = z.object({
  viewHierarchy: z.boolean().default(false),
  composeSemantics: z.boolean().default(false),
  screenshots: z.boolean().default(false),
  timeline: z.boolean().default(false),
});

export interface DisplayInfo {
  readonly widthPx: number;
  readonly heightPx: number;
  readonly density: number;
}

export const displayInfoSchema = z.object({
  widthPx: z.number().int().nonnegative(),
  heightPx: z.number().int().nonnegative(),
  density: z.number().nonnegative(),
});

export interface Bounds {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

export const boundsSchema = z.object({
  left: z.number().int(),
  top: z.number().int(),
  right: z.number().int(),
  bottom: z.number().int(),
});

export function boundsWidth(bounds: Bounds): number {
  return Math.max(0, bounds.right - bounds.left);
}

export function boundsHeight(bounds: Bounds): number {
  return Math.max(0, bounds.bottom - bounds.top);
}

export function boundsArea(bounds: Bounds): number {
  return boundsWidth(bounds) * boundsHeight(bounds);
}

export interface EdgeInsets {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

export const edgeInsetsSchema = z.object({
  left: z.number().int().default(0),
  top: z.number().int().default(0),
  right: z.number().int().default(0),
  bottom: z.number().int().default(0),
});

/** Full ViewAttributes set from ProtocolModels.kt; agent-only fields stay optional. */
export interface ViewAttributes {
  readonly visibility?: string;
  readonly layoutBounds?: Bounds;
  readonly elevation?: number;
  readonly z?: number;
  readonly translationX?: number;
  readonly translationY?: number;
  readonly translationZ?: number;
  readonly rotation?: number;
  readonly rotationX?: number;
  readonly rotationY?: number;
  readonly scaleX?: number;
  readonly scaleY?: number;
  readonly pivotX?: number;
  readonly pivotY?: number;
  readonly padding?: EdgeInsets;
  readonly margin?: EdgeInsets;
  readonly layoutWidth?: number;
  readonly layoutHeight?: number;
  readonly layoutParamsClass?: string;
  readonly measuredWidth?: number;
  readonly measuredHeight?: number;
  readonly minWidth?: number;
  readonly minHeight?: number;
  readonly scrollX?: number;
  readonly scrollY?: number;
  readonly clipBounds?: Bounds;
  readonly clipChildren?: boolean;
  readonly clipToPadding?: boolean;
  readonly background?: string;
  readonly backgroundColor?: string;
  readonly foreground?: string;
  readonly opaque?: boolean;
  readonly willNotDraw?: boolean;
  readonly hardwareAccelerated?: boolean;
  readonly layerType?: string;
  readonly layoutRequested?: boolean;
  readonly enabled?: boolean;
  readonly clickable?: boolean;
  readonly longClickable?: boolean;
  readonly focusable?: boolean;
  readonly focused?: boolean;
  readonly selected?: boolean;
  readonly contentDescription?: string;
  readonly rawProperties: Readonly<Record<string, string>>;
}

export const viewAttributesSchema = z.object({
  visibility: z.string().optional(),
  layoutBounds: boundsSchema.optional(),
  elevation: z.number().optional(),
  z: z.number().optional(),
  translationX: z.number().optional(),
  translationY: z.number().optional(),
  translationZ: z.number().optional(),
  rotation: z.number().optional(),
  rotationX: z.number().optional(),
  rotationY: z.number().optional(),
  scaleX: z.number().optional(),
  scaleY: z.number().optional(),
  pivotX: z.number().optional(),
  pivotY: z.number().optional(),
  padding: edgeInsetsSchema.optional(),
  margin: edgeInsetsSchema.optional(),
  layoutWidth: z.number().int().optional(),
  layoutHeight: z.number().int().optional(),
  layoutParamsClass: z.string().optional(),
  measuredWidth: z.number().int().optional(),
  measuredHeight: z.number().int().optional(),
  minWidth: z.number().int().optional(),
  minHeight: z.number().int().optional(),
  scrollX: z.number().int().optional(),
  scrollY: z.number().int().optional(),
  clipBounds: boundsSchema.optional(),
  clipChildren: z.boolean().optional(),
  clipToPadding: z.boolean().optional(),
  background: z.string().optional(),
  backgroundColor: z.string().optional(),
  foreground: z.string().optional(),
  opaque: z.boolean().optional(),
  willNotDraw: z.boolean().optional(),
  hardwareAccelerated: z.boolean().optional(),
  layerType: z.string().optional(),
  layoutRequested: z.boolean().optional(),
  enabled: z.boolean().optional(),
  clickable: z.boolean().optional(),
  longClickable: z.boolean().optional(),
  focusable: z.boolean().optional(),
  focused: z.boolean().optional(),
  selected: z.boolean().optional(),
  contentDescription: z.string().optional(),
  rawProperties: z.record(z.string(), z.string()).default({}),
});

export interface ViewNode {
  readonly type: 'view';
  readonly id: string;
  readonly className: string;
  readonly bounds: Bounds;
  readonly visible: boolean;
  readonly alpha: number;
  readonly children: readonly UiNode[];
  readonly resourceName?: string;
  readonly text?: string;
  readonly attributes: ViewAttributes;
}

export interface ComposeNode {
  readonly type: 'compose';
  readonly id: string;
  readonly className: string;
  readonly bounds: Bounds;
  readonly visible: boolean;
  readonly alpha: number;
  readonly children: readonly UiNode[];
  readonly semanticsRole?: string;
  readonly text?: string;
  readonly semanticProperties: Readonly<Record<string, string>>;
}

export type UiNode = ViewNode | ComposeNode;

const nonBlank = z.string().refine((value) => value.trim().length > 0, { message: 'must not be blank' });

/** One node without recursion; children are normalized separately. */
export const rawUiNodeSchema = z.object({
  type: z.enum(['view', 'compose']).default('view'),
  id: nonBlank,
  className: nonBlank,
  bounds: boundsSchema,
  visible: z.boolean().default(true),
  alpha: z.number().default(1),
  children: z.array(z.unknown()).default([]),
  resourceName: z.string().optional(),
  text: z.string().optional(),
  attributes: viewAttributesSchema.optional(),
  semanticsRole: z.string().optional(),
  semanticProperties: z.record(z.string(), z.string()).optional(),
});

export function parseUiNode(value: unknown): UiNode {
  const raw = rawUiNodeSchema.parse(value);
  const children = raw.children.map(parseUiNode);
  if (raw.type === 'compose') {
    return {
      type: 'compose',
      id: raw.id,
      className: raw.className,
      bounds: raw.bounds,
      visible: raw.visible,
      alpha: raw.alpha,
      children,
      ...(raw.semanticsRole !== undefined ? { semanticsRole: raw.semanticsRole } : {}),
      ...(raw.text !== undefined ? { text: raw.text } : {}),
      semanticProperties: raw.semanticProperties ?? {},
    };
  }
  return {
    type: 'view',
    id: raw.id,
    className: raw.className,
    bounds: raw.bounds,
    visible: raw.visible,
    alpha: raw.alpha,
    children,
    ...(raw.resourceName !== undefined ? { resourceName: raw.resourceName } : {}),
    ...(raw.text !== undefined ? { text: raw.text } : {}),
    attributes: raw.attributes ?? { rawProperties: {} },
  };
}

export function walkNode(node: UiNode, visit: (node: UiNode, depth: number) => void, depth = 0): void {
  visit(node, depth);
  for (const child of node.children) walkNode(child, visit, depth + 1);
}
