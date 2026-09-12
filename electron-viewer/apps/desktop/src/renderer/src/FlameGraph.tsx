import { useCallback, useMemo, useRef, useState, type JSX } from 'react';
import type { FlameGraphPayload, FlameGraphPayloadNode, FrameImplementation } from '@aps/profile-analysis';
import { translate, type UiLanguage } from '../../shared/i18n';

/** FlameTooltipMode: where the frame facts appear. */
export type FlameTooltipMode = 'follow-mouse' | 'fixed';

export interface FlameGraphProps {
  readonly graph: FlameGraphPayload;
  readonly language: UiLanguage;
  /**
   * Undefined keeps the browser's own title tooltip, which is what the method
   * recording panel uses; the flame graph setting picks one of the two drawn
   * boxes instead.
   */
  readonly tooltipMode?: FlameTooltipMode;
  /** Called with the clicked frame; the parent decides what focusing means. */
  readonly onFocus?: (node: FlameGraphPayloadNode) => void;
  /** Formats a weight for display; defaults to a plain localized integer. */
  readonly formatWeight?: (value: string) => string;
}

const GRAPH_WIDTH = 1000;
const ROW_HEIGHT = 18;
const MIN_LABEL_WIDTH = 26;
const DEFAULT_LABEL_CHAR_WIDTH = 6;
/** Keeps the follow-mouse box inside the pane instead of under the pointer. */
const TOOLTIP_WIDTH = 260;
const TOOLTIP_MARGIN = 8;
const TOOLTIP_OFFSET = 14;

export const IMPLEMENTATION_FILL: Record<FrameImplementation, string> = {
  NATIVE: '#4a7fb5',
  MANAGED: '#4f9d69',
  KERNEL: '#b5793a',
  UNKNOWN: '#6b6f76',
};

export function defaultFormatWeight(value: string): string {
  try {
    return BigInt(value).toLocaleString('en-US');
  } catch {
    return value;
  }
}

/** Root-to-node function ids, used to build a focus transform path. */
export function flamePathTo(
  node: FlameGraphPayloadNode,
  byIndex: ReadonlyMap<number, FlameGraphPayloadNode>,
): string[] {
  const path: string[] = [];
  let current: FlameGraphPayloadNode | undefined = node;
  while (current !== undefined) {
    path.unshift(current.functionId);
    current = current.parent >= 0 ? byIndex.get(current.parent) : undefined;
  }
  return path;
}

export function nodesByIndex(graph: FlameGraphPayload): Map<number, FlameGraphPayloadNode> {
  const map = new Map<number, FlameGraphPayloadNode>();
  graph.nodes.forEach((node) => map.set(node.index, node));
  return map;
}

/**
 * Draws a projected flame graph as SVG. Rows come from the payload already
 * normalized to 0..1, so the only geometry here is scaling and label fitting.
 */
export function FlameGraph({ graph, language, tooltipMode, onFocus, formatWeight }: FlameGraphProps): JSX.Element {
  const format = formatWeight ?? defaultFormatWeight;
  const byIndex = useMemo(() => nodesByIndex(graph), [graph]);
  const rowsTopDown = useMemo(
    () => (graph.startsAtBottom ? [...graph.rows].reverse() : [...graph.rows]),
    [graph],
  );
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [hovered, setHovered] = useState<FlameGraphPayloadNode | null>(null);
  const [pointer, setPointer] = useState({ x: 0, y: 0 });

  const track = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const bounds = containerRef.current?.getBoundingClientRect();
    if (bounds === undefined) return;
    setPointer({ x: event.clientX - bounds.left, y: event.clientY - bounds.top });
  }, []);

  const tooltip = tooltipMode === undefined || hovered === null ? null : (
    <div
      className={
        tooltipMode === 'fixed' ? 'flame__tooltip flame__tooltip--fixed' : 'flame__tooltip'
      }
      style={
        tooltipMode === 'fixed'
          ? undefined
          : {
              left: Math.min(
                pointer.x + TOOLTIP_OFFSET,
                Math.max(
                  TOOLTIP_MARGIN,
                  (containerRef.current?.clientWidth ?? TOOLTIP_WIDTH) - TOOLTIP_WIDTH - TOOLTIP_MARGIN,
                ),
              ),
              top: pointer.y + TOOLTIP_OFFSET,
            }
      }
    >
      <p className="flame__tooltip-title">{hovered.symbolName}</p>
      <p className="flame__tooltip-note">{hovered.resource}</p>
      <dl className="flame__tooltip-rows">
        <div>
          <dt>{translate('cpu.inclusive', language)}</dt>
          <dd>{format(hovered.inclusiveWeight)}</dd>
        </div>
        <div>
          <dt>{translate('cpu.self', language)}</dt>
          <dd>{format(hovered.selfWeight)}</dd>
        </div>
      </dl>
    </div>
  );

  return (
    <div className="flame" ref={containerRef} onMouseMove={track} onMouseLeave={() => setHovered(null)}>
      <svg
        viewBox={'0 0 ' + String(GRAPH_WIDTH) + ' ' + String(Math.max(rowsTopDown.length * ROW_HEIGHT, ROW_HEIGHT))}
        width="100%"
        role="img"
        aria-label={translate('cpu.flameGraph', language)}
      >
        {rowsTopDown.map((row, rowPosition) =>
          row.map((nodeIndex) => {
            const node = byIndex.get(nodeIndex);
            if (node === undefined) return null;
            const x = node.start * GRAPH_WIDTH;
            const width = Math.max((node.end - node.start) * GRAPH_WIDTH, 0.5);
            const labelLimit = Math.floor(width / DEFAULT_LABEL_CHAR_WIDTH);
            return (
              <g key={String(nodeIndex)}>
                <rect
                  x={x}
                  y={rowPosition * ROW_HEIGHT}
                  width={width}
                  height={ROW_HEIGHT - 1}
                  fill={IMPLEMENTATION_FILL[node.implementation]}
                  opacity={node.selfWeight === '0' ? 0.75 : 1}
                  onMouseEnter={tooltipMode === undefined ? undefined : () => setHovered(node)}
                  onClick={onFocus === undefined ? undefined : () => onFocus(node)}
                >
                  {tooltipMode === undefined ? (
                    <title>
                      {node.symbolName +
                        '\n' +
                        node.resource +
                        '\n' +
                        translate('cpu.inclusive', language) +
                        ': ' +
                        format(node.inclusiveWeight) +
                        '\n' +
                        translate('cpu.self', language) +
                        ': ' +
                        format(node.selfWeight)}
                    </title>
                  ) : null}
                </rect>
                {width >= MIN_LABEL_WIDTH ? (
                  <text
                    x={x + 4}
                    y={rowPosition * ROW_HEIGHT + ROW_HEIGHT - 6}
                    fontSize={11}
                    fill="#ffffff"
                    pointerEvents="none"
                  >
                    {node.symbolName.length > labelLimit
                      ? node.symbolName.slice(0, Math.max(labelLimit - 1, 1)) + '…'
                      : node.symbolName}
                  </text>
                ) : null}
              </g>
            );
          }),
        )}
      </svg>
      {tooltip}
    </div>
  );
}
