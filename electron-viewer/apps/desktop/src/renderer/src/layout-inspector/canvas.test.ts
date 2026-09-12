import { describe, expect, it } from 'vitest';
import type { UiNode } from '@aps/layout-inspector';
import {
  CANVAS_BORDER_COLORS,
  clampPan,
  mapBounds,
  parseCanvasArgb,
  previewSize,
  scrollPan,
  sourceRect,
  unmapPoint,
  visibleBoundsRects,
  zoomIn,
  zoomLabel,
  zoomOut,
} from './canvas';

/**
 * Expectations ported one by one from the reference's CanvasGeometryTest,
 * PreviewPanStateTest, PreviewZoomStateTest, ViewBoundsOverlayTest and
 * CanvasBorderColorsTest.
 */

function viewNode(
  id: string,
  bounds: [number, number, number, number],
  children: UiNode[] = [],
  extra: { visible?: boolean; alpha?: number } = {},
): UiNode {
  return {
    type: 'view',
    id,
    className: 'android.view.View',
    bounds: { left: bounds[0], top: bounds[1], right: bounds[2], bottom: bounds[3] },
    visible: extra.visible ?? true,
    alpha: extra.alpha ?? 1,
    children,
    attributes: { rawProperties: {} },
  };
}

describe('canvas geometry', () => {
  it('centres a contained portrait preview in a square viewport', () => {
    const size = previewSize({ left: 0, top: 0, width: 100, height: 200, right: 100, bottom: 200 }, 300, 300, 390);
    expect(size).toEqual({ width: 150, height: 300 });
  });

  it('scales bounds by the destination rect', () => {
    const destination = { left: 75, top: 0, width: 150, height: 300 };
    const source = { left: 0, top: 0, width: 100, height: 200, right: 100, bottom: 200 };
    expect(mapBounds({ left: 10, top: 20, right: 50, bottom: 100 }, source, destination)).toEqual({
      left: 90,
      top: 30,
      width: 60,
      height: 120,
    });
  });

  it('clamps app-only bounds into the display', () => {
    expect(sourceRect({ left: 1508, top: 300, right: 2332, bottom: 1764 }, 3840, 2160, true)).toEqual({
      left: 1508,
      top: 300,
      width: 824,
      height: 1464,
      right: 2332,
      bottom: 1764,
    });
    expect(sourceRect({ left: -50, top: 100, right: 4000, bottom: 2300 }, 3840, 2160, true)).toEqual({
      left: 0,
      top: 100,
      width: 3840,
      height: 2060,
      right: 3840,
      bottom: 2160,
    });
  });

  it('falls back to the whole display for invalid bounds or full-device mode', () => {
    const full = { left: 0, top: 0, width: 3840, height: 2160, right: 3840, bottom: 2160 };
    expect(sourceRect({ left: 4000, top: 100, right: 4200, bottom: 500 }, 3840, 2160, true)).toEqual(full);
    expect(sourceRect({ left: 1508, top: 300, right: 2332, bottom: 1764 }, 3840, 2160, false)).toEqual(full);
  });

  it('subtracts the crop origin before scaling', () => {
    expect(
      mapBounds(
        { left: 1600, top: 400, right: 1800, bottom: 500 },
        { left: 1500, top: 300, width: 800, height: 1600, right: 2300, bottom: 1900 },
        { left: 0, top: 0, width: 400, height: 800 },
      ),
    ).toEqual({ left: 50, top: 50, width: 100, height: 50 });
  });

  it('reads a pointer back into device coordinates, and rejects points outside', () => {
    const source = { left: 1500, top: 300, width: 800, height: 1600, right: 2300, bottom: 1900 };
    const destination = { left: 0, top: 0, width: 400, height: 800 };
    expect(unmapPoint({ x: 50, y: 50 }, source, destination)).toEqual({ x: 1600, y: 400 });
    expect(unmapPoint({ x: -1, y: 50 }, source, destination)).toBeUndefined();
    expect(unmapPoint({ x: 401, y: 50 }, source, destination)).toBeUndefined();
  });
});

