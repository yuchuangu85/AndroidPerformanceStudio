export interface DeviceDisplay {
  readonly widthPx: number;
  readonly heightPx: number;
  /** Android density scale (dpi / 160). */
  readonly density: number;
}

const WM_SIZE = /(?:Physical|Override) size:\s*(\d+)x(\d+)/;
const WM_DENSITY = /(?:Physical|Override) density:\s*(\d+)/;

/** Parses wm size output, preferring the physical entry. */
export function parseWmSize(output: string): { widthPx: number; heightPx: number } | undefined {
  const matches = [...output.matchAll(new RegExp(WM_SIZE.source, 'g'))];
  const match = matches[0];
  if (match === undefined) return undefined;
  const widthPx = Number(match[1]);
  const heightPx = Number(match[2]);
  if (!Number.isFinite(widthPx) || !Number.isFinite(heightPx) || widthPx <= 0 || heightPx <= 0) return undefined;
  return { widthPx, heightPx };
}

/** Parses wm density output into a density scale. */
export function parseWmDensity(output: string): number | undefined {
  const match = WM_DENSITY.exec(output);
  if (match === null) return undefined;
  const dpi = Number(match[1]);
  if (!Number.isFinite(dpi) || dpi <= 0) return undefined;
  return dpi / 160;
}

export function parseDeviceDisplay(sizeOutput: string, densityOutput: string): DeviceDisplay | undefined {
  const size = parseWmSize(sizeOutput);
  if (size === undefined) return undefined;
  return { ...size, density: parseWmDensity(densityOutput) ?? 1 };
}
