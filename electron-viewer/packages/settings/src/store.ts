import {
  DEFAULT_APPLICATION_UI_SETTINGS,
  parseLanguagePreference,
  parseThemePreference,
  type ApplicationUiSettings,
} from './model.js';

export interface SettingsStoreIo {
  readFile(path: string): Promise<string>;
  writeFile(path: string, contents: string): Promise<void>;
  mkdir(path: string): Promise<void>;
}

/** JSON settings store for the Electron application data directory. */
export class JsonSettingsStore {
  private readonly filePath: string;
  private readonly io: SettingsStoreIo;

  constructor(filePath: string, io: SettingsStoreIo) {
    this.filePath = filePath;
    this.io = io;
  }

  async load(): Promise<ApplicationUiSettings> {
    try {
      const parsed: unknown = JSON.parse(await this.io.readFile(this.filePath));
      if (parsed === null || typeof parsed !== 'object') return DEFAULT_APPLICATION_UI_SETTINGS;
      const record = parsed as Record<string, unknown>;
      const androidSdkPath = typeof record['androidSdkPath'] === 'string' ? record['androidSdkPath'].trim() : '';
      return {
        theme: parseThemePreference(typeof record['theme'] === 'string' ? record['theme'] : undefined),
        language: parseLanguagePreference(typeof record['language'] === 'string' ? record['language'] : undefined),
        ...(androidSdkPath.length > 0 ? { androidSdkPath } : {}),
      };
    } catch {
      return DEFAULT_APPLICATION_UI_SETTINGS;
    }
  }

  async save(settings: ApplicationUiSettings): Promise<boolean> {
    try {
      await this.io.mkdir(this.filePath.replace(/[/\\][^/\\]*$/, ''));
      await this.io.writeFile(this.filePath, JSON.stringify(settings, null, 2) + '\n');
      return true;
    } catch {
      return false;
    }
  }
}
