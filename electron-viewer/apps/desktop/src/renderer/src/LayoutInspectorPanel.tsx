import { useCallback, useEffect, useMemo, useRef, useState, type JSX } from 'react';
import {
  analyzeLayout,
  clearHiddenLayers,
  computeHiddenSubtree,
  effectiveDefaultWindowId,
  effectiveWindows,
  hideLayer,
  showLayer,
  type HitTestOrder,
  type LayoutSnapshot,
  type WindowSnapshot,
} from '@aps/layout-inspector';
import type { DeviceSummary, LayoutCaptureDetail, LayoutCaptureSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';
import type { ApplicationUiSettingsPatch, LayoutInspectorSettings } from '../../shared/settings-contract';
import { argbToCss } from './layout-inspector/canvas';
import { nodeDetailSections, usesDeepDetailStripe } from './layout-inspector/details';
import {
  FINDINGS_LAYOUT,
  dragFindingsHeight,
  filterHiddenFindings,
  findingRows,
  fitFindingsHeight,
  severitySummary,
} from './layout-inspector/findings';
import { layoutText } from './layout-inspector/labels';
import { LayoutCanvas } from './layout-inspector/LayoutCanvas';
import { PanelToggleButton } from './layout-inspector/PanelToggleButton';
import {
  DEFAULT_PANE_WIDTHS,
  dragHierarchyWidth,
  dragPropertiesWidth,
  fitPaneWidths,
  type PaneWidths,
} from './layout-inspector/pane-layout';
import {
  NO_HIERARCHY_SEARCH,
  currentMatchedNodeId,
  isSearching,
  matchSummary,
  matchedNodeIds,
  navigateNext,
  navigatePrevious,
  searchSegments,
  withQuery,
  type HierarchySearchState,
} from './layout-inspector/hierarchy-search';
import {
  NO_ISOLATION,
  clearIsolation,
  isolate,
  isolatedRows,
  isolationActive,
  isolationParent,
  type HierarchyIsolationState,
} from './layout-inspector/hierarchy-isolation';
import {
  adjacentNodeId,
  buildLayoutTreeRows,
  hierarchyLabel,
  revealedCollapsed,
  toggledCollapsed,
  visibleTreeRows,
  type LayoutTreeRow,
} from './layout-inspector/tree';
import type { ViewerMenuCommand, ViewerMenuViewField } from '../../shared/viewer-menu';
import type { LayoutCaptureTarget } from '../../shared/ipc';

export interface LayoutInspectorPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
  /** The stored view and canvas preferences; the settings page owns them. */
  readonly settings: LayoutInspectorSettings;
  readonly onPatchSettings: (patch: ApplicationUiSettingsPatch) => void;
  /** A command from the native menu bar; null until one arrives. */
  readonly viewerCommand: ViewerMenuCommand | null;
}

/** The reference draws 20dp rows at 10sp with a 14dp indent. */
const ROW_HEIGHT = 20;
const INDENT = 14;
/**
 * The virtual window's height until the tree has been measured. The tree fills
 * its pane now, so this only covers the frame between the first render and the
 * ResizeObserver's first report.
 */
const TREE_FALLBACK_HEIGHT = 440;
/** The reference rescans every second (CAPTURE_INTERVAL_MILLIS) while auto scan is on. */
const AUTO_SCAN_INTERVAL_MS = 1000;
const OVERSCAN = 12;

/** The header's status reading: the tone colours the dot, the text is the line. */
interface LayoutStatus {
  readonly tone: 'neutral' | 'success' | 'error';
  readonly text: string;
}

const IDLE_STATUS: LayoutStatus = { tone: 'neutral', text: '' };

/** Which of the three panes are on screen; the Actions menu toggles them. */
interface PaneVisibility {
  readonly hierarchy: boolean;
  readonly details: boolean;
  readonly findings: boolean;
}

/** The device dropdown reads "model · serial", the way the reference's does. */
function deviceLabel(device: DeviceSummary): string {
  const model = device.model ?? '';
  return model.length === 0 ? device.serial : model + ' \u00b7 ' + device.serial;
}

interface ViewOptions {
  readonly showIds: boolean;
  readonly hideIndices: boolean;
  readonly hideInvisible: boolean;
  readonly hideInvisibleFindings: boolean;
}

/** ViewDisplayOptions, read from the stored Layout Inspector settings. */
function viewOptions(settings: LayoutInspectorSettings): ViewOptions {
  return {
    showIds: settings.showHierarchyIds,
    hideIndices: settings.hideHierarchyIndices,
    hideInvisible: settings.hideInvisibleHierarchyViews,
    hideInvisibleFindings: settings.hideInvisibleFindings,
  };
}

/** The option's storage key, so a quick toggle writes the same field the page does. */
const VIEW_OPTION_FIELDS = {
  showIds: 'showHierarchyIds',
  hideIndices: 'hideHierarchyIndices',
  hideInvisible: 'hideInvisibleHierarchyViews',
  hideInvisibleFindings: 'hideInvisibleFindings',
} as const;

