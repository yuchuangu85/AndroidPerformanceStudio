export type ApplicationThemePreference = 'system' | 'light' | 'dark';
export type ApplicationLanguagePreference = 'system' | 'simplified_chinese' | 'english';

/**
 * The window's accent colour, by palette name. "default" is the AppKit blue the
 * shell ships with; the rest are the theme colours the General settings offer.
 * The colour lives here so the picker, the stylesheet and the store cannot
 * disagree about what a name means.
 */
export const ACCENT_COLOR_PRESETS = [
  { key: 'default', color: '#007AFF' },
  { key: 'banana-red', color: '#D4042D' },
  { key: 'warm-sun-orange', color: '#DB7A0E' },
  { key: 'cornflower-blue', color: '#5A92E5' },
  { key: 'jade-green', color: '#5E8034' },
  { key: 'merlot-pink', color: '#EB6D98' },
  { key: 'azure', color: '#41B5C2' },
  { key: 'lemon-yellow', color: '#FACA2E' },
  { key: 'royal-purple', color: '#722169' },
] as const;

export type ApplicationAccentPreference = (typeof ACCENT_COLOR_PRESETS)[number]['key'];

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
export type SimpleperfRateModePreference = 'FREQUENCY' | 'PERIOD';
/** Runtime target for opening a completed Simpleperf session. */
export type SimpleperfEnginePreference = 'local' | 'firefox-local' | 'firefox';

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
  /** Kotlin's archive.snapshotSizeMultiplier; scales compatible .apinspect limits. */
  readonly snapshotSizeMultiplier: number;
  readonly canvasHitTestOrder: CanvasHitTestOrderPreference;
  readonly canvasBorderColors: CanvasBorderColorsSettings;
}

export interface SimpleperfCaptureDefaults {
  readonly template: SamplingTemplatePreference;
  readonly target: SimpleperfTargetPreference;
  readonly event: string;
  readonly frequencyHertz: number;
  /** Number of events for simpleperf -c period mode. */
  readonly periodEvents: number;
  readonly rateMode: SimpleperfRateModePreference;
  readonly durationSeconds: number;
  readonly callGraph: SimpleperfCallGraphPreference;
  readonly scope: SimpleperfScopePreference;
}

export interface SimpleperfSettings {
  readonly flameTooltipMode: FlameTooltipModePreference;
  readonly engine: SimpleperfEnginePreference;
  readonly captureDefaults: SimpleperfCaptureDefaults;
}

export interface ApplicationUiSettings {
  readonly theme: ApplicationThemePreference;
  readonly language: ApplicationLanguagePreference;
  /** The accent family the shell paints from; see ACCENT_COLOR_PRESETS. */
  readonly accentColor: ApplicationAccentPreference;
  /** The whole window's display size, as a percentage of the default. */
  readonly displayScalePercent: number;
  readonly androidSdkPath?: string;
  readonly layoutInspector: LayoutInspectorSettings;
  readonly simpleperf: SimpleperfSettings;
}

/** A partial update; every level is merged, never replaced wholesale. */
export interface ApplicationUiSettingsPatch {
  readonly theme?: ApplicationThemePreference;
  readonly language?: ApplicationLanguagePreference;
  readonly accentColor?: ApplicationAccentPreference;
  readonly displayScalePercent?: number;
  /** null clears the stored SDK path. */
  readonly androidSdkPath?: string | null;
  readonly layoutInspector?: Partial<Omit<LayoutInspectorSettings, 'canvasBorderColors'>> & {
    readonly canvasBorderColors?: Partial<CanvasBorderColorsSettings>;
  };
  readonly simpleperf?: Partial<Omit<SimpleperfSettings, 'captureDefaults'>> & {
    readonly captureDefaults?: Partial<SimpleperfCaptureDefaults>;
  };
}

/** The steps the settings page offers; a stored value outside the range is clamped. */
export const DISPLAY_SCALE_PERCENTS: readonly number[] = [80, 90, 100, 110, 125, 150];
export const DEFAULT_DISPLAY_SCALE_PERCENT = 100;
const DISPLAY_SCALE_MIN_PERCENT = 75;
const DISPLAY_SCALE_MAX_PERCENT = 200;

/** Kotlin CaptureArchiveLimits bounds, persisted as archive.snapshotSizeMultiplier. */
export const MIN_CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIER = 1;
export const MAX_CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIER = 10;
export const DEFAULT_CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIER = 1;
export const CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIERS: readonly number[] = [
  1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
];

