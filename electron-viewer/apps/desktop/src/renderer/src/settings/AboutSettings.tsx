import type { JSX } from 'react';
import { translate, type UiLanguage } from '../../../shared/i18n';
import type { AppInfo } from '../../../shared/ipc';
import { SettingsSection } from './controls';

export interface AboutSettingsPageProps {
  readonly language: UiLanguage;
  readonly appInfo: AppInfo;
}

export function AboutSettingsPage({ language, appInfo }: AboutSettingsPageProps): JSX.Element {
  return (
    <SettingsSection title={translate('settings.about', language)}>
      <div className="settings__about">
        <p className="settings__about-name">{appInfo.name}</p>
        <dl className="settings__about-rows">
          <div className="settings__row">
            <dt className="settings__row-label">{translate('settings.aboutVersion', language)}</dt>
            <dd className="settings__row-value">{appInfo.version}</dd>
          </div>
          <div className="settings__row">
            <dt className="settings__row-label">{translate('settings.aboutContract', language)}</dt>
            <dd className="settings__row-value">{'v' + String(appInfo.contractVersion)}</dd>
          </div>
          <div className="settings__row">
            <dt className="settings__row-label">{translate('settings.aboutPlatform', language)}</dt>
            <dd className="settings__row-value">{appInfo.platform}</dd>
          </div>
        </dl>
      </div>
    </SettingsSection>
  );
}
