import type { PreferenceNode } from './legacy-linux-xml.js';
import {
  APPLICATION_KEYS,
  DEFAULT_APPLICATION_UI_SETTINGS,
  LAYOUT_INSPECTOR_KEYS,
  LEGACY_DESKTOP_NODE,
  SIMPLEPERF_KEYS,
  parseLanguagePreference,
  parseThemePreference,
  type ApplicationUiSettings,
} from './model.js';

export interface LegacyPreferenceSource {
  readNode(nodePath: string): Promise<PreferenceNode>;
}

export interface ApplicationSettingsMigration {
  readonly settings: ApplicationUiSettings;
  readonly migratedKeys: readonly string[];
  readonly missingKeys: readonly string[];
}

/** Reads the application.* keys written by ApplicationUiSettingsStore. */
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
  for (const key of Object.values(APPLICATION_KEYS)) {
    if (node.has(key)) migratedKeys.push(key);
    else missingKeys.push(key);
  }
  return {
    settings: {
      theme: parseThemePreference(theme),
      language: parseLanguagePreference(language),
      ...(androidSdkPath !== undefined && androidSdkPath.length > 0 ? { androidSdkPath } : {}),
    },
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

export async function migrateLayoutInspectorPreferences(source: LegacyPreferenceSource): Promise<KeyValueMigration> {
  return migrateKeys(source, LAYOUT_INSPECTOR_KEYS);
}

export async function migrateSimpleperfPreferences(source: LegacyPreferenceSource): Promise<KeyValueMigration> {
  return migrateKeys(source, Object.values(SIMPLEPERF_KEYS));
}

export { DEFAULT_APPLICATION_UI_SETTINGS };
