export type ApplicationThemePreference = 'system' | 'light' | 'dark';
export type ApplicationLanguagePreference = 'system' | 'simplified_chinese' | 'english';

/** CanvasHitTestOrder: the order a canvas click cycles through stacked views. */
export type CanvasHitTestOrderPreference = 'smallest-area' | 'z-order';

/** FlameTooltipMode: where the flame graph's frame facts appear. */
export type FlameTooltipModePreference = 'follow-mouse' | 'fixed';

/** SamplingTemplate: the Kotlin capture presets, by their enum names. */
export type SamplingTemplatePreference =
  | 'APP_CPU_BASIC'
  | 'UI_THREAD_FOCUS'
  | 'NATIVE_HOTSPOT'
  | 'LOW_OVERHEAD'
  | 'SYSTEM_PROCESS';

export type SimpleperfTargetPreference = 'APP' | 'SYSTEM_WIDE';
export type SimpleperfCallGraphPreference = 'DWARF' | 'FRAME_POINTER' | 'NONE';
export type SimpleperfScopePreference = 'BOTH' | 'USER' | 'KERNEL';

/** Stored as #AARRGGBB, the format CanvasArgb.toHex writes. */
export interface CanvasBorderColorsSettings {
  readonly normal: string;
  readonly hovered: string;
  readonly selected: string;
}

export interface LayoutInspectorSettings {
  readonly hideInvisibleHierarchyViews: boolean;
  readonly hideInvisibleFindings: boolean;
  readonly hideHierarchyIndices: boolean;
  readonly showHierarchyIds: boolean;
  readonly showHierarchyLayerVisibilityButtons: boolean;
  readonly showVisibleViewBounds: boolean;
  readonly canvasHitTestOrder: CanvasHitTestOrderPreference;
  readonly canvasBorderColors: CanvasBorderColorsSettings;
}

export interface SimpleperfCaptureDefaults {
  readonly template: SamplingTemplatePreference;
  readonly target: SimpleperfTargetPreference;
  readonly event: string;
  readonly frequencyHertz: number;
  readonly durationSeconds: number;
  readonly callGraph: SimpleperfCallGraphPreference;
  readonly scope: SimpleperfScopePreference;
}

export interface SimpleperfSettings {
  readonly flameTooltipMode: FlameTooltipModePreference;
  readonly captureDefaults: SimpleperfCaptureDefaults;
}

export interface ApplicationUiSettings {
  readonly theme: ApplicationThemePreference;
  readonly language: ApplicationLanguagePreference;
  readonly androidSdkPath?: string;
  readonly layoutInspector: LayoutInspectorSettings;
  readonly simpleperf: SimpleperfSettings;
}

/** A partial update; every level is merged, never replaced wholesale. */
export interface ApplicationUiSettingsPatch {
  readonly theme?: ApplicationThemePreference;
  readonly language?: ApplicationLanguagePreference;
  /** null clears the stored SDK path. */
  readonly androidSdkPath?: string | null;
  readonly layoutInspector?: Partial<Omit<LayoutInspectorSettings, 'canvasBorderColors'>> & {
    readonly canvasBorderColors?: Partial<CanvasBorderColorsSettings>;
  };
  readonly simpleperf?: Partial<Omit<SimpleperfSettings, 'captureDefaults'>> & {
    readonly captureDefaults?: Partial<SimpleperfCaptureDefaults>;
  };
}

export const DEFAULT_CANVAS_BORDER_COLORS: CanvasBorderColorsSettings = {
  normal: '#FF7DD3FC',
  hovered: '#FFF59E0B',
  selected: '#FFEF4444',
};

/** Matches CanvasArgb's conversion to Compose colors, hue for hue. */
export const CANVAS_BORDER_COLOR_PRESETS: readonly string[] = [
  '#FF7DD3FC',
  '#FFF59E0B',
  '#FFEF4444',
  '#FF22C55E',
  '#FFA855F7',
  '#FFFFFFFF',
];

/** ViewDisplayOptions defaults, exactly as ViewDisplayOptionsStore reads them. */
export const DEFAULT_LAYOUT_INSPECTOR_SETTINGS: LayoutInspectorSettings = {
  hideInvisibleHierarchyViews: false,
  hideInvisibleFindings: false,
  hideHierarchyIndices: false,
  showHierarchyIds: true,
  showHierarchyLayerVisibilityButtons: false,
  showVisibleViewBounds: true,
  canvasHitTestOrder: 'smallest-area',
  canvasBorderColors: DEFAULT_CANVAS_BORDER_COLORS,
};

export const SAMPLING_TEMPLATES: readonly SamplingTemplatePreference[] = [
  'APP_CPU_BASIC',
  'UI_THREAD_FOCUS',
  'NATIVE_HOTSPOT',
  'LOW_OVERHEAD',
  'SYSTEM_PROCESS',
];

export const DEFAULT_SIMPLEPERF_CAPTURE_DEFAULTS: SimpleperfCaptureDefaults = {
  template: 'APP_CPU_BASIC',
  target: 'APP',
  event: 'cpu-clock',
  frequencyHertz: 1000,
  durationSeconds: 10,
  callGraph: 'DWARF',
  scope: 'USER',
};

