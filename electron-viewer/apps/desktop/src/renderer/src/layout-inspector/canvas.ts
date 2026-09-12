import type { Bounds, UiNode } from '@aps/layout-inspector';

/**
 * Port of CanvasGeometry, PreviewZoomState, PreviewPanState and
 * ViewBoundsOverlay: the CANVAS pane's arithmetic, kept free of the DOM so the
 * reference's own test expectations can check it.
 */

export interface CropRect {
  readonly left: number;
  readonly top: number;
  readonly width: number;
  readonly height: number;
  readonly right: number;
  readonly bottom: number;
}

export interface FloatRect {
  readonly left: number;
  readonly top: number;
  readonly width: number;
  readonly height: number;
}

export interface FloatSize {
  readonly width: number;
  readonly height: number;
}

export interface Offset {
  readonly x: number;
  readonly y: number;
}

export const PREVIEW_ZOOM = { minScale: 0.5, maxScale: 2.5, defaultScale: 1, step: 0.25 } as const;

/** The reference's three border colours, from CanvasBorderColors. */
export const CANVAS_BORDER_COLORS = {
  normal: '#7dd3fc',
  hovered: '#f59e0b',
  selected: '#ef4444',
} as const;

export function zoomIn(scale: number): number {
  return Math.min(PREVIEW_ZOOM.maxScale, scale + PREVIEW_ZOOM.step);
}

export function zoomOut(scale: number): number {
  return Math.max(PREVIEW_ZOOM.minScale, scale - PREVIEW_ZOOM.step);
}

export function zoomLabel(scale: number): string {
  return Math.round(scale * 100) + '%';
}

/** The whole display, or the active window's bounds clamped into it. */
export function sourceRect(
  appBounds: Bounds | undefined,
  displayWidth: number,
  displayHeight: number,
  appOnly: boolean,
): CropRect {
  const full = cropRect(0, 0, displayWidth, displayHeight);
  if (!appOnly || appBounds === undefined) return full;
  const left = clamp(appBounds.left, 0, displayWidth);
  const top = clamp(appBounds.top, 0, displayHeight);
  const right = clamp(appBounds.right, 0, displayWidth);
  const bottom = clamp(appBounds.bottom, 0, displayHeight);
  if (right <= left || bottom <= top) return full;
  return cropRect(left, top, right - left, bottom - top);
}

/** Contain-fit, with the reference's narrower cap for a portrait screen. */
export function previewSize(
  source: CropRect,
  maxWidth: number,
  maxHeight: number,
  portraitMaxWidth: number,
): FloatSize {
  const availableWidth = source.width <= source.height ? Math.min(maxWidth, portraitMaxWidth) : maxWidth;
  const scale = Math.min(availableWidth / source.width, maxHeight / source.height);
  return { width: source.width * scale, height: source.height * scale };
}

export function destinationRect(viewport: FloatSize, content: FloatSize, pan: Offset): FloatRect {
  return {
    left: (viewport.width - content.width) / 2 + pan.x,
    top: (viewport.height - content.height) / 2 + pan.y,
    width: content.width,
    height: content.height,
  };
}

export function mapBounds(bounds: Bounds, source: CropRect, destination: FloatRect): FloatRect | undefined {
  const left = Math.max(bounds.left, source.left);
  const top = Math.max(bounds.top, source.top);
  const right = Math.min(bounds.right, source.right);
  const bottom = Math.min(bounds.bottom, source.bottom);
  if (right <= left || bottom <= top) return undefined;
  const scaleX = destination.width / source.width;
  const scaleY = destination.height / source.height;
  return {
    left: destination.left + (left - source.left) * scaleX,
    top: destination.top + (top - source.top) * scaleY,
    width: (right - left) * scaleX,
    height: (bottom - top) * scaleY,
  };
}

/** Screen point inside the preview back to device coordinates. */
export function unmapPoint(point: Offset, source: CropRect, destination: FloatRect): Offset | undefined {
  if (
    point.x < destination.left ||
    point.x > destination.left + destination.width ||
    point.y < destination.top ||
    point.y > destination.top + destination.height
  ) {
    return undefined;
  }
  return {
    x: source.left + ((point.x - destination.left) * source.width) / destination.width,
    y: source.top + ((point.y - destination.top) * source.height) / destination.height,
  };
}

