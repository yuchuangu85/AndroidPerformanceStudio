/**
 * The main process, the preload bridge and the renderer share one settings
 * shape. The canonical definition lives in @aps/settings so the JSON store, the
 * java.util.prefs migration and the settings page cannot drift apart.
 */
export type {
  ApplicationLanguagePreference,
  ApplicationThemePreference,
  ApplicationUiSettings,
  ApplicationUiSettingsPatch,
  CanvasBorderColorsSettings,
  CanvasHitTestOrderPreference,
  FlameTooltipModePreference,
  LayoutInspectorSettings,
  SamplingTemplatePreference,
  SimpleperfCallGraphPreference,
  SimpleperfCaptureDefaults,
  SimpleperfScopePreference,
  SimpleperfSettings,
  SimpleperfTargetPreference,
} from '@aps/settings';

export {
  CANVAS_BORDER_COLOR_PRESETS,
  DEFAULT_APPLICATION_UI_SETTINGS as DEFAULT_SETTINGS,
  DEFAULT_CANVAS_BORDER_COLORS,
  DEFAULT_LAYOUT_INSPECTOR_SETTINGS,
  DEFAULT_SIMPLEPERF_CAPTURE_DEFAULTS,
  DEFAULT_SIMPLEPERF_SETTINGS,
  SAMPLING_TEMPLATES,
  mergeApplicationUiSettings,
  normalizeApplicationUiSettings,
  parseArgbColor,
  samplingTemplateDefaults,
} from '@aps/settings';