export const DEFAULT_SIMPLEPERF_SETTINGS: SimpleperfSettings = {
  flameTooltipMode: 'follow-mouse',
  captureDefaults: DEFAULT_SIMPLEPERF_CAPTURE_DEFAULTS,
};

export const DEFAULT_APPLICATION_UI_SETTINGS: ApplicationUiSettings = {
  theme: 'system',
  language: 'system',
  layoutInspector: DEFAULT_LAYOUT_INSPECTOR_SETTINGS,
  simpleperf: DEFAULT_SIMPLEPERF_SETTINGS,
};

/** Legacy java.util.prefs node paths (dots become slashes). */
export const LEGACY_DESKTOP_NODE = 'com/androidperformancestudio/desktop';
export const LEGACY_AI_NODE = 'com/androidperformancestudio/ai';

export const APPLICATION_KEYS = {
  theme: 'application.theme',
  language: 'application.language',
  androidSdkPath: 'application.androidSdkPath',
} as const;

export const SIMPLEPERF_KEYS = {
  tooltipMode: 'simpleperf.tooltipMode',
  engine: 'simpleperf.engine',
} as const;

export const LAYOUT_INSPECTOR_KEYS = [
  'view.hideInvisibleHierarchyViews',
  'view.hideInvisibleFindings',
  'view.hideHierarchyIndices',
  'view.showHierarchyIds',
  'view.showHierarchyLayerVisibilityButtons',
  'view.showVisibleViewBounds',
  'view.canvasHitTestOrder.zOrder',
  'canvas.bounds.normal',
  'canvas.bounds.hovered',
  'canvas.bounds.selected',
  'archive.snapshotSizeMultiplier',
] as const;

export const AI_KEYS = {
  model: 'model',
  endpoint: 'endpoint',
} as const;

export function parseThemePreference(value: unknown): ApplicationThemePreference {
  const normalized = typeof value === 'string' ? value.toLowerCase() : '';
  return normalized === 'light' || normalized === 'dark' ? normalized : 'system';
}

export function parseLanguagePreference(value: unknown): ApplicationLanguagePreference {
  const normalized = typeof value === 'string' ? value.toLowerCase() : '';
  return normalized === 'simplified_chinese' || normalized === 'english' ? normalized : 'system';
}

export function parseCanvasHitTestOrder(value: unknown): CanvasHitTestOrderPreference {
  return value === 'z-order' ? 'z-order' : 'smallest-area';
}

export function parseFlameTooltipMode(value: unknown): FlameTooltipModePreference {
  return value === 'fixed' ? 'fixed' : 'follow-mouse';
}

export function parseSamplingTemplate(value: unknown): SamplingTemplatePreference {
  return SAMPLING_TEMPLATES.find((template) => template === value) ?? 'APP_CPU_BASIC';
}

export function parseSimpleperfTarget(value: unknown): SimpleperfTargetPreference {
  return value === 'SYSTEM_WIDE' ? 'SYSTEM_WIDE' : 'APP';
}

export function parseSimpleperfCallGraph(value: unknown): SimpleperfCallGraphPreference {
  if (value === 'FRAME_POINTER' || value === 'NONE') return value;
  return 'DWARF';
}

export function parseSimpleperfScope(
  value: unknown,
  fallback: SimpleperfScopePreference = 'BOTH',
): SimpleperfScopePreference {
  if (value === 'BOTH' || value === 'USER' || value === 'KERNEL') return value;
  return fallback;
}

/**
 * Accepts the same shapes CanvasArgb.parse does: #RRGGBB or #AARRGGBB, case
 * insensitive, and returns the #AARRGGBB form the Kotlin store writes.
 */
export function parseArgbColor(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const digits = value.trim().replace(/^#/, '');
  const argb = digits.length === 6 ? 'FF' + digits : digits;
  if (argb.length !== 8 || !/^[0-9a-fA-F]{8}$/.test(argb)) return undefined;
  return '#' + argb.toUpperCase();
}

function parseBoolean(value: unknown, fallback: boolean): boolean {
  if (typeof value === 'boolean') return value;
  if (value === 'true') return true;
  if (value === 'false') return false;
  return fallback;
}

function parseClampedNumber(value: unknown, fallback: number, minimum: number, maximum: number): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(maximum, Math.max(minimum, Math.round(parsed)));
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}

