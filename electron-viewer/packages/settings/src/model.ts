export type ApplicationThemePreference = 'system' | 'light' | 'dark';
export type ApplicationLanguagePreference = 'system' | 'simplified_chinese' | 'english';

export interface ApplicationUiSettings {
  readonly theme: ApplicationThemePreference;
  readonly language: ApplicationLanguagePreference;
  readonly androidSdkPath?: string;
}

export const DEFAULT_APPLICATION_UI_SETTINGS: ApplicationUiSettings = {
  theme: 'system',
  language: 'system',
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

export function parseThemePreference(value: string | undefined): ApplicationThemePreference {
  const normalized = value?.toLowerCase();
  return normalized === 'light' || normalized === 'dark' ? normalized : 'system';
}

export function parseLanguagePreference(value: string | undefined): ApplicationLanguagePreference {
  const normalized = value?.toLowerCase();
  return normalized === 'simplified_chinese' || normalized === 'english' ? normalized : 'system';
}