type BooleanViewField = (typeof VIEW_OPTION_FIELDS)[keyof typeof VIEW_OPTION_FIELDS];

function windowOf(snapshot: LayoutSnapshot, windowId: string): WindowSnapshot {
  const windows = effectiveWindows(snapshot);
  return windows.find((window) => window.id === windowId) ?? (windows[0] as WindowSnapshot);
}

export function LayoutInspectorPanel({
  language,
  devices,
  settings,
  onPatchSettings,
  viewerCommand,
}: LayoutInspectorPanelProps): JSX.Element {
  const [captures, setCaptures] = useState<readonly LayoutCaptureSummary[]>([]);
  const [serial, setSerial] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<LayoutCaptureDetail | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [hoveredNodeId, setHoveredNodeId] = useState<string | undefined>(undefined);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [search, setSearch] = useState<HierarchySearchState>(NO_HIERARCHY_SEARCH);
  const [isolation, setIsolation] = useState<HierarchyIsolationState>(NO_ISOLATION);
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set());
  const [activeWindowId, setActiveWindowId] = useState('');
  const [scrollTop, setScrollTop] = useState(0);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<LayoutStatus>(IDLE_STATUS);
  const [findingsHeight, setFindingsHeight] = useState<number>(FINDINGS_LAYOUT.defaultHeight);
  const [paneWidths, setPaneWidths] = useState<PaneWidths>(DEFAULT_PANE_WIDTHS);
  const [treeHeight, setTreeHeight] = useState(0);
  const [selectedFindingKey, setSelectedFindingKey] = useState<string | undefined>(undefined);
  /** Pane visibility; the reference's Actions menu toggles the same three. */
  const [panes, setPanes] = useState<PaneVisibility>({ hierarchy: true, details: true, findings: true });
  const [autoScan, setAutoScan] = useState(false);
  /** The reference's CaptureTargetMode: the foreground app, or System UI. */
  const [captureTarget, setCaptureTarget] = useState<LayoutCaptureTarget>('foregroundApp');
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const layoutRef = useRef<HTMLDivElement | null>(null);

  // The stored view options drive the tree, the canvas and the findings pane;
  // the toggles in this toolbar write them back through the settings page's
  // own update path, so both surfaces always show the same values.
  const options = viewOptions(settings);
  const hitOrder: HitTestOrder = settings.canvasHitTestOrder;

  const refresh = useCallback(() => {
    window.aps.listLayoutCaptures().then((records) => {
      setCaptures(records);
      if (records.length > 0 && selectedId.length === 0) setSelectedId(records[0]?.id ?? '');
    });
  }, [selectedId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (selectedId.length === 0) {
      setDetail(null);
      return;
    }
    window.aps
      .loadLayoutCapture(selectedId)
      .then((loaded) => {
        setDetail(loaded ?? null);
        if (loaded === undefined) return;
        // Collapsed layers, hidden layers and the open window are per-snapshot;
        // a new capture starts fully expanded, exactly as the reference opens it.
        const windowId = effectiveDefaultWindowId(loaded.snapshot);
        setCollapsed(new Set());
        setHidden(clearHiddenLayers());
        setHoveredNodeId(undefined);
        setActiveWindowId(windowId);
        setSelectedNodeId(windowOf(loaded.snapshot, windowId).root.id);
        setScrollTop(0);
      })
      .catch((reason: unknown) =>
        setStatus({ tone: 'error', text: reason instanceof Error ? reason.message : String(reason) }),
      );
  }, [selectedId]);

  const capture = useCallback(() => {
    setBusy(true);
    setStatus(IDLE_STATUS);
    window.aps
      .captureLayout(serial, { target: captureTarget })
      .then((outcome) => {
        setStatus({
          tone: outcome.ok ? 'success' : 'error',
          text: outcome.ok
            ? translate('layout.complete', language)
            : translate('layout.failed', language) + ': ' + String(outcome.error ?? ''),
        });
        if (outcome.ok && outcome.id !== undefined) setSelectedId(outcome.id);
        refresh();
      })
      .catch((reason: unknown) =>
        setStatus({ tone: 'error', text: reason instanceof Error ? reason.message : String(reason) }),
      )
      .finally(() => setBusy(false));
  }, [captureTarget, language, refresh, serial]);

  const snapshot: LayoutSnapshot | null = detail?.snapshot ?? null;
  const windows = useMemo(() => (snapshot === null ? [] : effectiveWindows(snapshot)), [snapshot]);
  const activeWindow = useMemo(
    () => (snapshot === null ? null : windowOf(snapshot, activeWindowId)),
    [activeWindowId, snapshot],
  );
  const hiddenSubtree = useMemo(
    () => (activeWindow === null ? new Set<string>() : computeHiddenSubtree(hidden, activeWindow.root)),
    [activeWindow, hidden],
  );
  const rows: LayoutTreeRow[] = useMemo(
    () => (activeWindow === null ? [] : buildLayoutTreeRows(activeWindow.root)),
    [activeWindow],
  );
  // The isolated subtree is the tree, the canvas and nothing else: the rows are
  // cut to it and their depths rebased, the way HierarchyIsolationState does.
  const isolationRows = useMemo(() => isolatedRows(isolation, rows), [isolation, rows]);
  const visibleRows = useMemo(
    () => visibleTreeRows(isolationRows, collapsed, options.hideInvisible),
    [collapsed, isolationRows, options.hideInvisible],
  );
  const matchedIds = useMemo(() => matchedNodeIds(visibleRows, search), [search, visibleRows]);
  const matchedIdSet = useMemo(() => new Set(matchedIds), [matchedIds]);
  const currentMatchId = currentMatchedNodeId(search, matchedIds);
  const previewRoot = useMemo(
    () => rows.find((row) => row.node.id === isolation.rootNodeId)?.node ?? activeWindow?.root ?? null,
    [activeWindow, isolation, rows],
  );
  // The analysis engine owns the metrics, the findings and the rule ordering.
  const report = useMemo(() => (activeWindow === null ? null : analyzeLayout(activeWindow.root)), [activeWindow]);
  const allFindings = useMemo(
    () => (report === null ? [] : findingRows(report, rows, language)),
    [language, report, rows],
  );
  const shownFindings = useMemo(
    () => filterHiddenFindings(allFindings, rows, options.hideInvisibleFindings),
    [allFindings, options.hideInvisibleFindings, rows],
  );
  const summary = useMemo(() => severitySummary(shownFindings), [shownFindings]);

  const startFindingsDrag = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      event.preventDefault();
      const startY = event.clientY;
      const startHeight = findingsHeight;
      const available = layoutRef.current?.clientHeight ?? 800;
      const onMove = (move: MouseEvent): void => {
        setFindingsHeight(dragFindingsHeight(startHeight, move.clientY - startY, available));
      };
      const onUp = (): void => {
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [findingsHeight],
  );

  const viewportHeight = treeHeight > 0 ? treeHeight : TREE_FALLBACK_HEIGHT;
  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
  const end = Math.min(visibleRows.length, Math.ceil((scrollTop + viewportHeight) / ROW_HEIGHT) + OVERSCAN);
  const windowRows = visibleRows.slice(start, end);

  useEffect(() => {
    // The remembered height follows the pane the way FindingsLayout.fit does.
    const available = layoutRef.current?.clientHeight ?? 0;
    if (available > 0) setFindingsHeight((current) => fitFindingsHeight(current, available));
  }, [activeWindowId, snapshot]);

  useEffect(() => {
    // The remembered widths follow the pane the way PaneLayout.fit does: the
    // canvas keeps its minimum, and the side panes give way in layout order.
    const element = layoutRef.current;
    if (element === null) return;
    const update = (): void => {
      if (element.clientWidth <= 0) return;
      setPaneWidths((current) => fitPaneWidths(current, element.clientWidth));
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, [activeWindowId, snapshot]);

  useEffect(() => {
    // The tree fills its pane, so the virtual window follows the element's own
    // height instead of a fixed one.
    const element = scrollRef.current;
    if (element === null) return;
    const update = (): void => setTreeHeight(element.clientHeight);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, [activeWindowId, snapshot]);

  const selected = useMemo(
    () => rows.find((row) => row.node.id === selectedNodeId)?.node ?? activeWindow?.root ?? null,
    [rows, selectedNodeId, activeWindow],
  );
  const selectedRow = useMemo(() => rows.find((row) => row.node.id === selectedNodeId), [rows, selectedNodeId]);
  const detailSections = useMemo(
    () => (selected === null ? [] : nodeDetailSections(selected, (selectedRow?.depth ?? 0) + 1, language)),
    [language, selected, selectedRow],
  );

  /**
   * A separator drag. The delta is measured from where the press started, so
   * the pane tracks the pointer instead of accumulating rounding.
   */
  const startPaneDrag = useCallback(
    (side: 'hierarchy' | 'properties') =>
      (event: React.MouseEvent<HTMLDivElement>): void => {
        event.preventDefault();
        const startX = event.clientX;
        const startWidths = paneWidths;
        const available = layoutRef.current?.clientWidth ?? 0;
        if (available <= 0) return;
        const onMove = (move: MouseEvent): void => {
          const delta = move.clientX - startX;
          setPaneWidths(
            side === 'hierarchy'
              ? dragHierarchyWidth(startWidths, delta, available)
              : dragPropertiesWidth(startWidths, delta, available),
          );
        };
        const onUp = (): void => {
          window.removeEventListener('mousemove', onMove);
          window.removeEventListener('mouseup', onUp);
        };
        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup', onUp);
      },
    [paneWidths],
  );

  /**
   * Selecting reveals the node first — the canvas can point at a node inside a
   * collapsed subtree — and the scroll policy below brings its row into view.
   */
  const selectAndReveal = useCallback(
    (nodeId: string) => {
      setCollapsed((current) => revealedCollapsed(current, rows, nodeId));
      setSelectedNodeId(nodeId);
    },
    [rows],
  );

  useEffect(() => {
    // HierarchySelectionScrollPolicy: an already-visible row never moves the
    // tree, and one off screen lands as close to its edge as it can.
    const element = scrollRef.current;
    if (element === null) return;
    const index = visibleRows.findIndex((row) => row.node.id === selectedNodeId);
    if (index < 0) return;
    const first = Math.floor(element.scrollTop / ROW_HEIGHT);
    const last = first + Math.floor(element.clientHeight / ROW_HEIGHT);
    if (index >= first && index <= last) return;
    const target = index < first ? index : Math.max(0, index - (last - first));
    element.scrollTop = target * ROW_HEIGHT;
    setScrollTop(element.scrollTop);
  }, [selectedNodeId, visibleRows]);

  useEffect(() => {
    // HierarchyIsolationState.sanitize: a live rescan can drop the isolated
    // node, and an isolation that points at nothing is no isolation at all.
    setIsolation((current) => {
      const sanitized = isolate(current, current.rootNodeId, rows);
      return sanitized.rootNodeId === current.rootNodeId ? current : sanitized;
    });
  }, [rows]);

  /** The reference's Previous/Next node: the neighbour in the tree's own order. */
  const moveSelection = useCallback(
    (direction: 'up' | 'down') => {
      const neighbour = adjacentNodeId(visibleRows, selectedNodeId, direction);
      if (neighbour !== undefined) selectAndReveal(neighbour);
    },
    [selectedNodeId, selectAndReveal, visibleRows],
  );

  /** Reveal and select the match the search bar's arrow moved the cursor to. */
  const navigateMatch = useCallback(
    (direction: 'previous' | 'next') => {
      const next = direction === 'next' ? navigateNext(search, matchedIds) : navigatePrevious(search, matchedIds);
      setSearch(next);
      const nodeId = currentMatchedNodeId(next, matchedIds);
      if (nodeId !== undefined) selectAndReveal(nodeId);
    },
    [matchedIds, search, selectAndReveal],
  );

  /**
   * A row click selects and hands the pane the keyboard, the way the reference
   * requests focus on the pane whenever a row is clicked.
   */
  const selectRow = useCallback(
    (nodeId: string) => {
      scrollRef.current?.focus();
      selectAndReveal(nodeId);
    },
    [selectAndReveal],
  );

  const toggleLayerHidden = useCallback((nodeId: string) => {
    setHidden((current) => (current.has(nodeId) ? showLayer(current, nodeId) : hideLayer(current, nodeId)));
  }, []);

  const toggleCollapsed = useCallback((id: string) => {
    setCollapsed((current) => toggledCollapsed(current, rows, id));
  }, [rows]);

  /** The pane's own keys, the way the reference's focusable Column handles them. */
  const onTreeKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
        event.preventDefault();
        moveSelection(event.key === 'ArrowUp' ? 'up' : 'down');
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        setCollapsed((current) => toggledCollapsed(current, rows, selectedNodeId));
        return;
      }
      if (event.key === 'h' || event.key === 'H') {
        event.preventDefault();
        toggleLayerHidden(selectedNodeId);
      }
    },
    [moveSelection, rows, selectedNodeId, toggleLayerHidden],
  );

  /**
   * Auto scan, the reference's continuous rescan: one frame per second. It does
   * not go through the capture button's path, because that one archives a record
   * per press — a stored record per second would fill the store — and it leaves
   * the selection and the open window alone, the way a live refresh should. A
   * rescan that fails keeps the last frame; a rescan still in flight is skipped.
   */
  const autoScanInFlight = useRef(false);
  const autoCapture = useCallback(() => {
    if (autoScanInFlight.current) return;
    autoScanInFlight.current = true;
    window.aps
      .captureLayout(serial, { archive: false, target: captureTarget })
      .then((outcome) => {
        if (outcome.ok && outcome.detail !== undefined) {
          setDetail(outcome.detail);
          return;
        }
        // A live rescan that fails says so in the header; the next tick tries
        // again, so the text only changes when the reason does.
        const text = outcome.error ?? translate('layout.failed', language);
        setStatus((current) => (current.text === text ? current : { tone: 'error', text }));
      })
      .catch(() => {
        // The next tick tries again; the page keeps the frame it has.
      })
      .finally(() => {
        autoScanInFlight.current = false;
      });
  }, [captureTarget, serial]);

  const autoCaptureRef = useRef(autoCapture);
  useEffect(() => {
    autoCaptureRef.current = autoCapture;
  }, [autoCapture]);

  useEffect(() => {
    if (!autoScan || serial.length === 0) return;
    const timer = setInterval(() => autoCaptureRef.current(), AUTO_SCAN_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [autoScan, serial]);

  /**
   * What the menu bar should show. The panel owns the panes, the selection and
   * auto scan; the display options come from the settings the page writes.
   */
  useEffect(() => {
    window.aps.updateViewerMenuState({
      language,
      available: true,
      hasSnapshot: snapshot !== null,
      hasSelection: selectedNodeId.length > 0,
      autoScan,
      panels: panes,
      view: {
        hideInvisibleHierarchyViews: settings.hideInvisibleHierarchyViews,
        hideInvisibleFindings: settings.hideInvisibleFindings,
        hideHierarchyIndices: settings.hideHierarchyIndices,
        showHierarchyLayerVisibilityButtons: settings.showHierarchyLayerVisibilityButtons,
        showVisibleViewBounds: settings.showVisibleViewBounds,
        showHierarchyIds: settings.showHierarchyIds,
      },
    });
  }, [autoScan, language, panes, selectedNodeId, settings, snapshot]);

  const toggleOption = useCallback(
    (key: keyof ViewOptions) => {
      const update: Partial<Record<BooleanViewField, boolean>> = { [VIEW_OPTION_FIELDS[key]]: !options[key] };
      onPatchSettings({ layoutInspector: update });
    },
    [onPatchSettings, options],
  );

  /** One menu command, dispatched to the state it moves. */
  const runViewerCommand = useCallback(
    (command: ViewerMenuCommand) => {
      if (command.kind === 'viewOption') {
        const update: Partial<Record<ViewerMenuViewField, boolean>> = {
          [command.field]: !settings[command.field],
        };
        onPatchSettings({ layoutInspector: update });
        return;
      }
      switch (command.action) {
        case 'TOGGLE_AUTO_SCAN':
          setAutoScan((on) => !on);
          break;
        case 'PREVIOUS_NODE':
          moveSelection('up');
          break;
        case 'NEXT_NODE':
          moveSelection('down');
          break;
        case 'TOGGLE_SELECTED_NODE':
          if (selectedNodeId.length > 0) toggleCollapsed(selectedNodeId);
          break;
        case 'TOGGLE_HIERARCHY':
          setPanes((current) => ({ ...current, hierarchy: !current.hierarchy }));
          break;
        case 'TOGGLE_FINDINGS':
          setPanes((current) => ({ ...current, findings: !current.findings }));
          break;
        case 'TOGGLE_DETAILS':
          setPanes((current) => ({ ...current, details: !current.details }));
          break;
        case 'TOGGLE_HIERARCHY_IDS':
          toggleOption('showIds');
          break;
        case 'OPEN_SETTINGS':
          break;
      }
    },
    [moveSelection, onPatchSettings, selectedNodeId, settings, toggleCollapsed, toggleOption],
  );

  // The command object is fresh on every menu click, so this runs exactly once
  // per click and never replays when unrelated state moves.
  const runViewerCommandRef = useRef(runViewerCommand);
  useEffect(() => {
    runViewerCommandRef.current = runViewerCommand;
  });
  useEffect(() => {
    if (viewerCommand !== null) runViewerCommandRef.current(viewerCommand);
  }, [viewerCommand]);

  const selectWindow = useCallback(
    (windowId: string) => {
      if (snapshot === null) return;
      const window = windowOf(snapshot, windowId);
      setActiveWindowId(window.id);
      setSelectedNodeId(window.root.id);
      setCollapsed(new Set());
      setScrollTop(0);
    },
    [snapshot],
  );

  // The header's reading: what the last capture did, and otherwise what is on
  // screen. The reference shows its connection line in this position.
  const loadedRecord = captures.find((record) => record.id === selectedId) ?? null;
  const packageLabel = snapshot?.packageName ?? translate('layout.noApp', language);
  const targetLabel = (mode: LayoutCaptureTarget): string =>
    translate('layout.target', language) +
    ': ' +
    translate(mode === 'foregroundApp' ? 'layout.targetForegroundApp' : 'layout.targetSystemUi', language);
  const metricsText =
    report === null
      ? ''
      : layoutText('metrics.summary', language, report.metrics.nodeCount, report.metrics.maxDepth, report.metrics.widestLevel);
  const reading: LayoutStatus = busy
    ? { tone: 'neutral', text: translate('layout.capturing', language) }
    : status.text.length > 0
      ? status
      : loadedRecord === null
        ? IDLE_STATUS
        : { tone: 'neutral', text: loadedRecord.id + ' · ' + loadedRecord.packageName + ' · ' + loadedRecord.nodeCount };

  return (
    <div className={'layout-page' + (panes.findings ? '' : ' layout-page--no-findings')}>
      {/* The reference's header toolbar: the captured package on the left, the
          selectors inline after it, the status reading behind a divider, and the
          action pushed to the far right. Labels live in the controls' accessible
          names rather than above them, so the row stays one line tall. */}
      <header className="layout-header">
        <span className="layout-header__package">{packageLabel}</span>
        <select
          aria-label={translate('trace.device', language)}
          value={serial}
          onChange={(event) => setSerial(event.target.value)}
        >
          {/* The reference opens on Auto device and lets the capture resolve it. */}
          <option value="">{translate('layout.autoDevice', language)}</option>
          {devices.map((device) => (
            <option key={device.serial} value={device.serial}>
              {deviceLabel(device)}
            </option>
          ))}
        </select>
        <select
          aria-label={translate('layout.target', language)}
          value={captureTarget}
          onChange={(event) => setCaptureTarget(event.target.value as LayoutCaptureTarget)}
        >
          <option value="foregroundApp">{targetLabel('foregroundApp')}</option>
          <option value="systemUi">{targetLabel('systemUi')}</option>
        </select>
        <select
          aria-label={translate('layout.captures', language)}
          value={selectedId}
          onChange={(event) => setSelectedId(event.target.value)}
        >
          <option value="">{translate('layout.captures', language)}</option>
          {captures.map((record) => (
            <option key={record.id} value={record.id}>
              {record.id} · {record.packageName} · {record.nodeCount}
            </option>
          ))}
        </select>
        {windows.length > 1 ? (
          <select
            aria-label={layoutText('window.title', language)}
            value={activeWindowId}
            onChange={(event) => selectWindow(event.target.value)}
          >
            {windows.map((window) => (
              <option key={window.id} value={window.id}>
                {window.title}
              </option>
            ))}
          </select>
        ) : null}
        {reading.text.length > 0 ? (
          <>
            <span className="layout-header__divider" />
            <span className={'layout-header__dot layout-header__dot--' + reading.tone} />
            <span className={'layout-header__status layout-header__status--' + reading.tone}>{reading.text}</span>
          </>
        ) : null}
        <span className="layout-header__spacer" />
        {/* The reference hides the manual refresh while auto scan is running. */}
        {autoScan ? null : (
          <button
            type="button"
            className="button layout-header__action"
            title={translate('layout.refreshOnce', language)}
            disabled={busy || devices.length === 0}
            onClick={capture}
          >
            {busy ? translate('layout.capturing', language) : translate('layout.refresh', language)}
          </button>
        )}
        <button
          type="button"
          className={autoScan ? 'layout-switch layout-switch--on' : 'layout-switch'}
          role="switch"
          aria-checked={autoScan}
          onClick={() => setAutoScan((on) => !on)}
        >
          <span className="layout-switch__label">{translate('menu.autoScan', language)}</span>
          <span className="layout-switch__track">
            <span className="layout-switch__thumb" />
          </span>
        </button>
        <span className="layout-header__divider" />
        <span className="layout-header__metrics">{metricsText}</span>
        <span className="layout-header__divider" />
        <PanelToggleButton
          side="left"
          visible={panes.hierarchy}
          label={translate('menu.toggleHierarchy', language)}
          onClick={() => setPanes((current) => ({ ...current, hierarchy: !current.hierarchy }))}
        />
        <PanelToggleButton
          side="bottom"
          visible={panes.findings}
          label={translate('menu.toggleFindings', language)}
          onClick={() => setPanes((current) => ({ ...current, findings: !current.findings }))}
        />
        <PanelToggleButton
          side="right"
          visible={panes.details}
          label={translate('menu.toggleDetails', language)}
          onClick={() => setPanes((current) => ({ ...current, details: !current.details }))}
        />
      </header>

      {snapshot === null || activeWindow === null ? (
        <p className="content__muted">{translate('layout.none', language)}</p>
      ) : (
        <div
          className={
            'layout' +
            (panes.hierarchy ? '' : ' layout--no-hierarchy') +
            (panes.details ? '' : ' layout--no-details')
          }
          ref={layoutRef}
        >
          <section className="card layout__pane layout__pane--hierarchy" style={{ width: paneWidths.hierarchy }}>
            {/* PanelTitle: the count, then the isolation controls — the three
                display options live in the View menu, the way the reference
                keeps them out of this row. */}
            <div className="pane-header">
              <h3 className="pane-header__title">{layoutText('pane.hierarchy', language)}</h3>
              <div className="pane-header__options">
                <span className="pane-header__note">{visibleRows.length}</span>
                <button
                  type="button"
                  className="pane-action"
                  disabled={selectedNodeId.length === 0}
                  onClick={() => setIsolation((current) => isolate(current, selectedNodeId, rows))}
                >
                  {translate('layout.isolateSubtree', language)}
                </button>
                {isolationActive(isolation) ? (
                  <>
                    <button
                      type="button"
                      className="pane-action"
                      onClick={() => setIsolation((current) => isolationParent(current, rows))}
                    >
                      {translate('layout.isolateParent', language)}
                    </button>
                    <button
                      type="button"
                      className="pane-action"
                      onClick={() => setIsolation(clearIsolation())}
                    >
                      {translate('layout.clearIsolation', language)}
                    </button>
                  </>
                ) : null}
              </div>
            </div>
            {/* HierarchySearchBar: the query, its match cursor, and the arrows
                that walk the matches. */}
            <div className="tree-search">
              <div className="tree-search__field">
                <input
                  value={search.query}
                  spellCheck={false}
                  aria-label={translate('layout.searchHierarchy', language)}
                  placeholder={translate('layout.searchHierarchy', language)}
                  onChange={(event) => setSearch((current) => withQuery(current, event.target.value))}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') {
                      event.preventDefault();
                      navigateMatch('next');
                    } else if (event.key === 'Escape') {
                      setSearch(NO_HIERARCHY_SEARCH);
                    }
                  }}
                />
                {search.query.length === 0 ? null : (
                  <button
                    type="button"
                    className="tree-search__clear"
                    aria-label={translate('layout.searchClear', language)}
                    onClick={() => setSearch(NO_HIERARCHY_SEARCH)}
                  >
                    ✕
                  </button>
                )}
              </div>
              {isSearching(search) ? (
                <>
                  <span
                    className={
                      matchedIds.length === 0
                        ? 'tree-search__summary tree-search__summary--none'
                        : 'tree-search__summary'
                    }
                  >
                    {matchSummary(search, matchedIds) ?? translate('layout.searchNoMatch', language)}
                  </span>
                  <button
                    type="button"
                    className="tree-search__nav"
                    disabled={matchedIds.length === 0}
                    aria-label={translate('layout.searchPrevious', language)}
                    onClick={() => navigateMatch('previous')}
                  >
                    ◀
                  </button>
                  <button
                    type="button"
                    className="tree-search__nav"
                    disabled={matchedIds.length === 0}
                    aria-label={translate('layout.searchNext', language)}
                    onClick={() => navigateMatch('next')}
                  >
                    ▶
                  </button>
                </>
              ) : null}
            </div>
            <div
              className="tree"
              ref={scrollRef}
              tabIndex={0}
              onKeyDown={onTreeKeyDown}
              onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
              onMouseLeave={() => setHoveredNodeId(undefined)}
            >
              <div className="tree__spacer" style={{ height: visibleRows.length * ROW_HEIGHT }}>
                {/* Offset by margin rather than by position, so the rows stay in
                    flow: the longest of them is what makes the pane scroll
                    sideways, the way the reference's list does. */}
                <div className="tree__window" style={{ marginTop: start * ROW_HEIGHT }}>
                {windowRows.map((row) => {
                  const labelText = hierarchyLabel(row, {
                    hideIndex: options.hideIndices,
                    showId: options.showIds,
                  });
                  // Only a matching row is split, so the highlight never runs
                  // through a row the query did not find.
                  const segments =
                    isSearching(search) && matchedIdSet.has(row.node.id)
                      ? searchSegments(labelText, search.query)
                      : null;
                  const layerHidden = hidden.has(row.node.id);
                  return (
                    <div
                      key={row.node.id}
                      className={
                        'tree__row' +
                        (row.node.id === selectedNodeId
                          ? ' tree__row--active'
                          : row.node.id === currentMatchId
                            ? ' tree__row--current-match'
                            : matchedIdSet.has(row.node.id)
                              ? ' tree__row--match'
                              : row.node.id === hoveredNodeId
                                ? ' tree__row--hovered'
                                : '') +
                        (layerHidden || !row.visible ? ' tree__row--hidden' : '')
                      }
                      style={{ paddingLeft: 8 + row.depth * INDENT }}
                      onMouseEnter={() => setHoveredNodeId(row.node.id)}
                    >
                      {/* The reference draws an 8dp chevron in the accent colour,
                          and nothing at all on a leaf. */}
                      <span
                        className="tree__twisty"
                        role="presentation"
                        onClick={() => {
                          if (!row.hasChildren) return;
                          selectRow(row.node.id);
                          toggleCollapsed(row.node.id);
                        }}
                      >
                        {row.hasChildren ? (
                          <svg className="tree__chevron" viewBox="0 0 8 8" aria-hidden="true">
                            {collapsed.has(row.node.id) ? (
                              <path d="M2 0.5 L5.5 4 L2 7.5" />
                            ) : (
                              <path d="M0.5 2 L4 5.5 L7.5 2" />
                            )}
                          </svg>
                        ) : null}
                      </span>
                      {/* The reference draws the visibility button before the
                          label: with the pane scrolling sideways, a button after
                          a long label would sit off the edge. */}
                      {settings.showHierarchyLayerVisibilityButtons ? (
                        <span
                          className={layerHidden ? 'tree__action tree__action--hidden' : 'tree__action'}
                          role="presentation"
                          title={translate('layout.toggleLayerVisibility', language)}
                          onClick={() => toggleLayerHidden(row.node.id)}
                        >
                          {layerHidden
                            ? translate('layout.show', language)
                            : translate('layout.hide', language)}
                        </span>
                      ) : null}
                      <span className="tree__label" role="presentation" onClick={() => selectRow(row.node.id)}>
                        {segments === null
                          ? labelText
                          : segments.map((segment, index) =>
                              segment.match ? (
                                <span key={index} className="tree__label-match">
                                  {segment.text}
                                </span>
                              ) : (
                                segment.text
                              ),
                            )}
                      </span>
                    </div>
                  );
                })}
                </div>
              </div>
            </div>
          </section>

          <div
            className="pane-splitter pane-splitter--hierarchy"
            role="separator"
            aria-orientation="vertical"
            onMouseDown={startPaneDrag('hierarchy')}
          />

          <section className="card layout__pane layout__pane--canvas">
            <LayoutCanvas
              root={previewRoot ?? activeWindow.root}
              display={snapshot.display}
              {...(detail?.screenshotBase64 !== undefined ? { screenshotBase64: detail.screenshotBase64 } : {})}
              selectedNodeId={selectedNodeId}
              hiddenSubtree={hiddenSubtree}
              hiddenCount={hidden.size}
              hitOrder={hitOrder}
              borderColors={{
                normal: argbToCss(settings.canvasBorderColors.normal),
                hovered: argbToCss(settings.canvasBorderColors.hovered),
                selected: argbToCss(settings.canvasBorderColors.selected),
              }}
              showBounds={settings.showVisibleViewBounds}
              language={language}
              onSelect={selectAndReveal}
              onHover={setHoveredNodeId}
              onToggleHitOrder={() =>
                onPatchSettings({
                  layoutInspector: {
                    canvasHitTestOrder: hitOrder === 'smallest-area' ? 'z-order' : 'smallest-area',
                  },
                })
              }
              onClearHidden={() => setHidden(clearHiddenLayers())}
            />
          </section>

          <div
            className="pane-splitter pane-splitter--details"
            role="separator"
            aria-orientation="vertical"
            onMouseDown={startPaneDrag('properties')}
          />

          <section className="card layout__pane layout__pane--details" style={{ width: paneWidths.properties }}>
            <div className="pane-header">
              <h3 className="pane-header__title">{layoutText('pane.properties', language)}</h3>
            </div>
            {selected === null ? (
              <p className="card__muted">{translate('layout.noSelection', language)}</p>
            ) : (
              <div className="details">
                {detailSections.map((section) => (
                  <section
                    key={section.title}
                    className={
                      section.highlightsRenderingRisk === true
                        ? 'details__section details__section--risks'
                        : 'details__section'
                    }
                  >
                    <h4 className="details__title">{section.title}</h4>
                    <dl className="details__rows">
                      {section.rows.map((row, index) => (
                        <div
                          className={
                            'details__row' +
                            (usesDeepDetailStripe(index) ? ' details__row--deep' : '') +
                            ' details__row--' +
                            row.tone
                          }
                          key={row.label}
                        >
                          <dt className="details__label">{row.label}</dt>
                          <dd className="details__value">{row.value}</dd>
                        </div>
                      ))}
                    </dl>
                  </section>
                ))}
              </div>
            )}
          </section>
        </div>
      )}

      {/* The findings pane is the workspace's sibling, not one of its panes: it
          spans the window under the three columns, the way the reference's
          weight(1f) column puts it. */}
      <div
        className="findings-splitter"
        role="separator"
        aria-orientation="horizontal"
        onMouseDown={startFindingsDrag}
      />

      <section className="card findings" style={{ height: findingsHeight }}>
        <div className="pane-header">
          <h3 className="pane-header__title">{layoutText('pane.findings', language)}</h3>
          <div className="pane-header__options">
            <span className="badge badge--info">{layoutText('badge.info', language, summary.info)}</span>
            <span className="badge badge--warning">{layoutText('badge.warning', language, summary.warning)}</span>
            <span className="badge badge--error">{layoutText('badge.error', language, summary.error)}</span>
            <button
              type="button"
              className={options.hideInvisibleFindings ? 'toggle toggle--on' : 'toggle'}
              aria-pressed={options.hideInvisibleFindings}
              onClick={() => toggleOption('hideInvisibleFindings')}
            >
              {layoutText('view.hideInvisibleFindings', language)}
            </button>
          </div>
        </div>
        {shownFindings.length === 0 ? (
          <p className="card__muted">{layoutText('findings.none', language)}</p>
        ) : (
          <div className="findings__list">
            {shownFindings.map((finding) => (
              <div
                key={finding.key}
                className={
                  'finding finding--' +
                  finding.tone +
                  (finding.key === selectedFindingKey ? ' finding--selected' : '')
                }
                title={finding.nodeId}
                onClick={() => setSelectedFindingKey(finding.key)}
                onDoubleClick={() => selectAndReveal(finding.nodeId)}
              >
                {'[' + finding.nodeNumber + ']  ' + finding.title + '  ·  ' + finding.message}
              </div>
            ))}
          </div>
        )}
      </section>

    </div>
  );
}
