import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import {
  activateDestination,
  DESTINATIONS,
  DESTINATION_TITLE_KEYS,
  HOME_DESTINATIONS,
  INITIAL_NAVIGATION_STATE,
  type AppDestination,
} from '../../shared/destinations';
import { resolveLanguage, translate, type UiLanguage } from '../../shared/i18n';
import type { DeviceSummary, ShellSnapshot } from '../../shared/ipc';
import type {
  ApplicationLanguagePreference,
  ApplicationThemePreference,
} from '../../shared/settings-contract';
import { resolvedTheme } from '../../shared/theme';
import { AiAnalysisPanel } from './AiAnalysisPanel';
import { BatteryProfilerPanel } from './BatteryProfilerPanel';
import { CpuProfilerPanel } from './CpuProfilerPanel';
import { BenchmarkRegressionPanel } from './BenchmarkRegressionPanel';
import { DestinationIcon } from './DestinationIcon';
import { FrameProfilerPanel } from './FrameProfilerPanel';
import { GpuInspectorPanel } from './GpuInspectorPanel';
import { LayoutInspectorPanel } from './LayoutInspectorPanel';
import { MemoryProfilerPanel } from './MemoryProfilerPanel';
import { MethodRecordingPanel } from './MethodRecordingPanel';
import { NetworkProfilerPanel } from './NetworkProfilerPanel';
import { SourceWorkspacePanel } from './SourceWorkspacePanel';
import { StartupProfilerPanel } from './StartupProfilerPanel';
import { TraceAnalyzerPanel } from './TraceAnalyzerPanel';

const THEME_OPTIONS: readonly ApplicationThemePreference[] = ['system', 'light', 'dark'];
const LANGUAGE_OPTIONS: readonly ApplicationLanguagePreference[] = ['system', 'english', 'simplified_chinese'];

interface PanelProps {
  readonly destination: AppDestination;
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
  /** Visited destinations, reported by the placeholder for one with no panel yet. */
  readonly retainedCount: number;
}

/**
 * The migrated destination pages. Kept as one switch so the shell stays a shell:
 * a destination that has no panel yet keeps the placeholder rather than an
 * empty frame.
 */
function destinationPanel({ destination, language, devices, retainedCount }: PanelProps): JSX.Element {
  switch (destination) {
    case 'PERFETTO':
      return <TraceAnalyzerPanel language={language} devices={devices} />;
    case 'LAYOUT_INSPECTOR':
      return <LayoutInspectorPanel language={language} devices={devices} />;
    case 'FRAME_PROFILER':
      return <FrameProfilerPanel language={language} devices={devices} />;
    case 'STARTUP_PROFILER':
      return <StartupProfilerPanel language={language} devices={devices} />;
    case 'BATTERY_PROFILER':
      return <BatteryProfilerPanel language={language} devices={devices} />;
    case 'NETWORK_PROFILER':
      return <NetworkProfilerPanel language={language} devices={devices} />;
    case 'BENCHMARK_REGRESSION':
      return <BenchmarkRegressionPanel language={language} devices={devices} />;
    case 'SOURCE_WORKSPACES':
      return <SourceWorkspacePanel language={language} devices={devices} />;
    case 'METHOD_RECORDING':
      return <MethodRecordingPanel language={language} devices={devices} />;
    case 'SIMPLEPERF':
      return <CpuProfilerPanel language={language} devices={devices} />;
    case 'MEMORY_PROFILER':
      return <MemoryProfilerPanel language={language} devices={devices} />;
    case 'AI_ANALYSIS':
      return <AiAnalysisPanel language={language} />;
    case 'GPU_INSPECTOR':
      return <GpuInspectorPanel language={language} devices={devices} />;
    default:
      return <p className="content__muted">{retainedCount + ' destinations retained \u00b7 not migrated yet'}</p>;
  }
}

