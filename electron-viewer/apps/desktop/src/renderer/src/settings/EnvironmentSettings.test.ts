import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { AdbStatus, DeviceSummary, MigrationStatus, TraceProcessorStatus } from '../../../shared/ipc';
import { EnvironmentSettingsPage, type EnvironmentSettingsPageProps } from './EnvironmentSettings';

const ADB: AdbStatus = { available: true, executable: '/sdk/platform-tools/adb', source: 'Android SDK' };
const DEVICES: readonly DeviceSummary[] = [{ serial: 'emulator-5554', state: 'ONLINE', model: 'Pixel 8' }];
const TRACE_PROCESSOR: TraceProcessorStatus = {
  available: true,
  path: '/Applications/AndroidPerfermanceStudio.app/Contents/Resources/perfetto-tools/trace_processor_shell',
  version: 'v57.2',
};
const MIGRATION: MigrationStatus = { source: 'migrated', migratedKeys: ['theme', 'language'] };

function render(overrides: Partial<EnvironmentSettingsPageProps> = {}): string {
  return renderToStaticMarkup(
    createElement(EnvironmentSettingsPage, {
      language: 'en',
      adb: ADB,
      devices: DEVICES,
      traceProcessor: TRACE_PROCESSOR,
      migration: MIGRATION,
      onRefreshDevices: () => {},
      ...overrides,
    }),
  );
}

/**
 * These two cards used to sit under every destination page. The page that took
 * them is the only place the environment is reported, so the things it must
 * keep reporting are asserted here rather than by looking at a window.
 */
describe('environment settings', () => {
  it('reports the adb that was found and the devices it sees', () => {
    const markup = render();
    expect(markup).toContain('Devices');
    expect(markup).toContain('/sdk/platform-tools/adb');
    expect(markup).toContain('Android SDK');
    expect(markup).toContain('emulator-5554');
    expect(markup).toContain('Pixel 8');
  });

  it('reports the bundled Trace Processor and where the settings came from', () => {
    const markup = render();
    expect(markup).toContain('Trace Processor');
    expect(markup).toContain('v57.2');
    expect(markup).toContain('trace_processor_shell');
    expect(markup).toContain('Imported settings from the previous desktop app (2)');
  });

  it('says so instead of listing a device when adb found none', () => {
    const markup = render({ devices: [] });
    expect(markup).toContain('No authorized device found');
  });

  it('carries its own refresh action: the shell toolbar is hidden while Settings is open', () => {
    expect(render()).toContain('Refresh');
  });
});
