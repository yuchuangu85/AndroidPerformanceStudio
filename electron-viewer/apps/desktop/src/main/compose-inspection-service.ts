import {
  decodeComposeInspectionFrame,
  decodeViewInspection,
  encodeDumpViewsCommand,
  encodeGetComposablesCommand,
  graftComposeInspection,
  type ComposableNode,
  type ComposeInspectionFrame,
  type LayoutSnapshot,
} from '@aps/layout-inspector';
import {
  COMPOSE_INSPECTOR_ID,
  COMPOSE_UI_LIBRARY_ID,
  connectUiInspector,
  type UiInspectorClient,
} from './ui-inspector-client.js';

export const VIEW_INSPECTOR_ID = 'ui.inspector.inspectors.view.inspector';
const SOCKET_PREFIX = 'ui_inspector_';
const TIMEOUT_MS = 15_000;
const DEFAULT_MAX_ATTEMPTS = 3;

type ViewInspectionCapture = ReturnType<typeof decodeViewInspection>;

export interface ComposeInspectionAdb {
  shell(args: readonly string[], options: { readonly timeoutMs: number }): Promise<{ readonly stdout: string }>;
  forward(local: string, remote: string, options: { readonly timeoutMs: number }): Promise<{ readonly stdout: string }>;
  removeForward(local: string, options: { readonly timeoutMs: number }): Promise<unknown>;
}

export interface RunningComposeAgentOptions {
  /** Required when the agent was deployed by deployComposeAgent. */
  readonly sessionToken?: string;
  /** Deploy helper's private View Inspector JAR; created before version discovery when supplied. */
  readonly viewInspectorPath?: string;
  /** Resolved, version-matched Compose inspector JAR; created after version discovery when supplied. */
  readonly composeInspectorPath?: string;
  /** Allows a verified deployment session to resolve and stage the version-matched JAR after discovery. */
  readonly resolveComposeInspectorPath?: (composeVersion: string) => Promise<string>;
  /** Rejects a target whose reported Compose version changed after artifact resolution. */
  readonly expectedComposeVersion?: string;
  readonly maxAttempts?: number;
}

export interface ComposeAgentCapture {
  readonly snapshot: ReturnType<typeof graftComposeInspection>;
  readonly composeInspectionJson: string;
  readonly composeVersion: string;
}

export interface StableComposeFrameCaptureClient {
  captureViews(): Promise<ViewInspectionCapture>;
  captureTree(rootViewIds: readonly number[], generation: number): Promise<ComposeInspectionFrame>;
}

export interface StableComposeFrameCapture {
  readonly views: ViewInspectionCapture;
  readonly compose: ComposeInspectionFrame;
}

/**
 * Kotlin-equivalent bounded double-collect: View-A → Compose-A → View-B →
 * Compose-B → View-C. Only the middle View/Compose pair is returned, and only
 * when structural reads prove that it is surrounded by one stable UI state.
 */
export async function captureStableComposeFrame(
  client: StableComposeFrameCaptureClient,
  maxAttempts = DEFAULT_MAX_ATTEMPTS,
): Promise<StableComposeFrameCapture> {
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1) throw new Error('Compose capture attempts must be positive');
  let generation = 0;
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const viewA = await client.captureViews();
    const composeA = await client.captureTree(viewA.rootViewIds, generation++);
    const viewB = await client.captureViews();
    const composeB = await client.captureTree(viewB.rootViewIds, generation++);
    const viewC = await client.captureViews();
    if (
      sameViewStructure(viewA.snapshot, viewB.snapshot) &&
      sameViewStructure(viewB.snapshot, viewC.snapshot) &&
      sameComposeStructure(composeA, composeB) &&
      sameRootIds(composeA, viewA.rootViewIds) &&
      sameRootIds(composeB, viewB.rootViewIds)
    ) return { views: viewB, compose: composeB };
  }
  throw new Error('Target changed during Compose frame capture; try again');
}