export function App(): JSX.Element {
  const [snapshot, setSnapshot] = useState<ShellSnapshot | null>(null);
  const [current, setCurrent] = useState<AppDestination>(INITIAL_NAVIGATION_STATE.current);
  const [retained, setRetained] = useState<readonly AppDestination[]>(INITIAL_NAVIGATION_STATE.retained);
  const [systemDark, setSystemDark] = useState<boolean>(() => window.matchMedia('(prefers-color-scheme: dark)').matches);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const listener = (event: MediaQueryListEvent): void => setSystemDark(event.matches);
    query.addEventListener('change', listener);
    return () => query.removeEventListener('change', listener);
  }, []);

  useEffect(() => {
    window.aps
      .getShellSnapshot()
      .then(setSnapshot)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : String(reason)));
  }, []);

  const language = useMemo(
    () => resolveLanguage(snapshot?.settings.language ?? 'system', navigator.language),
    [snapshot],
  );
  const theme = resolvedTheme(snapshot?.settings.theme ?? 'system', systemDark);

  useEffect(() => {
    document.documentElement.dataset['theme'] = theme;
  }, [theme]);

  /**
   * macOS draws the window's vibrancy material behind the page, so the page has
   * to stay transparent for it to show through. Everywhere else the shell paints
   * its own window colour, which keeps one stylesheet correct on all platforms.
   */
  useEffect(() => {
    document.documentElement.dataset['vibrancy'] = snapshot?.appInfo.platform === 'darwin' ? 'on' : 'off';
  }, [snapshot]);

  useEffect(() => {
    document.title = translate(DESTINATION_TITLE_KEYS[current], language);
  }, [current, language]);

  const navigate = useCallback((destination: AppDestination) => {
    const next = activateDestination({ current, retained }, destination);
    setCurrent(next.current);
    setRetained(next.retained);
    void window.aps.openDestination(next.current);
  }, [current, retained]);

  const applySettings = useCallback(
    (patch: { theme?: ApplicationThemePreference; language?: ApplicationLanguagePreference }) => {
      window.aps.updateSettings(patch).then(setSnapshot).catch((reason: unknown) => {
        setError(reason instanceof Error ? reason.message : String(reason));
      });
    },
    [],
  );

  const refreshDevices = useCallback(() => {
    void window.aps.refreshDevices().then(setSnapshot).catch((reason: unknown) => {
      setError(reason instanceof Error ? reason.message : String(reason));
    });
  }, []);

  if (snapshot === null) {
    return (
      <main className="shell shell--loading">
        <p>{error ?? translate('shell.phase', language)}</p>
      </main>
    );
  }

  const title = translate(DESTINATION_TITLE_KEYS[current], language);
  const deviceStatus = snapshot.adb.available
    ? translate('shell.devices', language) +
      (snapshot.devices.length > 0 ? ' · ' + String(snapshot.devices.length) : '')
    : translate('status.unavailable', language);

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="sidebar__titlebar">
          <h1 className="sidebar__title">{translate('app.name', language)}</h1>
        </div>

        <nav className="nav" aria-label={translate('shell.navigation', language)}>
          {DESTINATIONS.map((destination) => {
            const active = current === destination;
            // Home is the app's own entry point, so it keeps the short label the
            // sidebar always used instead of the window title.
            const label =
              destination === 'HOME'
                ? translate('shell.home', language)
                : translate(DESTINATION_TITLE_KEYS[destination], language);
            return (
              <button
                key={destination}
                type="button"
                className={active ? 'nav__item nav__item--active' : 'nav__item'}
                aria-current={active ? 'page' : undefined}
                title={label}
                onClick={() => navigate(destination)}
              >
                <DestinationIcon destination={destination} />
                <span className="nav__label">{label}</span>
              </button>
            );
          })}
        </nav>

        <p className="sidebar__note">{translate('shell.phase', language)}</p>

        <section className="sidebar__section">
          <h2 className="sidebar__heading">{translate('shell.settings', language)}</h2>
          <div className="field field--stacked">
            <span className="field__label">{translate('shell.theme', language)}</span>
            <div className="segmented" role="group" aria-label={translate('shell.theme', language)}>
              {THEME_OPTIONS.map((option) => (
                <button
                  key={option}
                  type="button"
                  className={
                    snapshot.settings.theme === option
                      ? 'segmented__option segmented__option--selected'
                      : 'segmented__option'
                  }
                  aria-pressed={snapshot.settings.theme === option}
                  onClick={() => applySettings({ theme: option })}
                >
                  {translate(('shell.theme.' + option) as 'shell.theme.system', language)}
                </button>
              ))}
            </div>
          </div>
          <label className="field field--stacked">
            <span className="field__label">{translate('shell.language', language)}</span>
            <select
              value={snapshot.settings.language}
              onChange={(event) => applySettings({ language: event.target.value as ApplicationLanguagePreference })}
            >
              {LANGUAGE_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {translate(('shell.language.' + option) as 'shell.language.system', language)}
                </option>
              ))}
            </select>
          </label>
        </section>
      </aside>

      <main className="content">
        <header className="toolbar">
          <div className="toolbar__leading">
            <h2 className="toolbar__title">{current === 'HOME' ? translate('shell.home', language) : title}</h2>
            {current === 'HOME' ? null : (
              <span className="toolbar__subtitle">{translate('app.name', language)}</span>
            )}
          </div>
          <div className="toolbar__actions">
            <span
              className={snapshot.adb.available ? 'chip chip--ok' : 'chip chip--down'}
              title={snapshot.adb.available ? String(snapshot.adb.executable) : String(snapshot.adb.error ?? '')}
            >
              <span className="chip__dot" />
              {deviceStatus}
            </span>
            <button type="button" className="button" onClick={refreshDevices}>
              {translate('shell.devices.refresh', language)}
            </button>
          </div>
        </header>

        <div className="content__body">
          {current === 'HOME' ? (
            <div className="grid">
              {HOME_DESTINATIONS.map((destination) => (
                <button
                  key={destination}
                  type="button"
                  className="card card--action"
                  onClick={() => navigate(destination)}
                >
                  <span className="card__glyph">
                    <DestinationIcon destination={destination} />
                  </span>
                  <span className="card__label">{translate(DESTINATION_TITLE_KEYS[destination], language)}</span>
                </button>
              ))}
            </div>
          ) : (
            destinationPanel({
              destination: current,
              language,
              devices: snapshot.devices,
              retainedCount: retained.length,
            })
          )}

          <section className="card">
            <h3 className="card__title">{translate('shell.devices', language)}</h3>
            <p className="card__muted">
              {snapshot.adb.available
                ? snapshot.adb.executable + ' (' + String(snapshot.adb.source) + ')'
                : translate('status.unavailable', language) + ': ' + String(snapshot.adb.error ?? '')}
            </p>
            {snapshot.devices.length === 0 ? (
              <p className="card__muted">{translate('shell.devices.none', language)}</p>
            ) : (
              <ul className="list">
                {snapshot.devices.map((device) => (
                  <li key={device.serial}>
                    <code>{device.serial}</code> · {device.state}
                    {device.model !== undefined ? ' · ' + device.model : ''}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card">
            <h3 className="card__title">{translate('shell.traceProcessor', language)}</h3>
            <p className="card__muted">
              {snapshot.traceProcessor.available
                ? String(snapshot.traceProcessor.version) + ' · ' + String(snapshot.traceProcessor.path)
                : translate('status.unavailable', language) + ': ' + String(snapshot.traceProcessor.error ?? '')}
            </p>
            <p className="card__muted">
              {snapshot.migration.source === 'migrated'
                ? translate('shell.migration.migrated', language) + ' (' + snapshot.migration.migratedKeys.length + ')'
                : translate('shell.migration.fresh', language)}
            </p>
          </section>

          {error !== null ? <p className="card__error">{error}</p> : null}
        </div>
      </main>
    </div>
  );
}
