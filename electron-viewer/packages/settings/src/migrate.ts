import type { PreferenceNode } from './legacy-linux-xml.js';
import {
  APPLICATION_KEYS,
  LAYOUT_INSPECTOR_KEYS,
  LEGACY_DESKTOP_NODE,
  SIMPLEPERF_KEYS,
  mergeApplicationUiSettings,
  normalizeApplicationUiSettings,
  parseArgbColor,
  type ApplicationUiSettings,
  type ApplicationUiSettingsPatch,
  type CanvasBorderColorsSettings,
  type LayoutInspectorSettings,
} from './model.js';

export interface LegacyPreferenceSource {
  readNode(nodePath: string): Promise<PreferenceNode>;
}

export interface ApplicationSettingsMigration {
  readonly settings: ApplicationUiSettings;
  readonly migratedKeys: readonly string[];
  readonly missingKeys: readonly string[];
}

/** Every key the previous desktop app may have written into one prefs node. */
export const MIGRATED_PREFERENCE_KEYS: readonly string[] = [
  ...Object.values(APPLICATION_KEYS),
  ...LAYOUT_INSPECTOR_KEYS,
  ...Object.values(SIMPLEPERF_KEYS),
];

function legacyBoolean(value: string | undefined): boolean | undefined {
  if (value === 'true') return true;
  if (value === 'false') return false;
  return undefined;
}

type MutableLayoutInspectorPatch = {
  -readonly [K in keyof Omit<LayoutInspectorSettings, 'canvasBorderColors'>]?: LayoutInspectorSettings[K];
} & { canvasBorderColors?: Partial<CanvasBorderColorsSettings> };

/** Reads the view.*, canvas.* and simpleperf.* keys the Kotlin stores wrote. */
function migratedFeaturePatch(node: PreferenceNode): ApplicationUiSettingsPatch {
  const layoutInspector: MutableLayoutInspectorPatch = {};
  const booleans = {
    hideInvisibleHierarchyViews: 'view.hideInvisibleHierarchyViews',
    hideInvisibleFindings: 'view.hideInvisibleFindings',
    hideHierarchyIndices: 'view.hideHierarchyIndices',
    showHierarchyIds: 'view.showHierarchyIds',
    showHierarchyLayerVisibilityButtons: 'view.showHierarchyLayerVisibilityButtons',
    showVisibleViewBounds: 'view.showVisibleViewBounds',
  } as const;
  for (const field of Object.keys(booleans) as (keyof typeof booleans)[]) {
    const parsed = legacyBoolean(node.get(booleans[field]));
    if (parsed !== undefined) layoutInspector[field] = parsed;
  }
  const zOrder = legacyBoolean(node.get('view.canvasHitTestOrder.zOrder'));
  if (zOrder !== undefined) layoutInspector.canvasHitTestOrder = zOrder ? 'z-order' : 'smallest-area';
  const colorKeys = {
    normal: 'canvas.bounds.normal',
    hovered: 'canvas.bounds.hovered',
    selected: 'canvas.bounds.selected',
  } as const;
  const colors: { -readonly [K in keyof CanvasBorderColorsSettings]?: CanvasBorderColorsSettings[K] } = {};
  for (const field of Object.keys(colorKeys) as (keyof typeof colorKeys)[]) {
    const parsed = parseArgbColor(node.get(colorKeys[field]));
    if (parsed !== undefined) colors[field] = parsed;
  }
  const tooltipMode = node.get(SIMPLEPERF_KEYS.tooltipMode);
  return {
    layoutInspector: {
      ...layoutInspector,
      ...(Object.keys(colors).length > 0 ? { canvasBorderColors: colors } : {}),
    },
    ...(tooltipMode === 'FIXED' || tooltipMode === 'FOLLOW_MOUSE'
      ? { simpleperf: { flameTooltipMode: tooltipMode === 'FIXED' ? 'fixed' : 'follow-mouse' } }
      : {}),
  };
}

/**
 * Reads the keys written by the previous desktop app — application.* plus the
 * Layout Inspector view/canvas keys and the simpleperf keys that moved into the
 * settings JSON — and merges them onto the Electron defaults.
 */
export async function migrateApplicationSettings(
  source: LegacyPreferenceSource,
  nodePath: string = LEGACY_DESKTOP_NODE,
): Promise<ApplicationSettingsMigration> {
  const node = await source.readNode(nodePath);
  const theme = node.get(APPLICATION_KEYS.theme);
  const language = node.get(APPLICATION_KEYS.language);
  const androidSdkPath = node.get(APPLICATION_KEYS.androidSdkPath)?.trim();
  const migratedKeys: string[] = [];
  const missingKeys: string[] = [];
  for (const key of MIGRATED_PREFERENCE_KEYS) {
    if (node.has(key)) migratedKeys.push(key);
    else missingKeys.push(key);
  }
  const base = normalizeApplicationUiSettings({
    theme,
    language,
    ...(androidSdkPath !== undefined && androidSdkPath.length > 0 ? { androidSdkPath } : {}),
  });
  return {
    settings: mergeApplicationUiSettings(base, migratedFeaturePatch(node)),
    migratedKeys,
    missingKeys,
  };
}

export interface KeyValueMigration {
  readonly values: Readonly<Record<string, string>>;
  readonly migratedKeys: readonly string[];
  readonly missingKeys: readonly string[];
}

export async function migrateKeys(
  source: LegacyPreferenceSource,
  keys: readonly string[],
  nodePath: string = LEGACY_DESKTOP_NODE,
): Promise<KeyValueMigration> {
  const node = await source.readNode(nodePath);
  const values: Record<string, string> = {};
  const migratedKeys: string[] = [];
  const missingKeys: string[] = [];
  for (const key of keys) {
    const value = node.get(key);
    if (value === undefined) missingKeys.push(key);
    else {
      values[key] = value;
      migratedKeys.push(key);
    }
  }
  return { values, migratedKeys, missingKeys };
}
