import { describe, expect, it } from 'vitest';
import type { LayoutSnapshot, UiNode } from '@aps/layout-inspector';
import type { LayoutCaptureDetail } from '../../../shared/ipc';
import {
  MAX_TIMELINE_FRAMES,
  appendTimelineFrame,
  diffSnapshots,
  removeTimelineFrame,
  type TimelineFrame,
} from './timeline';

const BOUNDS = { left: 0, top: 0, right: 100, bottom: 100 } as const;

function view(
  id: string,
  overrides: Partial<Extract<UiNode, { type: 'view' }>> = {},
): Extract<UiNode, { type: 'view' }> {
  return {
    type: 'view',
    id,
    className: 'android.view.View',
    bounds: BOUNDS,
    visible: true,
    alpha: 1,
    children: [],
    attributes: { rawProperties: {} },
    ...overrides,
  };
}

function compose(
  id: string,
  overrides: Partial<Extract<UiNode, { type: 'compose' }>> = {},
): Extract<UiNode, { type: 'compose' }> {
  return {
    type: 'compose',
    id,
    className: 'Button',
    bounds: BOUNDS,
    visible: true,
    alpha: 1,
    children: [],
    semanticProperties: {},
    ...overrides,
  };
}

function snapshot(capturedAtEpochMillis: number, children: readonly UiNode[]): LayoutSnapshot {
  const root = view('root', { className: 'Root', children });
  return {
    protocolVersion: { major: 1, minor: 1 },
    packageName: 'dev.example',
    capturedAtEpochMillis,
    display: { widthPx: 100, heightPx: 100, density: 1 },
    capabilities: { viewHierarchy: true, composeSemantics: true, screenshots: true, timeline: true },
    root,
    windows: [
      {
        id: 'main',
        title: 'Main',
        type: 'ACTIVITY',
        bounds: BOUNDS,
        root,
      },
    ],
    defaultWindowId: 'main',
  };
}

function detail(capturedAtEpochMillis: number): LayoutCaptureDetail {
  return { snapshot: snapshot(capturedAtEpochMillis, []) };
}

function frame(index: number): TimelineFrame {
  return {
    index,
    detail: detail(index),
    diffFromPrevious: null,
  };
}

describe('diffSnapshots', () => {
  it('reports added, removed, bounds-changed, and Kotlin-equivalent property changes', () => {
    const previous = snapshot(100, [
      view('stable-view', {
        className: 'BeforeClass',
        resourceName: 'before',
        text: 'before',
        attributes: { contentDescription: 'before', rawProperties: {} },
      }),
      compose('compose/42', { semanticsRole: 'Button', semanticProperties: { TestTag: 'action', State: 'off' } }),
      view('removed'),
    ]);
    const current = snapshot(200, [
      view('stable-view', {
        className: 'AfterClass',
        bounds: { left: 10, top: 0, right: 110, bottom: 100 },
        visible: false,
        alpha: 0.5,
        resourceName: 'after',
        text: 'after',
        attributes: { contentDescription: 'after', rawProperties: {} },
      }),
      compose('compose/42', { semanticsRole: 'Checkbox', semanticProperties: { TestTag: 'action', State: 'on' } }),
      view('added'),
    ]);

    const diff = diffSnapshots(previous, current);

    expect(diff).toMatchObject({
      previousCapturedAtEpochMillis: 100,
      currentCapturedAtEpochMillis: 200,
      addedNodes: 1,
      removedNodes: 1,
      boundsChangedNodes: 1,
    });
    expect(diff.changes.find((change) => change.nodeId === 'stable-view')).toEqual({
      type: 'changed',
      windowId: 'main',
      nodeId: 'stable-view',
      nodeKey: 'main:stable-view',
      className: 'AfterClass',
      changedProperties: [
        'alpha',
        'bounds',
        'className',
        'contentDescription',
        'resourceName',
        'text',
        'visible',
      ],
    });
    expect(diff.changes.find((change) => change.nodeId === 'compose/42')?.changedProperties).toEqual([
      'semanticProperties',
      'semanticsRole',
    ]);
  });

  it('matches reordered siblings by stable identity instead of reporting false additions', () => {
    const previous = snapshot(100, [view('node/1', { resourceName: 'title' }), view('node/2', { resourceName: 'body' })]);
    const current = snapshot(200, [view('node/2', { resourceName: 'body' }), view('node/1', { resourceName: 'title' })]);

    expect(diffSnapshots(previous, current)).toMatchObject({
      addedNodes: 0,
      removedNodes: 0,
      boundsChangedNodes: 0,
      changes: [],
    });
  });
});

describe('timeline frames', () => {
  it('keeps the newest 50 frames and diffs each capture against the latest frame', () => {
    let frames: readonly TimelineFrame[] = [];
    let selectedIndex = -1;
    for (let index = 0; index <= MAX_TIMELINE_FRAMES; index += 1) {
      ({ frames, selectedIndex } = appendTimelineFrame(frames, detail(index), `capture-${index}`));
    }

    expect(frames).toHaveLength(MAX_TIMELINE_FRAMES);
    expect(frames[0]?.index).toBe(1);
    expect(frames.at(-1)?.index).toBe(MAX_TIMELINE_FRAMES);
    expect(frames.at(-1)?.captureId).toBe(`capture-${MAX_TIMELINE_FRAMES}`);
    expect(frames.at(-1)?.diffFromPrevious).toMatchObject({
      previousCapturedAtEpochMillis: MAX_TIMELINE_FRAMES - 1,
      currentCapturedAtEpochMillis: MAX_TIMELINE_FRAMES,
    });
    expect(selectedIndex).toBe(MAX_TIMELINE_FRAMES);
  });

  it('selects the next frame when the selected frame closes, then falls back to the latest frame', () => {
    const frames = [frame(0), frame(1), frame(2)];

    const middleRemoved = removeTimelineFrame(frames, 1, 1);
    expect(middleRemoved.frames.map(({ index }) => index)).toEqual([0, 2]);
    expect(middleRemoved.selectedIndex).toBe(2);
    expect(middleRemoved.selectedFrame?.index).toBe(2);

    const lastRemoved = removeTimelineFrame(frames, 2, 2);
    expect(lastRemoved.selectedIndex).toBe(1);
    expect(lastRemoved.selectedFrame?.index).toBe(1);
  });

  it('does not move selection when an unselected frame closes and clears it after the last selected frame', () => {
    const unselectedRemoved = removeTimelineFrame([frame(0), frame(1)], 1, 0);
    expect(unselectedRemoved.selectedIndex).toBe(1);
    expect(unselectedRemoved.selectedFrame?.index).toBe(1);

    const lastRemoved = removeTimelineFrame([frame(1)], 1, 1);
    expect(lastRemoved.frames).toEqual([]);
    expect(lastRemoved.selectedIndex).toBeNull();
    expect(lastRemoved.selectedFrame).toBeNull();
  });
});