export function clampPan(
  pan: Offset,
  contentWidthPx: number,
  contentHeightPx: number,
  viewportWidthPx: number,
  viewportHeightPx: number,
): Offset {
  const horizontalLimit = Math.max(0, (contentWidthPx - viewportWidthPx) / 2);
  const verticalLimit = Math.max(0, (contentHeightPx - viewportHeightPx) / 2);
  return {
    x: horizontalLimit === 0 ? 0 : clamp(pan.x, -horizontalLimit, horizontalLimit),
    y: verticalLimit === 0 ? 0 : clamp(pan.y, -verticalLimit, verticalLimit),
  };
}

export function scrollPan(
  pan: Offset,
  scrollDelta: Offset,
  contentWidthPx: number,
  contentHeightPx: number,
  viewportWidthPx: number,
  viewportHeightPx: number,
): Offset {
  return clampPan(
    { x: pan.x - scrollDelta.x, y: pan.y - scrollDelta.y },
    contentWidthPx,
    contentHeightPx,
    viewportWidthPx,
    viewportHeightPx,
  );
}

/** Keeps the device pixel under the pointer fixed while the scale changes. */
export function zoomPanAtPointer(
  pointer: Offset,
  ratio: number,
  oldContent: FloatSize,
  newContent: FloatSize,
  viewport: FloatSize,
  pan: Offset,
): Offset {
  const oldCenterX = (viewport.width - oldContent.width) / 2;
  const oldCenterY = (viewport.height - oldContent.height) / 2;
  const newCenterX = (viewport.width - newContent.width) / 2;
  const newCenterY = (viewport.height - newContent.height) / 2;
  return clampPan(
    {
      x: pointer.x * (1 - ratio) + (oldCenterX + pan.x) * ratio - newCenterX,
      y: pointer.y * (1 - ratio) + (oldCenterY + pan.y) * ratio - newCenterY,
    },
    newContent.width,
    newContent.height,
    viewport.width,
    viewport.height,
  );
}

export interface OverlayOptions {
  readonly selectedNodeId: string | undefined;
  readonly source: CropRect;
  readonly destination: FloatRect;
  readonly hiddenNodeIds?: ReadonlySet<string>;
}

/**
 * Port of ViewBoundsOverlay.mappedVisibleBounds: preorder, invisible or fully
 * transparent subtrees pruned, hidden layers skipped, the selection excluded
 * because it is drawn in its own colour, and anything outside the crop dropped.
 */
export function visibleBoundsRects(root: UiNode, options: OverlayOptions): FloatRect[] {
  const hidden = options.hiddenNodeIds ?? new Set<string>();
  const rects: FloatRect[] = [];
  const visit = (node: UiNode, ancestorsVisible: boolean, ancestorAlpha: number): void => {
    if (hidden.has(node.id)) return;
    const effectivelyVisible = ancestorsVisible && node.visible;
    const effectiveAlpha = ancestorAlpha * node.alpha;
    if (!effectivelyVisible || !(effectiveAlpha > 0)) return;
    const width = node.bounds.right - node.bounds.left;
    const height = node.bounds.bottom - node.bounds.top;
    if (node.id !== options.selectedNodeId && width > 0 && height > 0) {
      const mapped = mapBounds(node.bounds, options.source, options.destination);
      if (mapped !== undefined) rects.push(mapped);
    }
    for (const child of node.children) visit(child, effectivelyVisible, effectiveAlpha);
  };
  visit(root, true, 1);
  return rects;
}

/** Parses #rgb / #rrggbb / #aarrggbb the way CanvasArgb.parse does. */
export function parseCanvasArgb(value: string | undefined): string | undefined {
  const digits = value?.trim().replace(/^#/, '');
  if (digits === undefined) return undefined;
  if (digits.length === 6) return /^[0-9a-fA-F]{6}$/.test(digits) ? '#' + digits.toLowerCase() : undefined;
  if (digits.length === 8) return /^[0-9a-fA-F]{8}$/.test(digits) ? '#' + digits.slice(2).toLowerCase() : undefined;
  return undefined;
}

function cropRect(left: number, top: number, width: number, height: number): CropRect {
  return { left, top, width, height, right: left + width, bottom: top + height };
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(Math.max(value, low), high);
}
