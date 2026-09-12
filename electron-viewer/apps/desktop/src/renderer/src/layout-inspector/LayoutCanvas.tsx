import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type JSX } from 'react';
import { hitTestCandidates, type HitTestOrder, type UiNode } from '@aps/layout-inspector';
import type { UiLanguage } from '../../../shared/i18n';
import {
  CANVAS_BORDER_COLORS,
  PREVIEW_ZOOM,
  clampPan,
  destinationRect,
  mapBounds,
  previewSize,
  scrollPan,
  sourceRect,
  unmapPoint,
  visibleBoundsRects,
  zoomIn,
  zoomLabel,
  zoomOut,
  zoomPanAtPointer,
  type FloatSize,
  type Offset,
} from './canvas';
import { layoutText } from './labels';

export interface LayoutCanvasProps {
  readonly root: UiNode;
  readonly display: { readonly widthPx: number; readonly heightPx: number };
  readonly screenshotBase64?: string;
  readonly selectedNodeId: string;
  readonly hiddenSubtree: ReadonlySet<string>;
  readonly hitOrder: HitTestOrder;
  readonly language: UiLanguage;
  readonly onSelect: (nodeId: string) => void;
  readonly onHover: (nodeId: string | undefined) => void;
  readonly onToggleHitOrder: () => void;
  readonly hiddenCount: number;
  readonly onClearHidden: () => void;
}

/** The reference caps a portrait preview so it never fills a wide pane. */
const PORTRAIT_MAX_WIDTH = 390;
/** Two clicks within this many pixels count as the same point, so they cycle. */
const CLICK_TOLERANCE_PX = 4;
const WHEEL_SCROLL_PIXELS = 1;

interface ClickCycle {
  readonly x: number;
  readonly y: number;
  readonly path: string;
  readonly index: number;
}

/**
 * CANVAS pane: the screenshot at a zoom/pan of its own, every visible view's
 * bounds drawn over it, the hovered node in amber and the selection in red —
 * the same three colours and the same hit-test cycling as the reference.
 *
 * The overlay is one canvas element rather than thousands of DOM rects: a
 * 10,000-node hierarchy still paints in a single pass.
 */
