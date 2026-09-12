import { useEffect, useState, type JSX } from 'react';
import { translate, type ShellStringKey, type UiLanguage } from '../../../shared/i18n';
import type {
  ApplicationLanguagePreference,
  ApplicationThemePreference,
  ApplicationUiSettings,
  ApplicationUiSettingsPatch,
} from '../../../shared/settings-contract';
import { SettingsChoice, SettingsField, SettingsSection } from './controls';

export interface GeneralSettingsProps {
  readonly language: UiLanguage;
  readonly settings: ApplicationUiSettings;
  readonly onPatch: (patch: ApplicationUiSettingsPatch) => void;
}

const THEME_KEYS: Record<ApplicationThemePreference, ShellStringKey> = {
  system: 'shell.theme.system',
  light: 'shell.theme.light',
  dark: 'shell.theme.dark',
};

const LANGUAGE_KEYS: Record<ApplicationLanguagePreference, ShellStringKey> = {
  system: 'shell.language.system',
  simplified_chinese: 'shell.language.simplified_chinese',
  english: 'shell.language.english',
};

export function GeneralSettings({ language, settings, onPatch }: GeneralSettingsProps): JSX.Element {
  const storedPath = settings.androidSdkPath ?? '';
  const [draftPath, setDraftPath] = useState(storedPath);

  // A stored change from anywhere else (migration, another window) wins over a
  // half-typed draft only when it lands; typing never fights the field.
  useEffect(() => setDraftPath(storedPath), [storedPath]);

  const apply = (): void => {
    const trimmed = draftPath.trim();
    onPatch({ androidSdkPath: trimmed.length > 0 ? trimmed : null });
  };

  return (
    <>
      <SettingsSection title={translate('settings.general', language)}>
        <SettingsChoice
          label={translate('shell.language', language)}
          value={settings.language}
          options={(['system', 'simplified_chinese', 'english'] as const).map((option) => ({
            value: option,
            label: translate(LANGUAGE_KEYS[option], language),
          }))}
          onChange={(value) => onPatch({ language: value })}
        />
        <SettingsChoice
          label={translate('shell.theme', language)}
          value={settings.theme}
          options={(['system', 'light', 'dark'] as const).map((option) => ({
            value: option,
            label: translate(THEME_KEYS[option], language),
          }))}
          onChange={(value) => onPatch({ theme: value })}
        />
      </SettingsSection>

      <SettingsSection
        title="Android SDK"
        description={translate('settings.sdkPathHint', language)}
      >
        <SettingsField label={translate('settings.sdkPath', language)}>
          <input
            value={draftPath}
            spellCheck={false}
            placeholder="/Users/me/Library/Android/sdk"
            onChange={(event) => setDraftPath(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') apply();
            }}
          />
        </SettingsField>
        <div className="settings__actions">
          <button
            type="button"
            className="button"
            onClick={() => {
              void window.aps.chooseAndroidSdkDirectory().then((directory) => {
                if (directory === undefined) return;
                setDraftPath(directory);
                onPatch({ androidSdkPath: directory });
              });
            }}
          >
            {translate('settings.browse', language)}
          </button>
          <button type="button" className="button" disabled={draftPath.trim() === storedPath} onClick={apply}>
            {translate('settings.apply', language)}
          </button>
          <button
            type="button"
            className="button"
            disabled={draftPath.length === 0 && storedPath.length === 0}
            onClick={() => {
              setDraftPath('');
              onPatch({ androidSdkPath: null });
            }}
          >
            {translate('settings.clear', language)}
          </button>
        </div>
      </SettingsSection>
    </>
  );
}
