import type { JSX } from 'react';
import { translate, type UiLanguage } from '../../../shared/i18n';
import type { AdbStatus, DeviceSummary, MigrationStatus, TraceProcessorStatus } from '../../../shared/ipc';
import { SettingsSection } from './controls';

export interface EnvironmentSettingsPageProps {
  readonly language: UiLanguage;
  readonly adb: AdbStatus;
  readonly devices: readonly DeviceSummary[];
  readonly traceProcessor: TraceProcessorStatus;
  readonly migration: MigrationStatus;
  readonly onRefreshDevices: () => void;
}

/**
 * The machine the workstation runs against: adb with the devices it can see,
 * and the bundled Trace Processor.
 *
 * The two were cards at the bottom of every destination page. Nothing on those
 * pages acts on them, they answer a question asked rarely, and they pushed the
 * content they sat under upward on every visit — so they moved here, to a page
 * that only reports the environment. Refresh lives on this page because the
 * shell's own toolbar is hidden while Settings is open.
 */
export function EnvironmentSettingsPage({
  language,
  adb,
  devices,
  traceProcessor,
  migration,
  onRefreshDevices,
}: EnvironmentSettingsPageProps): JSX.Element {
  return (
    <>
      <SettingsSection
        title={translate('shell.devices', language)}
        description={
          adb.available
            ? adb.executable + ' (' + String(adb.source) + ')'
            : translate('status.unavailable', language) + ': ' + String(adb.error ?? '')
        }
      >
        {devices.length === 0 ? (
          <p className="settings__section-note">{translate('shell.devices.none', language)}</p>
        ) : (
          <ul className="list">
            {devices.map((device) => (
              <li key={device.serial}>
                <code>{device.serial}</code> · {device.state}
                {device.model !== undefined ? ' · ' + device.model : ''}
              </li>
            ))}
          </ul>
        )}
        <div className="settings__actions">
          <button type="button" className="button" onClick={onRefreshDevices}>
            {translate('shell.devices.refresh', language)}
          </button>
        </div>
      </SettingsSection>

      <SettingsSection
        title={translate('shell.traceProcessor', language)}
        description={
          traceProcessor.available
            ? String(traceProcessor.version) + ' · ' + String(traceProcessor.path)
            : translate('status.unavailable', language) + ': ' + String(traceProcessor.error ?? '')
        }
      >
        <p className="settings__section-note">
          {migration.source === 'migrated'
            ? translate('shell.migration.migrated', language) + ' (' + migration.migratedKeys.length + ')'
            : translate('shell.migration.fresh', language)}
        </p>
      </SettingsSection>
    </>
  );
}