export function LayoutCanvas(props: LayoutCanvasProps): JSX.Element {
  const {
    display,
    hiddenCount,
    hiddenSubtree,
    hitOrder,
    onClearHidden,
    language,
    onHover,
    onSelect,
    onToggleHitOrder,
    root,
    screenshotBase64,
    selectedNodeId,
  } = props;
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const overlayRef = useRef<HTMLCanvasElement | null>(null);
  const highlightRef = useRef<HTMLCanvasElement | null>(null);
  const cycle = useRef<ClickCycle | undefined>(undefined);
  const [viewport, setViewport] = useState<FloatSize>({ width: 0, height: 0 });
  const [zoom, setZoom] = useState<number>(PREVIEW_ZOOM.defaultScale);
  const [pan, setPan] = useState<Offset>({ x: 0, y: 0 });
  const [appOnly, setAppOnly] = useState(true);
  const [hoveredNodeId, setHoveredNodeId] = useState<string | undefined>(undefined);

  const source = useMemo(
    () => sourceRect(root.bounds, display.widthPx, display.heightPx, appOnly),
    [appOnly, display.heightPx, display.widthPx, root.bounds],
  );
  const baseSize = useMemo(
    () => previewSize(source, Math.max(viewport.width, 1), Math.max(viewport.height, 1), PORTRAIT_MAX_WIDTH),
    [source, viewport.height, viewport.width],
  );
  const content: FloatSize = useMemo(
    () => ({ width: baseSize.width * zoom, height: baseSize.height * zoom }),
    [baseSize.height, baseSize.width, zoom],
  );
  const destination = useMemo(
    () => destinationRect(viewport, content, pan),
    [content, pan, viewport],
  );
  // One lookup table per snapshot: the hovered and selected rects are repainted
  // on every pointer move, and walking 10,000 nodes each time would show.
  const nodesById = useMemo(() => {
    const map = new Map<string, UiNode>();
    const visit = (node: UiNode): void => {
      map.set(node.id, node);
      for (const child of node.children) visit(child);
    };
    visit(root);
    return map;
  }, [root]);
  // Every visible view, selection excluded: the selection and the hover are drawn
  // on their own layer so a click repaints two rectangles, not ten thousand.
  const overlayRects = useMemo(
    () =>
      visibleBoundsRects(root, {
        selectedNodeId: undefined,
        source,
        destination,
        hiddenNodeIds: hiddenSubtree,
      }),
    [destination, hiddenSubtree, root, source],
  );

  useLayoutEffect(() => {
    const element = viewportRef.current;
    if (element === null) return;
    const update = (): void => {
      setViewport({ width: element.clientWidth, height: element.clientHeight });
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // A resize or a zoom keeps the preview inside the pane, as PreviewPanState does.
  useEffect(() => {
    setPan((current) => clampPan(current, content.width, content.height, viewport.width, viewport.height));
  }, [content.height, content.width, viewport.height, viewport.width]);

  useEffect(() => {
    cycle.current = undefined;
  }, [root, screenshotBase64]);

  const prepare = useCallback(
    (canvas: HTMLCanvasElement | null): CanvasRenderingContext2D | undefined => {
      if (canvas === null || destination.width <= 0 || content.width <= 0) return undefined;
      const ratio = window.devicePixelRatio > 0 ? window.devicePixelRatio : 1;
      const width = Math.max(1, Math.round(content.width * ratio));
      const height = Math.max(1, Math.round(content.height * ratio));
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      const context = canvas.getContext('2d');
      if (context === null) return undefined;
      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      context.clearRect(0, 0, content.width, content.height);
      context.lineWidth = 1;
      return context;
    },
    [content.height, content.width, destination.width],
  );

  const stroke = useCallback(
    (
      context: CanvasRenderingContext2D,
      rect: { left: number; top: number; width: number; height: number },
      color: string,
    ): void => {
      context.strokeStyle = color;
      context.strokeRect(
        Math.round(rect.left - destination.left) + 0.5,
        Math.round(rect.top - destination.top) + 0.5,
        Math.max(1, Math.round(rect.width) - 1),
        Math.max(1, Math.round(rect.height) - 1),
      );
    },
    [destination.left, destination.top],
  );

  // Layer one: every visible view's bounds, repainted when the view or crop moves.
  useEffect(() => {
    const context = prepare(overlayRef.current);
    if (context === undefined) return;
    for (const rect of overlayRects) stroke(context, rect, CANVAS_BORDER_COLORS.normal);
  }, [overlayRects, prepare, stroke]);

  // Layer two: the hover in amber and the selection in red.
  useEffect(() => {
    const context = prepare(highlightRef.current);
    if (context === undefined) return;
    if (hoveredNodeId !== undefined && hoveredNodeId !== selectedNodeId) {
      const hovered = nodesById.get(hoveredNodeId);
      const rect = hovered === undefined ? undefined : mapBounds(hovered.bounds, source, destination);
      if (rect !== undefined) stroke(context, rect, CANVAS_BORDER_COLORS.hovered);
    }
    const selected = nodesById.get(selectedNodeId);
    const rect = selected === undefined ? undefined : mapBounds(selected.bounds, source, destination);
    if (rect !== undefined) stroke(context, rect, CANVAS_BORDER_COLORS.selected);
  }, [hoveredNodeId, nodesById, prepare, selectedNodeId, source, stroke, destination]);

  const sourcePoint = useCallback(
    (event: { clientX: number; clientY: number }): { point: Offset; candidates: UiNode[] } | undefined => {
      const element = viewportRef.current;
      if (element === null || destination.width <= 0 || destination.height <= 0) return undefined;
      const rect = element.getBoundingClientRect();
      const point = unmapPoint(
        { x: event.clientX - rect.left, y: event.clientY - rect.top },
        source,
        destination,
      );
      if (point === undefined) return undefined;
      return {
        point,
        candidates: hitTestCandidates(root, { x: point.x, y: point.y, hiddenSubtree, order: hitOrder }),
      };
    },
    [destination, hiddenSubtree, hitOrder, root, source],
  );

  const onPointerMove = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      const hit = sourcePoint(event);
      const next = hit?.candidates[0]?.id;
      if (next !== hoveredNodeId) {
        setHoveredNodeId(next);
        onHover(next);
      }
    },
    [hoveredNodeId, onHover, sourcePoint],
  );

  const onPointerLeave = useCallback(() => {
    setHoveredNodeId(undefined);
    onHover(undefined);
  }, [onHover]);

  const onClick = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      const hit = sourcePoint(event);
      if (hit === undefined || hit.candidates.length === 0) return;
      const path = hit.candidates.map((node) => node.id);
      const previous = cycle.current;
      const samePoint =
        previous !== undefined &&
        Math.hypot(hit.point.x - previous.x, hit.point.y - previous.y) <= CLICK_TOLERANCE_PX;
      const samePath = previous !== undefined && previous.path === path.join('|');
      const index = samePoint && samePath ? (previous as ClickCycle).index : 0;
      const selected = path[index % path.length];
      cycle.current = { x: hit.point.x, y: hit.point.y, path: path.join('|'), index: (index + 1) % path.length };
      if (selected !== undefined) onSelect(selected);
    },
    [onSelect, sourcePoint],
  );

  const onWheel = useCallback(
    (event: React.WheelEvent<HTMLDivElement>) => {
      if (content.width <= 0) return;
      const element = viewportRef.current;
      if (element === null) return;
      if (event.metaKey || event.ctrlKey) {
        const rect = element.getBoundingClientRect();
        const pointer = { x: event.clientX - rect.left, y: event.clientY - rect.top };
        const delta = Math.round(-event.deltaY) * 0.001;
        const next = Math.min(PREVIEW_ZOOM.maxScale, Math.max(PREVIEW_ZOOM.minScale, zoom + delta));
        if (next === zoom) return;
        const ratio = next / zoom;
        setPan(
          zoomPanAtPointer(pointer, ratio, content, { width: content.width * ratio, height: content.height * ratio }, viewport, pan),
        );
        setZoom(next);
        return;
      }
      setPan((current) =>
        scrollPan(
          current,
          { x: event.deltaX * WHEEL_SCROLL_PIXELS, y: event.deltaY * WHEEL_SCROLL_PIXELS },
          content.width,
          content.height,
          viewport.width,
          viewport.height,
        ),
      );
    },
    [content, pan, viewport, zoom],
  );

  return (
    <div className="canvas">
      <div className="pane-header">
        <h3 className="pane-header__title">{layoutText('pane.canvas', language)}</h3>
        <div className="pane-header__options">
          <span className="pane-header__note">
            {source.width} × {source.height}
          </span>
          <button
            type="button"
            className={appOnly ? 'toggle toggle--on' : 'toggle'}
            aria-pressed={appOnly}
            onClick={() => setAppOnly((current) => !current)}
          >
            {layoutText(appOnly ? 'canvas.appOnlyOn' : 'canvas.appOnlyOff', language)}
          </button>
          <button type="button" className="toggle toggle--on" onClick={onToggleHitOrder}>
            {layoutText(hitOrder === 'smallest-area' ? 'canvas.smallHits' : 'canvas.zOrderHits', language)}
          </button>
          {hiddenCount > 0 ? (
            <button type="button" className="toggle toggle--on" onClick={onClearHidden}>
              {layoutText('hidden.summary', language, hiddenCount)}
            </button>
          ) : null}
        </div>
      </div>
      <div
        className="preview canvas__viewport"
        ref={viewportRef}
        onMouseMove={onPointerMove}
        onMouseLeave={onPointerLeave}
        onClick={onClick}
        onWheel={onWheel}
      >
        {screenshotBase64 === undefined ? (
          <p className="card__muted">{layoutText('canvas.noLiveFrame', language)}</p>
        ) : (
          <div
            className={appOnly ? 'canvas__stage canvas__stage--app' : 'canvas__stage'}
            style={{
              left: destination.left,
              top: destination.top,
              width: content.width,
              height: content.height,
            }}
          >
            <img
              className="preview__image"
              alt="device screenshot"
              src={'data:image/png;base64,' + screenshotBase64}
              draggable={false}
            />
            <canvas className="canvas__overlay" ref={overlayRef} />
            <canvas className="canvas__overlay canvas__overlay--highlight" ref={highlightRef} />
          </div>
        )}
        <div className="canvas__zoom">
          <button
            type="button"
            className="button button--inline"
            aria-label={layoutText('canvas.zoomOut', language)}
            disabled={zoom <= PREVIEW_ZOOM.minScale}
            onClick={() => setZoom((current) => zoomOut(current))}
          >
            −
          </button>
          <span className="canvas__zoom-label">{zoomLabel(zoom)}</span>
          <button
            type="button"
            className="button button--inline"
            aria-label={layoutText('canvas.zoomIn', language)}
            disabled={zoom >= PREVIEW_ZOOM.maxScale}
            onClick={() => setZoom((current) => zoomIn(current))}
          >
            +
          </button>
        </div>
      </div>
    </div>
  );
}
