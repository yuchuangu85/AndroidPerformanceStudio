export interface StartupEventMetrics {
  readonly displayedTimeMs?: number;
  readonly fullyDrawnTimeMs?: number;
  readonly warnings: readonly string[];
}

const DURATION = /(\d+)ms/gi;
const NUMBER = /\d+/g;

function lastDurationMillis(line: string): number | undefined {
  const matches = [...line.matchAll(DURATION)];
  const explicit = matches.at(-1)?.[1];
  if (explicit !== undefined) {
    const parsed = Number(explicit);
    if (Number.isSafeInteger(parsed)) return parsed;
  }
  const open = line.lastIndexOf('[');
  const close = line.lastIndexOf(']');
  const payload = open >= 0 && close > open ? line.slice(open + 1, close) : '';
  const numbers = [...payload.matchAll(NUMBER)].map((match) => Number(match[0]));
  const last = numbers.at(-1);
  return last !== undefined && Number.isSafeInteger(last) ? last : undefined;
}

/**
 * Port of StartupEventLogParser. Only lines that mention the package are used,
 * and a missing metric stays missing rather than being estimated.
 */
export function parseStartupEventLog(output: string, packageName: string): StartupEventMetrics {
  let displayed: number | undefined;
  let fullyDrawn: number | undefined;
  for (const line of output.split('\n')) {
    if (!line.includes(packageName)) continue;
    const lower = line.toLowerCase();
    if (lower.includes('fully_drawn_time') || lower.includes('fully drawn')) {
      fullyDrawn = lastDurationMillis(line) ?? fullyDrawn;
    } else if (lower.includes('activity_launch_time') || lower.includes('displayed')) {
      // The platform tag is am_activity_launch_time; matching the suffix also
      // covers the wm_ form used by the original Kotlin check.
      displayed = lastDurationMillis(line) ?? displayed;
    }
  }
  const warnings: string[] = [];
  if (displayed === undefined) warnings.push('No TTID (Displayed) event was found for ' + packageName + '.');
  if (fullyDrawn === undefined) {
    warnings.push('No TTFD (reportFullyDrawn) event was found for ' + packageName + '.');
  }
  return {
    ...(displayed !== undefined ? { displayedTimeMs: displayed } : {}),
    ...(fullyDrawn !== undefined ? { fullyDrawnTimeMs: fullyDrawn } : {}),
    warnings,
  };
}
