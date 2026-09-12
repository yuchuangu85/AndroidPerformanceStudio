import type { JSX } from 'react';

/**
 * The Settings glyph the toolbar draws, on the same 16x16 grid with the same
 * 1.4px round stroke as DestinationIcon. It is not a destination, so it does
 * not belong in that component's map.
 */
const GEAR_PATHS: readonly string[] = [
  'M8 5.7a2.3 2.3 0 1 0 0 4.6 2.3 2.3 0 0 0 0-4.6z',
  'M8 1.9v1.9M8 12.2v1.9M1.9 8h1.9M12.2 8h1.9',
  'M3.7 3.7l1.3 1.3M11 11l1.3 1.3M12.3 3.7 11 5M5 11l-1.3 1.3',
];

export function SettingsIcon(): JSX.Element {
  return (
    <svg className="settings-icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
      {GEAR_PATHS.map((path, index) => (
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
