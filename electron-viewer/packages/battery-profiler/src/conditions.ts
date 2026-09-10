/** Read-only experiment conditions recorded with each battery snapshot. */
export function parseScreenBrightness(output: string): string | undefined {
  const trimmed = output.trim();
  return /^-?\d+$/.test(trimmed) ? trimmed : undefined;
}

export function parseScreenOn(output: string): boolean | undefined {
  const wakefulness = /mWakefulness=(\w+)/.exec(output)?.[1];
  if (wakefulness !== undefined) return wakefulness.toLowerCase() === 'awake';
  const display = /Display Power:\s*state=(\w+)/.exec(output)?.[1];
  if (display !== undefined) return display.toUpperCase() === 'ON';
  return undefined;
}

export function parseDozeState(output: string): string | undefined {
  const state = /mState=([A-Z_]+)/.exec(output)?.[1];
  if (state !== undefined) return state;
  const deep = /mDeepSleepState=(\d+)/.exec(output)?.[1];
  const light = /mLightState=(\d+)/.exec(output)?.[1];
  if (deep === undefined && light === undefined) return undefined;
  return 'deep=' + (deep ?? '?') + ',light=' + (light ?? '?');
}

/**
 * Conditions describe the external state used to interpret a measurement. They
 * are recorded, never enforced: the experiment does not change device settings.
 */
export function parseBatteryConditions(
  brightnessOutput: string,
  powerOutput: string,
  deviceIdleOutput: string,
): Record<string, string> {
  const conditions: Record<string, string> = {};
  const brightness = parseScreenBrightness(brightnessOutput);
  if (brightness !== undefined) conditions['screenBrightness'] = brightness;
  const screenOn = parseScreenOn(powerOutput);
  if (screenOn !== undefined) conditions['screenOn'] = screenOn ? 'on' : 'off';
  const doze = parseDozeState(deviceIdleOutput);
  if (doze !== undefined) conditions['doze'] = doze;
  return conditions;
}
