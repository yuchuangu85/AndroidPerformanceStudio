import { useEffect, useState, type JSX } from 'react';
import { translate, type ShellStringKey, type UiLanguage } from '../../../shared/i18n';
import type {
  ApplicationLanguagePreference,
  ApplicationThemePreference,
  ApplicationUiSettings,
  ApplicationUiSettingsPatch,
} from '../../../shared/settings-contract';
import { ACCENT_COLOR_PRESETS, DISPLAY_SCALE_PERCENTS } from '../../../shared/settings-contract';
import { SettingsField, SettingsSection, SettingsSelect } from './controls';

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
      <SettingsSection
        title={translate('settings.general', language)}
        description={translate('settings.displayScaleHint', language)}
      >
        <SettingsSelect
          label={translate('shell.language', language)}
          value={settings.language}
          options={(['system', 'simplified_chinese', 'english'] as const).map((option) => ({
            value: option,
            label: translate(LANGUAGE_KEYS[option], language),
          }))}
          onChange={(value) => onPatch({ language: value })}
        />
        <SettingsSelect
          label={translate('shell.theme', language)}
          value={settings.theme}
          options={(['system', 'light', 'dark'] as const).map((option) => ({
            value: option,
            label: translate(THEME_KEYS[option], language),
          }))}
          onChange={(value) => onPatch({ theme: value })}
        />
        <div className="settings__row">
          <span className="settings__row-label">{translate('settings.accentColor', language)}</span>
          <div
            className="settings__accents"
            role="radiogroup"
            aria-label={translate('settings.accentColor', language)}
          >
            {ACCENT_COLOR_PRESETS.map((preset) => {
              const name = translate(('accentColor.' + preset.key) as ShellStringKey, language);
              const chosen = settings.accentColor === preset.key;
              return (
                <button
                  key={preset.key}
                  type="button"
                  role="radio"
                  aria-checked={chosen}
                  aria-label={name}
                  title={name}
                  className={chosen ? 'settings__accent settings__accent--selected' : 'settings__accent'}
                  style={{ background: preset.color }}
                  onClick={() => onPatch({ accentColor: preset.key })}
                />
              );
            })}
          </div>
        </div>
        <SettingsSelect
          label={translate('settings.displayScale', language)}
          value={String(settings.displayScalePercent)}
          options={DISPLAY_SCALE_PERCENTS.map((percent) => ({
            value: String(percent),
            label: String(percent) + '%',
          }))}
          onChange={(value) => onPatch({ displayScalePercent: Number(value) })}
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
