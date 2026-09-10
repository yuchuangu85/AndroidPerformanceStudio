export type ApplicationThemePreference = 'system' | 'light' | 'dark';
export type ApplicationLanguagePreference = 'system' | 'simplified_chinese' | 'english';

export interface ApplicationUiSettings {
  readonly theme: ApplicationThemePreference;
  readonly language: ApplicationLanguagePreference;
  readonly androidSdkPath?: string;
}

export const DEFAULT_SETTINGS: ApplicationUiSettings = { theme: 'system', language: 'system' };