/**
 * A display size outside the range would leave the shell unusable rather than
 * merely wrong, so a stored value is clamped instead of rejected.
 */
export function parseDisplayScalePercent(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return DEFAULT_DISPLAY_SCALE_PERCENT;
  return Math.min(DISPLAY_SCALE_MAX_PERCENT, Math.max(DISPLAY_SCALE_MIN_PERCENT, Math.round(value)));
}

/** Mirrors CaptureArchiveLimits.fromStoredMultiplier() in the Kotlin desktop app. */
export function parseCaptureArchiveSnapshotSizeMultiplier(value: unknown): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(parsed)) return DEFAULT_CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIER;
  return Math.min(
    MAX_CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIER,
    Math.max(MIN_CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIER, Math.round(parsed)),
  );
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
  snapshotSizeMultiplier: DEFAULT_CAPTURE_ARCHIVE_SNAPSHOT_SIZE_MULTIPLIER,
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
  periodEvents: 1000,
  rateMode: 'FREQUENCY',
  durationSeconds: 10,
  callGraph: 'DWARF',
  scope: 'USER',
};

export const DEFAULT_SIMPLEPERF_SETTINGS: SimpleperfSettings = {
  flameTooltipMode: 'follow-mouse',
  engine: 'local',
  captureDefaults: DEFAULT_SIMPLEPERF_CAPTURE_DEFAULTS,
};

export const DEFAULT_APPLICATION_UI_SETTINGS: ApplicationUiSettings = {
  theme: 'system',
  language: 'system',
  accentColor: 'default',
  displayScalePercent: DEFAULT_DISPLAY_SCALE_PERCENT,
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

/** An unknown or missing accent falls back to the shell's own blue. */
export function parseAccentPreference(value: unknown): ApplicationAccentPreference {
  const match = ACCENT_COLOR_PRESETS.find((preset) => preset.key === value);
  return match === undefined ? 'default' : match.key;
}

/** The #RRGGBB the picker paints a preset with. */
export function accentColorOf(preference: ApplicationAccentPreference): string {
  return ACCENT_COLOR_PRESETS.find((preset) => preset.key === preference)?.color ?? '#007AFF';
}

export function parseCanvasHitTestOrder(value: unknown): CanvasHitTestOrderPreference {
  return value === 'z-order' ? 'z-order' : 'smallest-area';
}

export function parseFlameTooltipMode(value: unknown): FlameTooltipModePreference {
  return value === 'fixed' ? 'fixed' : 'follow-mouse';
}

/** Kotlin's LOCAL, FIREFOX_PROFILER_LOCAL and FIREFOX_PROFILER values map here. */
export function parseSimpleperfEngine(value: unknown): SimpleperfEnginePreference {
  if (value === 'firefox-local' || value === 'FIREFOX_PROFILER_LOCAL') return 'firefox-local';
  if (value === 'firefox' || value === 'FIREFOX_PROFILER') return 'firefox';
  return 'local';
}

export function parseSamplingTemplate(value: unknown): SamplingTemplatePreference {
  return SAMPLING_TEMPLATES.find((template) => template === value) ?? 'APP_CPU_BASIC';
}

export function parseSimpleperfTarget(value: unknown): SimpleperfTargetPreference {
  return value === 'SYSTEM_WIDE' ? 'SYSTEM_WIDE' : 'APP';
}

export function parseSimpleperfRateMode(value: unknown): SimpleperfRateModePreference {
  return value === 'PERIOD' ? 'PERIOD' : 'FREQUENCY';
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
    snapshotSizeMultiplier: parseCaptureArchiveSnapshotSizeMultiplier(source['snapshotSizeMultiplier']),
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
    engine: parseSimpleperfEngine(source['engine']),
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
      periodEvents: parseClampedNumber(
        capture['periodEvents'],
        defaults.captureDefaults.periodEvents,
        1,
        1000000000,
      ),
      rateMode: parseSimpleperfRateMode(capture['rateMode']),
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
    accentColor: parseAccentPreference(source['accentColor']),
    displayScalePercent: parseDisplayScalePercent(source['displayScalePercent']),
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
    ...(patch.accentColor !== undefined ? { accentColor: patch.accentColor } : {}),
    ...(patch.displayScalePercent !== undefined
      ? { displayScalePercent: patch.displayScalePercent }
      : {}),
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
    periodEvents: DEFAULT_SIMPLEPERF_CAPTURE_DEFAULTS.periodEvents,
    rateMode: 'FREQUENCY',
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
