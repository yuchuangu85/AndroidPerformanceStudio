import { join } from 'node:path';
import { JsonSettingsStore, migrateApplicationSettings, type LegacyPreferenceSource } from '@aps/settings';
import type { ApplicationUiSettings } from '../shared/settings-contract.js';

export interface SettingsServiceIo {
  readFile(path: string): Promise<string>;
  writeFile(path: string, contents: string): Promise<void>;
  mkdir(path: string): Promise<void>;
}

export interface LoadSettingsResult {
  readonly settings: ApplicationUiSettings;
  readonly migration: {
    readonly source: 'stored' | 'migrated' | 'default';
    readonly migratedKeys: readonly string[];
  };
}

/**
 * Loads settings.json, falling back to a one-time java.util.prefs migration.
 * A stored file always wins so migration never overwrites user changes.
 */
export async function loadApplicationSettings(options: {
  readonly userDataDirectory: string;
  readonly io: SettingsServiceIo;
  readonly legacySource: LegacyPreferenceSource;
}): Promise<LoadSettingsResult> {
  const store = new JsonSettingsStore(join(options.userDataDirectory, 'settings.json'), options.io);
  try {
    await options.io.readFile(join(options.userDataDirectory, 'settings.json'));
    return { settings: await store.load(), migration: { source: 'stored', migratedKeys: [] } };
  } catch {
    // No settings file yet: try the legacy application preferences.
  }
  const migrated = await migrateApplicationSettings(options.legacySource);
  if (migrated.migratedKeys.length > 0) {
    await store.save(migrated.settings);
    return {
      settings: migrated.settings,
      migration: { source: 'migrated', migratedKeys: migrated.migratedKeys },
    };
  }
  return { settings: migrated.settings, migration: { source: 'default', migratedKeys: [] } };
}
