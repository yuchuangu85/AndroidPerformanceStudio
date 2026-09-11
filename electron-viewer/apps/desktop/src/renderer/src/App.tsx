import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import {
  activateDestination,
  DESTINATIONS,
  DESTINATION_TITLE_KEYS,
  HOME_DESTINATIONS,
  INITIAL_NAVIGATION_STATE,
  type AppDestination,
} from '../../shared/destinations';
import { resolveLanguage, translate } from '../../shared/i18n';
import type { ShellSnapshot } from '../../shared/ipc';
import type {
  ApplicationLanguagePreference,
  ApplicationThemePreference,
} from '../../shared/settings-contract';
import { resolvedTheme } from '../../shared/theme';
import { AiAnalysisPanel } from './AiAnalysisPanel';
import { BatteryProfilerPanel } from './BatteryProfilerPanel';
import { CpuProfilerPanel } from './CpuProfilerPanel';
import { BenchmarkRegressionPanel } from './BenchmarkRegressionPanel';
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

  if (snapshot === null) {
    return (
      <main className="shell shell--loading">
        <p>{error ?? translate('shell.phase', language)}</p>
      </main>
    );
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <p className="sidebar__eyebrow">{translate('shell.phase', language)}</p>
        <h1 className="sidebar__title">{translate('app.name', language)}</h1>

        <nav className="nav">
          <button
            type="button"
            className={current === 'HOME' ? 'nav__item nav__item--active' : 'nav__item'}
            onClick={() => navigate('HOME')}
          >
            {translate('shell.home', language)}
          </button>
          {DESTINATIONS.filter((destination) => destination !== 'HOME').map((destination) => (
            <button
              key={destination}
              type="button"
              className={current === destination ? 'nav__item nav__item--active' : 'nav__item'}
              onClick={() => navigate(destination)}
            >
              {translate(DESTINATION_TITLE_KEYS[destination], language)}
            </button>
          ))}
        </nav>

        <section className="sidebar__section">
          <h2 className="sidebar__heading">{translate('shell.settings', language)}</h2>
          <label className="field">
            <span>{translate('shell.theme', language)}</span>
            <select
              value={snapshot.settings.theme}
              onChange={(event) => applySettings({ theme: event.target.value as ApplicationThemePreference })}
            >
              {THEME_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {translate(('shell.theme.' + option) as 'shell.theme.system', language)}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('shell.language', language)}</span>
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
        {current === 'HOME' ? (
          <>
            <h2 className="content__title">{translate('shell.home', language)}</h2>
            <div className="grid">
              {HOME_DESTINATIONS.map((destination) => (
                <button
                  key={destination}
                  type="button"
                  className="card card--action"
                  onClick={() => navigate(destination)}
                >
                  {translate(DESTINATION_TITLE_KEYS[destination], language)}
                </button>
              ))}
            </div>
          </>
        ) : (
          <>
            <h2 className="content__title">{translate(DESTINATION_TITLE_KEYS[current], language)}</h2>
            {current === 'PERFETTO' ? (
              <TraceAnalyzerPanel language={language} devices={snapshot.devices} />
            ) : current === 'LAYOUT_INSPECTOR' ? (
              <LayoutInspectorPanel language={language} devices={snapshot.devices} />
            ) : current === 'FRAME_PROFILER' ? (
              <FrameProfilerPanel language={language} devices={snapshot.devices} />
            ) : current === 'STARTUP_PROFILER' ? (
              <StartupProfilerPanel language={language} devices={snapshot.devices} />
            ) : current === 'BATTERY_PROFILER' ? (
              <BatteryProfilerPanel language={language} devices={snapshot.devices} />
            ) : current === 'NETWORK_PROFILER' ? (
              <NetworkProfilerPanel language={language} devices={snapshot.devices} />
            ) : current === 'BENCHMARK_REGRESSION' ? (
              <BenchmarkRegressionPanel language={language} devices={snapshot.devices} />
            ) : current === 'SOURCE_WORKSPACES' ? (
              <SourceWorkspacePanel language={language} devices={snapshot.devices} />
            ) : current === 'METHOD_RECORDING' ? (
              <MethodRecordingPanel language={language} devices={snapshot.devices} />
            ) : current === 'SIMPLEPERF' ? (
              <CpuProfilerPanel language={language} devices={snapshot.devices} />
            ) : current === 'MEMORY_PROFILER' ? (
              <MemoryProfilerPanel language={language} devices={snapshot.devices} />
            ) : current === 'AI_ANALYSIS' ? (
              <AiAnalysisPanel language={language} />
            ) : current === 'GPU_INSPECTOR' ? (
              <GpuInspectorPanel language={language} devices={snapshot.devices} />
            ) : (
              <p className="content__muted">
                {retained.length} destinations retained · not migrated yet
              </p>
            )}
          </>
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
          <button type="button" className="button" onClick={() => void window.aps.refreshDevices().then(setSnapshot)}>
            {translate('shell.devices.refresh', language)}
          </button>
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
      </main>
    </div>
  );
}

