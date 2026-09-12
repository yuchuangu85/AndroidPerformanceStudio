import { normalizeApplicationUiSettings, type ApplicationUiSettings } from './model.js';

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
      return normalizeApplicationUiSettings(JSON.parse(await this.io.readFile(this.filePath)));
    } catch {
      // A missing or malformed file is a fresh install, not a failure.
      return normalizeApplicationUiSettings(undefined);
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