describe('preview zoom and pan', () => {
  it('steps between bounded scales and labels them as percentages', () => {
    expect(zoomIn(1)).toBe(1.25);
    expect(zoomOut(1)).toBe(0.75);
    expect(zoomOut(0.5)).toBe(0.5);
    expect(zoomIn(2.5)).toBe(2.5);
    expect(zoomLabel(1)).toBe('100%');
    expect(zoomLabel(1.25)).toBe('125%');
    expect(zoomLabel(0.75)).toBe('75%');
  });

  it('stays centred while the scaled preview fits the viewport', () => {
    expect(clampPan({ x: 40, y: -40 }, 300, 500, 400, 600)).toEqual({ x: 0, y: 0 });
  });

  it('clamps overflow independently per axis', () => {
    expect(clampPan({ x: 250, y: -500 }, 600, 1000, 400, 600)).toEqual({ x: 100, y: -200 });
    expect(clampPan({ x: 80, y: -150 }, 300, 900, 400, 600)).toEqual({ x: 0, y: -150 });
  });

  it('pans on both axes while scrolling a zoomed preview, and clamps at the edges', () => {
    expect(scrollPan({ x: 0, y: 0 }, { x: 80, y: 120 }, 800, 900, 400, 500)).toEqual({ x: -80, y: -120 });
    expect(scrollPan({ x: -190, y: 60 }, { x: 80, y: -120 }, 800, 300, 400, 500)).toEqual({ x: -200, y: 0 });
  });
});

describe('visible bounds overlay', () => {
  it('maps visible bounds in preorder while excluding the selection and off-source nodes', () => {
    const root = viewNode('root', [0, 0, 100, 100], [
      viewNode('selected', [10, 10, 30, 30]),
      viewNode('partial', [80, 20, 120, 40]),
      viewNode('outside', [120, 20, 140, 40]),
    ]);
    const rects = visibleBoundsRects(root, {
      selectedNodeId: 'selected',
      source: { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 },
      destination: { left: 0, top: 0, width: 200, height: 200 },
    });
    expect(rects).toEqual([
      { left: 0, top: 0, width: 200, height: 200 },
      { left: 160, top: 40, width: 40, height: 40 },
    ]);
  });

  it('prunes invisible and transparent subtrees, and hidden layers', () => {
    const root = viewNode('root', [0, 0, 100, 100], [
      viewNode('invisible-parent', [0, 0, 20, 20], [viewNode('invisible-child', [10, 10, 30, 30])], {
        visible: false,
      }),
      viewNode('transparent-parent', [0, 0, 20, 20], [viewNode('transparent-child', [10, 10, 30, 30])], {
        alpha: 0,
      }),
      viewNode('hidden', [20, 20, 40, 40]),
      viewNode('kept', [40, 40, 60, 60]),
    ]);
    const rects = visibleBoundsRects(root, {
      selectedNodeId: undefined,
      source: { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 },
      destination: { left: 0, top: 0, width: 100, height: 100 },
      hiddenNodeIds: new Set(['hidden']),
    });
    expect(rects).toHaveLength(2);
    expect(rects[1]).toEqual({ left: 40, top: 40, width: 20, height: 20 });
  });
});

describe('canvas border colours', () => {
  it('parses rgb and argb hex values and rejects anything else', () => {
    expect(parseCanvasArgb('#7DD3FC')).toBe('#7dd3fc');
    expect(parseCanvasArgb('#807DD3FC')).toBe('#7dd3fc');
    expect(parseCanvasArgb('#GG0000')).toBeUndefined();
  });

  it('keeps the reference defaults', () => {
    expect(CANVAS_BORDER_COLORS).toEqual({ normal: '#7dd3fc', hovered: '#f59e0b', selected: '#ef4444' });
  });
});
