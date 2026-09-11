import type { JSX } from 'react';
import type { AppDestination } from '../../shared/destinations';

/**
 * One line-art glyph per destination, drawn on a 16x16 grid with a 1.4px round
 * stroke so it matches the weight of AppKit's own sidebar symbols. They are
 * inline SVG rather than an icon font or image assets: the sidebar renders 14 of
 * them, they inherit the accent colour through currentColor, and the packaged
 * app gains no extra file to ship.
 */
const ICON_PATHS: Readonly<Record<AppDestination, readonly string[]>> = {
  HOME: ['M2.8 7.4 8 3.1l5.2 4.3V13H9.8V9.8H6.2V13H2.8z'],
  SOURCE_WORKSPACES: ['M2.2 4.6c0-.5.4-.9.9-.9h2.5l1.2 1.5h6.1c.5 0 .9.4.9.9v5.5c0 .5-.4.9-.9.9H3.1a.9.9 0 0 1-.9-.9z'],
  LAYOUT_INSPECTOR: ['M2.3 3.6h11.4v8.8H2.3z', 'M6.3 3.6v8.8', 'M2.3 6.4h11.4'],
  SIMPLEPERF: ['M3 11.6a5.4 5.4 0 1 1 10 0', 'M8 10.2 10.7 6.7', 'M8 10.2h.01'],
  PERFETTO: ['M1.9 10.4 4.4 6 6.2 10.6 8 4.1 9.8 11 12 7.3 14.1 9.6'],
  MEMORY_PROFILER: [
    'M4.6 4.6h6.8v6.8H4.6z',
    'M6.5 4.6V2.3M9.5 4.6V2.3M6.5 13.7v-2.3M9.5 13.7v-2.3',
    'M4.6 6.5H2.3M4.6 9.5H2.3M13.7 6.5h-2.3M13.7 9.5h-2.3',
  ],
  FRAME_PROFILER: ['M2.3 4.4h11.4v7.2H2.3z', 'M6.1 4.4v7.2M9.9 4.4v7.2'],
  STARTUP_PROFILER: ['M9.1 1.9 4 8.7h3.4l-1 5.4 5.1-6.9H8.1z'],
  BATTERY_PROFILER: ['M2.1 5.4h9.6v5.2H2.1z', 'M13 7.1v1.8', 'M3.7 6.9h5v2.2h-5z'],
  NETWORK_PROFILER: ['M2.6 5.6h10.6', 'M10.9 3.2l2.3 2.4-2.3 2.4', 'M13.4 10.4H2.8', 'M5.1 8l-2.3 2.4L5.1 12.8'],
  GPU_INSPECTOR: ['M2.1 3.7h11.8v7.1H2.1z', 'M5.6 13.5h4.8', 'M8 10.8v2.7'],
  BENCHMARK_REGRESSION: ['M2.4 3.1v9.8h11.2', 'M4.7 5.6 7.3 8.3l2.2-2.1 3.3 4.1', 'M12.8 8.2v2.1h-2.1'],
  METHOD_RECORDING: ['M8 2.6a5.4 5.4 0 1 0 0 10.8 5.4 5.4 0 0 0 0-10.8z', 'M8 6.2a1.8 1.8 0 1 0 0 3.6 1.8 1.8 0 0 0 0-3.6z'],
  AI_ANALYSIS: ['M6.1 2.5 6.9 4.9 9.3 5.7 6.9 6.5 6.1 8.9 5.3 6.5 2.9 5.7 5.3 4.9z', 'M11.3 8.6l.5 1.4 1.4.5-1.4.5-.5 1.4-.5-1.4-1.4-.5 1.4-.5z'],
};

export interface DestinationIconProps {
  readonly destination: AppDestination;
}

export function DestinationIcon({ destination }: DestinationIconProps): JSX.Element {
  return (
    <svg className="nav__icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
      {ICON_PATHS[destination].map((path, index) => (
        <path
          key={String(index)}
          d={path}
          fill="none"
          stroke="currentColor"
          strokeWidth={1.4}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
    </svg>
  );
}
