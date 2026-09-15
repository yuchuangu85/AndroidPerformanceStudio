import { describe, expect, it } from 'vitest';
import type { ComposeInspectionFrame, LayoutSnapshot } from '@aps/layout-inspector';
import { captureStableComposeFrame, type StableComposeFrameCaptureClient } from './compose-inspection-service.js';

function views(tag: string, capturedAtEpochMillis: number): LayoutSnapshot {
  const root = { type: 'view' as const, id: `view:${tag}`, className: 'android.view.View', bounds: { left: 0, top: 0, right: 1, bottom: 1 }, visible: true, alpha: 1, children: [], attributes: { rawProperties: {} } };
  return { protocolVersion: { major: 1, minor: 1 }, packageName: 'dev.sample', capturedAtEpochMillis, display: { widthPx: 1, heightPx: 1, density: 1 }, capabilities: { viewHierarchy: true, composeSemantics: false, screenshots: false, timeline: false }, root, windows: [{ id: 'window:1', title: 'sample', type: 'ACTIVITY', bounds: root.bounds, root }], defaultWindowId: 'window:1' };
}

function compose(rootIds: readonly number[], generation: number, observations = 0): ComposeInspectionFrame {
  return {
    frameId: `frame-${generation}`, generation, mode: 'FULL', capabilities: [], details: new Map(), coverage: [], completeness: 'COMPLETE', truncations: [],
    roots: rootIds.map((viewId) => ({ viewId, viewsToSkip: [], nodes: [{ id: viewId, anchorHash: 0, name: 'Content', bounds: { left: 0, top: 0, right: 1, bottom: 1 }, systemCreated: false, flags: [], recomposeCount: observations, skipCount: observations, children: [] }] })),
  };
}

function scriptedClient(
  viewSteps: readonly { readonly tag: string; readonly roots?: readonly number[] }[],
  treeSteps: readonly { readonly roots: readonly number[]; readonly observations?: number }[],
  calls: string[],
): StableComposeFrameCaptureClient {
  let viewIndex = 0;
  let treeIndex = 0;
  return {
    captureViews: async () => {
      const step = viewSteps[viewIndex++]!;
      const rootViewIds = step.roots ?? [10];
      calls.push(`view:${step.tag}`);
      return { snapshot: views(step.tag, viewIndex), rootViewIds: [...rootViewIds] };
    },
    captureTree: async (rootViewIds, generation) => {
      const step = treeSteps[treeIndex++]!;
      calls.push(`compose:${generation}:${rootViewIds.join(',')}`);
      expect(rootViewIds).toEqual(step.roots);
      return compose(step.roots, generation, step.observations ?? 0);
    },
  };
}

describe('captureStableComposeFrame', () => {
  it('returns the middle View-B/Compose-B pair after the Kotlin five-read stable protocol', async () => {
    const calls: string[] = [];
    const result = await captureStableComposeFrame(scriptedClient(
      [{ tag: 'stable' }, { tag: 'stable' }, { tag: 'stable' }],
      [{ roots: [10], observations: 1 }, { roots: [10], observations: 99 }],
      calls,
    ));

    expect(calls).toEqual(['view:stable', 'compose:0:10', 'view:stable', 'compose:1:10', 'view:stable']);
    expect(result.views.snapshot.capturedAtEpochMillis).toBe(2);
    expect(result.compose.generation).toBe(1);
  });

  it('retries an unstable frame and propagates each View root list to its matching Compose read', async () => {
    const calls: string[] = [];
    const result = await captureStableComposeFrame(scriptedClient(
      [{ tag: 'a', roots: [10] }, { tag: 'b', roots: [11] }, { tag: 'b', roots: [11] }, { tag: 'c', roots: [12] }, { tag: 'c', roots: [12] }, { tag: 'c', roots: [12] }],
      [{ roots: [10] }, { roots: [11] }, { roots: [12] }, { roots: [12] }],
      calls,
    ));

    expect(calls).toEqual(['view:a', 'compose:0:10', 'view:b', 'compose:1:11', 'view:b', 'view:c', 'compose:2:12', 'view:c', 'compose:3:12', 'view:c']);
    expect(result.views.snapshot.root.id).toBe('view:c');
    expect(result.compose.generation).toBe(3);
  });

  it('fails after the bounded retry count when the target keeps changing', async () => {
    const calls: string[] = [];
    await expect(captureStableComposeFrame(scriptedClient(
      [{ tag: 'a' }, { tag: 'b' }, { tag: 'b' }],
      [{ roots: [10] }, { roots: [10] }],
      calls,
    ), 1)).rejects.toThrow('Target changed during Compose frame capture');
  });
});
