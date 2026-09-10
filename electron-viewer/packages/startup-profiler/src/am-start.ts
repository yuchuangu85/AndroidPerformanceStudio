import type { PlatformLaunchMetrics } from './model.js';

export interface AmStartParseResult {
  readonly metrics: PlatformLaunchMetrics;
  readonly warnings: readonly string[];
}

function parseDurationMillis(value: string | undefined): number | undefined {
  if (value === undefined) return undefined;
  const trimmed = value.trim().replace(/ms$/i, '').trim();
  if (!/^\d+$/.test(trimmed)) return undefined;
  const parsed = Number(trimmed);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : undefined;
}

/** Port of AmStartOutputParser for the am start -W metric block. */
export function parseAmStartOutput(output: string): AmStartParseResult {
  const values = new Map<string, string>();
  const warnings: string[] = [];
  let complete = false;
  for (const rawLine of output.split('\n')) {
    const line = rawLine.trim();
    if (line.length === 0) continue;
    const delimiter = line.indexOf(':');
    if (delimiter > 0) {
      const key = line.slice(0, delimiter).trim();
      const value = line.slice(delimiter + 1).trim();
      values.set(key, value);
      if (key.toLowerCase() === 'warning' || key.toLowerCase() === 'error') {
        warnings.push(key + ': ' + value);
      }
    } else if (line.toLowerCase().startsWith('warning') || line.toLowerCase().startsWith('error')) {
      warnings.push(line);
    } else if (line.toLowerCase() === 'complete') {
      complete = true;
    }
  }

  const status = values.get('Status');
  const completeValue = values.get('Complete');
  const thisTime = parseDurationMillis(values.get('ThisTime'));
  const totalTime = parseDurationMillis(values.get('TotalTime'));
  const waitTime = parseDurationMillis(values.get('WaitTime'));
  const launchState = values.get('LaunchState');
  const activity = values.get('Activity');
  const metrics: PlatformLaunchMetrics = {
    ...(status !== undefined ? { status } : {}),
    ...(launchState !== undefined ? { launchState } : {}),
    ...(activity !== undefined ? { activity } : {}),
    ...(thisTime !== undefined ? { thisTimeMs: thisTime } : {}),
    ...(totalTime !== undefined ? { totalTimeMs: totalTime } : {}),
    ...(waitTime !== undefined ? { waitTimeMs: waitTime } : {}),
    complete: complete || completeValue?.toLowerCase() === 'true',
  };
  if (metrics.totalTimeMs === undefined) warnings.push('am start -W did not report TotalTime.');
  if (status !== undefined && status.toLowerCase() !== 'ok') {
    warnings.push('Activity Manager returned status ' + status + '.');
  }
  return { metrics, warnings: [...new Set(warnings)] };
}