export function normalizeLayoutInspectorSettings(value: unknown): LayoutInspectorSettings {
  const source = record(value);
  const colors = record(source['canvasBorderColors']);
  const defaults = DEFAULT_LAYOUT_INSPECTOR_SETTINGS;
  return {
    hideInvisibleHierarchyViews: parseBoolean(
      source['hideInvisibleHierarchyViews'],
      defaults.hideInvisibleHierarchyViews,
    ),
    hideInvisibleFindings: parseBoolean(source['hideInvisibleFindings'], defaults.hideInvisibleFindings),
    hideHierarchyIndices: parseBoolean(source['hideHierarchyIndices'], defaults.hideHierarchyIndices),
    showHierarchyIds: parseBoolean(source['showHierarchyIds'], defaults.showHierarchyIds),
    showHierarchyLayerVisibilityButtons: parseBoolean(
      source['showHierarchyLayerVisibilityButtons'],
      defaults.showHierarchyLayerVisibilityButtons,
    ),
    showVisibleViewBounds: parseBoolean(source['showVisibleViewBounds'], defaults.showVisibleViewBounds),
    canvasHitTestOrder: parseCanvasHitTestOrder(source['canvasHitTestOrder']),
    canvasBorderColors: {
      normal: parseArgbColor(colors['normal']) ?? defaults.canvasBorderColors.normal,
      hovered: parseArgbColor(colors['hovered']) ?? defaults.canvasBorderColors.hovered,
      selected: parseArgbColor(colors['selected']) ?? defaults.canvasBorderColors.selected,
    },
  };
}

export function normalizeSimpleperfSettings(value: unknown): SimpleperfSettings {
  const source = record(value);
  const capture = record(source['captureDefaults']);
  const defaults = DEFAULT_SIMPLEPERF_SETTINGS;
  const event = typeof capture['event'] === 'string' && capture['event'].trim().length > 0
    ? capture['event'].trim()
    : defaults.captureDefaults.event;
  return {
    flameTooltipMode: parseFlameTooltipMode(source['flameTooltipMode']),
    captureDefaults: {
      template: parseSamplingTemplate(capture['template']),
      target: parseSimpleperfTarget(capture['target']),
      event,
      frequencyHertz: parseClampedNumber(
        capture['frequencyHertz'],
        defaults.captureDefaults.frequencyHertz,
        1,
        100000,
      ),
      durationSeconds: parseClampedNumber(
        capture['durationSeconds'],
        defaults.captureDefaults.durationSeconds,
        1,
        3600,
      ),
      callGraph: parseSimpleperfCallGraph(capture['callGraph']),
      scope: parseSimpleperfScope(capture['scope'], defaults.captureDefaults.scope),
    },
  };
}

export function normalizeApplicationUiSettings(value: unknown): ApplicationUiSettings {
  const source = record(value);
  const androidSdkPath = typeof source['androidSdkPath'] === 'string' ? source['androidSdkPath'].trim() : '';
  return {
    theme: parseThemePreference(source['theme']),
    language: parseLanguagePreference(source['language']),
    ...(androidSdkPath.length > 0 ? { androidSdkPath } : {}),
    layoutInspector: normalizeLayoutInspectorSettings(source['layoutInspector']),
    simpleperf: normalizeSimpleperfSettings(source['simpleperf']),
  };
}

/**
 * Applies one update. Every section merges key by key, so a caller that only
 * changes the theme cannot drop the canvas colors it never read.
 */
export function mergeApplicationUiSettings(
  current: ApplicationUiSettings,
  patch: ApplicationUiSettingsPatch,
): ApplicationUiSettings {
  const layout = patch.layoutInspector;
  const simpleperf = patch.simpleperf;
  const colors = layout?.canvasBorderColors;
  const capture = simpleperf?.captureDefaults;
  return normalizeApplicationUiSettings({
    ...current,
    ...(patch.theme !== undefined ? { theme: patch.theme } : {}),
    ...(patch.language !== undefined ? { language: patch.language } : {}),
    ...(patch.androidSdkPath !== undefined
      ? { androidSdkPath: patch.androidSdkPath === null ? '' : patch.androidSdkPath }
      : {}),
    layoutInspector: {
      ...current.layoutInspector,
      ...(layout ?? {}),
      ...(colors !== undefined
        ? { canvasBorderColors: { ...current.layoutInspector.canvasBorderColors, ...colors } }
        : {}),
    },
    simpleperf: {
      ...current.simpleperf,
      ...(simpleperf ?? {}),
      ...(capture !== undefined
        ? { captureDefaults: { ...current.simpleperf.captureDefaults, ...capture } }
        : {}),
    },
  });
}

/**
 * SamplingTemplate.create: the Kotlin presets, expressed as capture defaults.
 * An app-scoped target samples user space; a system-wide one samples both.
 */
export function samplingTemplateDefaults(
  template: SamplingTemplatePreference,
  target: SimpleperfTargetPreference,
): SimpleperfCaptureDefaults {
  const scope: SimpleperfScopePreference = target === 'APP' ? 'USER' : 'BOTH';
  const base: SimpleperfCaptureDefaults = {
    template,
    target,
    event: 'cpu-clock',
    frequencyHertz: 1000,
    durationSeconds: DEFAULT_SIMPLEPERF_CAPTURE_DEFAULTS.durationSeconds,
    callGraph: 'DWARF',
    scope,
  };
  switch (template) {
    case 'NATIVE_HOTSPOT':
      return { ...base, event: 'cpu-cycles', frequencyHertz: 1000 };
    case 'LOW_OVERHEAD':
      return { ...base, frequencyHertz: 100, callGraph: 'FRAME_POINTER' };
    case 'SYSTEM_PROCESS':
      return { ...base, frequencyHertz: 400, callGraph: 'FRAME_POINTER' };
    default:
      return base;
  }
}
