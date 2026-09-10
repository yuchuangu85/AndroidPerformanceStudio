import { z } from 'zod';
import {
  agentCapabilitiesSchema,
  displayInfoSchema,
  parseUiNode,
  protocolVersionSchema,
  type AgentCapabilities,
  type Bounds,
  type DisplayInfo,
  type ProtocolVersion,
  type UiNode,
} from './model.js';

export type WindowType = 'ACTIVITY' | 'DIALOG' | 'POPUP' | 'OTHER';

export interface WindowSnapshot {
  readonly id: string;
  readonly title: string;
  readonly type: WindowType;
  readonly bounds: Bounds;
  readonly root: UiNode;
}

export interface LayoutSnapshot {
  readonly protocolVersion: ProtocolVersion;
  readonly packageName: string;
  readonly capturedAtEpochMillis: number;
  readonly display: DisplayInfo;
  readonly capabilities: AgentCapabilities;
  readonly root: UiNode;
  readonly windows: readonly WindowSnapshot[];
  readonly defaultWindowId?: string;
}

export const LEGACY_WINDOW_ID = 'window:legacy';

const windowTypeSchema = z.enum(['ACTIVITY', 'DIALOG', 'POPUP', 'OTHER']);

export const layoutSnapshotWireSchema = z.object({
  protocolVersion: protocolVersionSchema,
  packageName: z.string(),
  // Epoch milliseconds stay well inside JavaScript's safe integer range.
  capturedAtEpochMillis: z.number().int().nonnegative(),
  display: displayInfoSchema,
  capabilities: agentCapabilitiesSchema,
  root: z.unknown(),
  windows: z
    .array(
      z.object({
        id: z.string(),
        title: z.string(),
        type: windowTypeSchema.default('OTHER'),
        bounds: z.object({
          left: z.number().int(),
          top: z.number().int(),
          right: z.number().int(),
          bottom: z.number().int(),
        }),
        root: z.unknown(),
      }),
    )
    .default([]),
  defaultWindowId: z.string().nullable().default(null),
});

export function effectiveWindows(snapshot: LayoutSnapshot): WindowSnapshot[] {
  if (snapshot.windows.length > 0) return [...snapshot.windows];
  return [
    {
      id: LEGACY_WINDOW_ID,
      title: snapshot.packageName.split('.').pop() ?? snapshot.packageName,
      type: 'OTHER',
      bounds: snapshot.root.bounds,
      root: snapshot.root,
    },
  ];
}

export function effectiveDefaultWindowId(snapshot: LayoutSnapshot): string {
  const windows = effectiveWindows(snapshot);
  const candidate = snapshot.defaultWindowId;
  if (candidate !== undefined && windows.some((window) => window.id === candidate)) return candidate;
  return windows[0]?.id ?? LEGACY_WINDOW_ID;
}

export function normalizedToCurrentProtocol(snapshot: LayoutSnapshot): LayoutSnapshot {
  const windows = effectiveWindows(snapshot);
  const defaultWindowId = effectiveDefaultWindowId(snapshot);
  const root = windows.find((window) => window.id === defaultWindowId)?.root ?? snapshot.root;
  return { ...snapshot, root, windows, defaultWindowId };
}

export function decodeWireSnapshot(value: unknown): LayoutSnapshot {
  const wire = layoutSnapshotWireSchema.parse(value);
  return {
    protocolVersion: wire.protocolVersion,
    packageName: wire.packageName,
    capturedAtEpochMillis: wire.capturedAtEpochMillis,
    display: wire.display,
    capabilities: wire.capabilities,
    root: parseUiNode(wire.root),
    windows: wire.windows.map((window) => ({
      id: window.id,
      title: window.title,
      type: window.type,
      bounds: window.bounds,
      root: parseUiNode(window.root),
    })),
    ...(wire.defaultWindowId !== null ? { defaultWindowId: wire.defaultWindowId } : {}),
  };
}
