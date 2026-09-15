import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import {
  activateDestination,
  DESTINATION_SUMMARY_KEYS,
  DESTINATION_TITLE_KEYS,
  HOME_DESTINATIONS,
  INITIAL_NAVIGATION_STATE,
  type AppDestination,
} from '../../shared/destinations';
import { resolveLanguage, translate, type UiLanguage } from '../../shared/i18n';
import type { ViewerMenuCommand } from '../../shared/viewer-menu';
import type { DeviceSummary, ShellSnapshot } from '../../shared/ipc';
import {
  mergeApplicationUiSettings,
  type ApplicationUiSettings,
  type ApplicationUiSettingsPatch,
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
import { SettingsIcon } from './SettingsIcon';
import { SettingsPage } from './settings/SettingsPage';
import { SourceWorkspacePanel } from './SourceWorkspacePanel';
import { StartupProfilerPanel } from './StartupProfilerPanel';
import { TraceAnalyzerPanel } from './TraceAnalyzerPanel';

interface PanelProps {
  readonly destination: AppDestination;
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
  /** Visited destinations, reported by the placeholder for one with no panel yet. */
  readonly retainedCount: number;
  readonly settings: ApplicationUiSettings;
  readonly onPatchSettings: (patch: ApplicationUiSettingsPatch) => void;
  readonly onOpenSettings: () => void;
  /** A command from the native menu bar, routed to the panel that owns its state. */
  readonly viewerCommand: ViewerMenuCommand | null;
}

/**
 * The native menu accepts only its six display fields. Persisted Layout
 * Inspector settings may grow independently, so never forward that object over
 * the strict menu IPC boundary.
 */
function viewerMenuView(settings: ApplicationUiSettings['layoutInspector'] | undefined) {
  return {
    hideInvisibleHierarchyViews: settings?.hideInvisibleHierarchyViews ?? false,
    hideInvisibleFindings: settings?.hideInvisibleFindings ?? false,
    hideHierarchyIndices: settings?.hideHierarchyIndices ?? false,
    showHierarchyLayerVisibilityButtons: settings?.showHierarchyLayerVisibilityButtons ?? false,
    showVisibleViewBounds: settings?.showVisibleViewBounds ?? true,
    showHierarchyIds: settings?.showHierarchyIds ?? true,
  };
}

/**
 * The migrated destination pages. Kept as one switch so the shell stays a shell:
 * a destination that has no panel yet keeps the placeholder rather than an
 * empty frame.
 */
function destinationPanel({
  destination,
  language,
  devices,
  retainedCount,
  settings,
  onPatchSettings,
  onOpenSettings,
  viewerCommand,
}: PanelProps): JSX.Element {
  switch (destination) {
    case 'PERFETTO':
      return <TraceAnalyzerPanel language={language} devices={devices} />;
    case 'LAYOUT_INSPECTOR':
      return (
        <LayoutInspectorPanel
          language={language}
          devices={devices}
          settings={settings.layoutInspector}
          onPatchSettings={onPatchSettings}
          viewerCommand={viewerCommand}
        />
      );
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
      return <CpuProfilerPanel language={language} devices={devices} settings={settings.simpleperf} />;
    case 'MEMORY_PROFILER':
      return <MemoryProfilerPanel language={language} devices={devices} />;
    case 'AI_ANALYSIS':
      return <AiAnalysisPanel language={language} onOpenSettings={onOpenSettings} />;
    case 'GPU_INSPECTOR':
      return <GpuInspectorPanel language={language} devices={devices} />;
    default:
      return <p className="content__muted">{retainedCount + ' destinations retained \u00b7 not migrated yet'}</p>;
  }
}

/**
 * What a destination adds to the shared content column. Home is the one page
 * whose sections still float as cards, so it keeps the gutter they float in; the
 * Layout Inspector is the one page that owns its height instead of growing with
 * its content.
 */
const CONTENT_BODY_MODIFIER: Partial<Record<AppDestination, string>> = {
  HOME: 'content__body--home',
  LAYOUT_INSPECTOR: 'content__body--fill',
};

/** The body class for a destination: the shared one plus its own modifier. */
function contentBodyClass(destination: AppDestination): string {
  const modifier = CONTENT_BODY_MODIFIER[destination];
  return modifier === undefined ? 'content__body' : 'content__body ' + modifier;
}

export function App(): JSX.Element {
  const [snapshot, setSnapshot] = useState<ShellSnapshot | null>(null);
  const [current, setCurrent] = useState<AppDestination>(INITIAL_NAVIGATION_STATE.current);
  const [retained, setRetained] = useState<readonly AppDestination[]>(INITIAL_NAVIGATION_STATE.retained);
  const [systemDark, setSystemDark] = useState<boolean>(() => window.matchMedia('(prefers-color-scheme: dark)').matches);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [viewerCommand, setViewerCommand] = useState<ViewerMenuCommand | null>(null);
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

  /**
   * The native menu bar is the Layout Inspector's action surface, the way it is
   * in the reference. Settings belongs to the shell; every other command travels
   * to the panel, which owns the panes and the selection behind it.
   */
  useEffect(
    () =>
      window.aps.onViewerMenuCommand((command) => {
        if (command.kind === 'action' && command.action === 'OPEN_SETTINGS') {
          setSettingsOpen(true);
          return;
        }
        setViewerCommand(command);
      }),
    [],
  );

  // The panel reports the menu state while it is on screen. Every other page
  // leaves the viewer actions dormant, so the menu says so instead of offering
  // commands that would reach nothing.
  useEffect(() => {
    if (current === 'LAYOUT_INSPECTOR') return;
    window.aps.updateViewerMenuState({
      language,
      available: false,
      hasSnapshot: false,
      hasSelection: false,
      autoScan: false,
      panels: { hierarchy: true, details: true, findings: true },
      view: viewerMenuView(snapshot?.settings.layoutInspector),
    });
  }, [current, language, snapshot]);
  const theme = resolvedTheme(snapshot?.settings.theme ?? 'system', systemDark);

  useEffect(() => {
    document.documentElement.dataset['theme'] = theme;
  }, [theme]);

  // The theme colour rides the same document attributes as the theme: the
  // stylesheet derives the whole accent family from the base the preset sets.
  const accentColor = snapshot?.settings.accentColor ?? 'default';
  useEffect(() => {
    document.documentElement.dataset['accent'] = accentColor;
  }, [accentColor]);

  /**
   * macOS draws the window's vibrancy material behind the page, so the page has
   * to stay transparent for it to show through. Everywhere else the shell paints
   * its own window colour, which keeps one stylesheet correct on all platforms.
   */
  useEffect(() => {
    document.documentElement.dataset['vibrancy'] = snapshot?.appInfo.platform === 'darwin' ? 'on' : 'off';
  }, [snapshot]);

  useEffect(() => {
    document.title = settingsOpen
      ? translate('settings.title', language)
      : translate(DESTINATION_TITLE_KEYS[current], language);
  }, [current, language, settingsOpen]);

  const navigate = useCallback(
    (destination: AppDestination) => {
      const next = activateDestination({ current, retained }, destination);
      setCurrent(next.current);
      setRetained(next.retained);
      setSettingsOpen(false);
      void window.aps.openDestination(next.current);
    },
    [current, retained],
  );

  /**
   * A settings change is applied to the rendered snapshot first and stored
   * second: the switch, the colour field and the canvas all answer immediately,
   * and a failed write rolls the page back to what is still on disk. Returns
   * whether the value reached settings.json.
   */
  const applySettings = useCallback(
    async (patch: ApplicationUiSettingsPatch): Promise<boolean> => {
      const previous = snapshot;
      if (previous !== null) {
        setSnapshot({ ...previous, settings: mergeApplicationUiSettings(previous.settings, patch) });
      }
      try {
        setSnapshot(await window.aps.updateSettings(patch));
        setError(null);
        return true;
      } catch (reason) {
        if (previous !== null) setSnapshot(previous);
        setError(reason instanceof Error ? reason.message : String(reason));
        return false;
      }
    },
    [snapshot],
  );

  /** Fire-and-forget for the panel toggles that have no error line of their own. */
  const patchSettings = useCallback(
    (patch: ApplicationUiSettingsPatch): void => {
      void applySettings(patch);
    },
    [applySettings],
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
      {/*
        The destination page stays mounted while Settings is open — hidden, not
        unmounted — so a capture selection survives a visit to the settings page.
      */}
      <main className="content" hidden={settingsOpen}>
        <header className="toolbar">
          <div className="toolbar__leading">
            {current === 'HOME' ? null : (
              <button
                type="button"
                className="button button--icon"
                aria-label={translate('shell.backToHome', language)}
                title={translate('shell.backToHome', language)}
                onClick={() => navigate('HOME')}
              >
                <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" focusable="false">
                  <path
                    d="M10.1 3.3 5.4 8l4.7 4.7"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth={1.6}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </button>
            )}
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
            <button
              type="button"
              className="button button--icon"
              aria-label={translate('settings.title', language)}
              title={translate('settings.title', language)}
              onClick={() => setSettingsOpen(true)}
            >
              <SettingsIcon />
            </button>
          </div>
        </header>

        <div className={contentBodyClass(current)}>
          {current === 'HOME' ? (
            <section className="home">
              <h1 className="home__title">{translate('app.name', language)}</h1>
              <p className="home__subtitle">{translate('shell.home.tagline', language)}</p>
              <nav className="home__grid" aria-label={translate('shell.navigation', language)}>
                {HOME_DESTINATIONS.map((destination) => (
                  <button
                    key={destination}
                    type="button"
                    className="card card--action"
                    onClick={() => navigate(destination)}
                  >
                    <span className="card__head">
                      <span className="card__glyph">
                        <DestinationIcon destination={destination} />
                      </span>
                      <span className="card__label">
                        {translate(DESTINATION_TITLE_KEYS[destination], language)}
                      </span>
                    </span>
                    <span className="card__summary">
                      {translate(DESTINATION_SUMMARY_KEYS[destination], language)}
                    </span>
                  </button>
                ))}
              </nav>
            </section>
          ) : (
            destinationPanel({
              destination: current,
              language,
              devices: snapshot.devices,
              retainedCount: retained.length,
              settings: snapshot.settings,
              onPatchSettings: patchSettings,
              onOpenSettings: () => setSettingsOpen(true),
              viewerCommand,
            })
          )}

          {error !== null ? <p className="card__error">{error}</p> : null}
        </div>
      </main>

      {settingsOpen ? (
        <SettingsPage
          language={language}
          snapshot={snapshot}
          onPatch={applySettings}
          onClose={() => setSettingsOpen(false)}
          onOpenDestination={navigate}
          onRefreshDevices={refreshDevices}
        />
      ) : null}
    </div>
  );
}
