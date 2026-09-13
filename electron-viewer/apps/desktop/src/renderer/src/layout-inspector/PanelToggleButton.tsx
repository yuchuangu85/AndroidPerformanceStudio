import type { JSX } from 'react';

/** Which pane the button stands for, which is also the edge it fills in. */
export type PanelSide = 'left' | 'bottom' | 'right';

export interface PanelToggleButtonProps {
  readonly side: PanelSide;
  readonly visible: boolean;
  readonly label: string;
  readonly onClick: () => void;
}

/** The 15dp icon's filled edge, measured the way the reference draws it. */
const FILLED_EDGE: Record<PanelSide, { readonly x: number; readonly y: number }> = {
  left: { x: 2, y: 2 },
  bottom: { x: 2, y: 9 },
  right: { x: 9, y: 2 },
};

/**
 * The reference's PanelToggleButton: a 26x21 well holding a stroked rectangle
 * whose matching edge is filled, tinted with the accent while the pane is up.
 */
export function PanelToggleButton({ side, visible, label, onClick }: PanelToggleButtonProps): JSX.Element {
  const edge = FILLED_EDGE[side];
  const width = side === 'bottom' ? 11 : 4;
  const height = side === 'bottom' ? 4 : 11;
  return (
    <button
      type="button"
      className={visible ? 'panel-toggle panel-toggle--on' : 'panel-toggle'}
      aria-pressed={visible}
      aria-label={label}
      title={label}
      onClick={onClick}
    >
      <svg viewBox="0 0 15 15" width="15" height="15" aria-hidden="true">
        <rect x="1.5" y="1.5" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="1" />
        <rect x={edge.x} y={edge.y} width={width} height={height} fill="currentColor" opacity="0.8" />
      </svg>
    </button>
  );
}