/** Reads an injected AOSP agent. Agent deployment and artifact resolution remain separate verification boundaries. */
export async function captureFromRunningComposeAgent(
  adb: ComposeInspectionAdb,
  packageName: string,
  now: () => number,
  options: RunningComposeAgentOptions = {},
): Promise<ComposeAgentCapture> {
  const pid = await targetPid(adb, packageName);
  const forward = await adb.forward('tcp:0', `localabstract:${SOCKET_PREFIX}${pid}`, { timeoutMs: TIMEOUT_MS });
  const port = parseForwardPort(forward.stdout);
  try {
    const client = await connectUiInspector(port, { commandTimeoutMs: TIMEOUT_MS, sessionToken: options.sessionToken });
    try {
      if (options.viewInspectorPath !== undefined) await client.createInspector(VIEW_INSPECTOR_ID, options.viewInspectorPath);
      const composeVersion = (await client.getVersion([COMPOSE_UI_LIBRARY_ID])).get(COMPOSE_UI_LIBRARY_ID);
      if (composeVersion === undefined) throw new Error('Target process does not expose Jetpack Compose');
      if (options.expectedComposeVersion !== undefined && composeVersion !== options.expectedComposeVersion) {
        throw new Error(`Target Compose version changed: expected ${options.expectedComposeVersion}, got ${composeVersion}`);
      }
      const composeInspectorPath = options.resolveComposeInspectorPath === undefined
        ? options.composeInspectorPath
        : await options.resolveComposeInspectorPath(composeVersion);
      if (composeInspectorPath !== undefined) await client.createInspector(COMPOSE_INSPECTOR_ID, composeInspectorPath);

      const stable = await captureStableComposeFrame({
        captureViews: async () => decodeViewInspection(
          await client.sendInspectorCommand(VIEW_INSPECTOR_ID, encodeDumpViewsCommand(false)),
          packageName,
          now(),
        ),
        captureTree: async (rootViewIds, generation) => await captureComposeTrees(client, rootViewIds, pid, generation),
      }, options.maxAttempts);
      const snapshot = graftComposeInspection(stable.views.snapshot, stable.compose);
      return {
        snapshot,
        composeVersion,
        composeInspectionJson: JSON.stringify(stable.compose, (_key, value) => value instanceof Map ? Object.fromEntries(value) : value),
      };
    } finally {
      client.close();
    }
  } finally {
    await adb.removeForward(`tcp:${port}`, { timeoutMs: TIMEOUT_MS }).catch(() => undefined);
  }
}

async function captureComposeTrees(
  client: UiInspectorClient,
  rootViewIds: readonly number[],
  pid: number,
  generation: number,
): Promise<ComposeInspectionFrame> {
  const frames: ComposeInspectionFrame[] = [];
  for (const [index, rootViewId] of rootViewIds.entries()) {
    const payload = await client.sendInspectorCommand(
      COMPOSE_INSPECTOR_ID,
      encodeGetComposablesCommand(BigInt(rootViewId), false, generation),
    );
    frames.push(decodeComposeInspectionFrame(payload, `compose-${pid}-${generation}-${index}`, generation));
  }
  const first = frames[0];
  if (first === undefined) {
    throw new Error('View inspector did not return a root View ID for Compose capture');
  }
  return {
    ...first,
    roots: frames.flatMap((frame) => frame.roots),
    coverage: frames.flatMap((frame) => frame.coverage),
    truncations: frames.flatMap((frame) => frame.truncations),
    completeness: frames.some((frame) => frame.completeness !== 'COMPLETE') ? 'INCOMPLETE_RESOURCE_LIMIT' : 'COMPLETE',
  };
}

function sameViewStructure(first: LayoutSnapshot, second: LayoutSnapshot): boolean {
  return JSON.stringify({ ...first, capturedAtEpochMillis: 0 }) === JSON.stringify({ ...second, capturedAtEpochMillis: 0 });
}

function sameComposeStructure(first: ComposeInspectionFrame, second: ComposeInspectionFrame): boolean {
  return JSON.stringify(first.roots.map(stripObservationCounts)) === JSON.stringify(second.roots.map(stripObservationCounts));
}

function stripObservationCounts(root: ComposeInspectionFrame['roots'][number]): object {
  return { ...root, nodes: root.nodes.map(stripNodeObservationCounts) };
}

function stripNodeObservationCounts(node: ComposableNode): object {
  // JSON structural comparison omits undefined fields, matching Kotlin's copy-with-null-counts comparison.
  return { ...node, recomposeCount: undefined, skipCount: undefined, children: node.children.map(stripNodeObservationCounts) };
}

function sameRootIds(frame: ComposeInspectionFrame, rootViewIds: readonly number[]): boolean {
  return frame.roots.length === rootViewIds.length && frame.roots.every((root, index) => root.viewId === rootViewIds[index]);
}

async function targetPid(adb: ComposeInspectionAdb, packageName: string): Promise<number> {
  const pids = (await adb.shell(['pidof', packageName], { timeoutMs: TIMEOUT_MS })).stdout.trim().split(/\s+/).filter(Boolean).map(Number).filter((pid) => Number.isInteger(pid) && pid > 0);
  if (pids.length !== 1) throw new Error(pids.length === 0 ? 'Target process is not running' : 'Target app has multiple processes');
  return pids[0] as number;
}

function parseForwardPort(stdout: string): number {
  const port = Number(stdout.trim());
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('ADB did not return a loopback forward port');
  return port;
}
