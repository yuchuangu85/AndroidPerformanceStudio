import { useCallback, useEffect, useMemo, useRef, useState, type JSX } from 'react';
import {
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
import { nodeDetailSections } from './layout-inspector/details';
import { layoutText } from './layout-inspector/labels';
import { LayoutCanvas } from './layout-inspector/LayoutCanvas';
import {
  buildLayoutTreeRows,
  hierarchyLabel,
  treeMetrics,
  visibleTreeRows,
  type LayoutTreeRow,
} from './layout-inspector/tree';

export interface LayoutInspectorPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

/** The reference draws 20dp rows at 10sp with a 14dp indent. */
const ROW_HEIGHT = 20;
const INDENT = 14;
const VIEWPORT_HEIGHT = 440;
const OVERSCAN = 12;

interface ViewOptions {
  readonly showIds: boolean;
  readonly hideIndices: boolean;
  readonly hideInvisible: boolean;
}

const DEFAULT_VIEW_OPTIONS: ViewOptions = { showIds: true, hideIndices: false, hideInvisible: false };

function windowOf(snapshot: LayoutSnapshot, windowId: string): WindowSnapshot {
  const windows = effectiveWindows(snapshot);
  return windows.find((window) => window.id === windowId) ?? (windows[0] as WindowSnapshot);
}

export function LayoutInspectorPanel({ language, devices }: LayoutInspectorPanelProps): JSX.Element {
  const [captures, setCaptures] = useState<readonly LayoutCaptureSummary[]>([]);
  const [serial, setSerial] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<LayoutCaptureDetail | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [hoveredNodeId, setHoveredNodeId] = useState<string | undefined>(undefined);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set());
  const [activeWindowId, setActiveWindowId] = useState('');
  const [options, setOptions] = useState<ViewOptions>(DEFAULT_VIEW_OPTIONS);
  const [hitOrder, setHitOrder] = useState<HitTestOrder>('smallest-area');
  const [scrollTop, setScrollTop] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

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
    if (serial.length === 0 && devices.length > 0) setSerial(devices[0]?.serial ?? '');
  }, [devices, serial]);

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
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [selectedId]);

  const capture = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .captureLayout(serial)
      .then((outcome) => {
        setMessage(
          outcome.ok
            ? translate('layout.complete', language)
            : translate('layout.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        if (outcome.ok && outcome.id !== undefined) setSelectedId(outcome.id);
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, refresh, serial]);

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
  const visibleRows = useMemo(
    () => visibleTreeRows(rows, collapsed, options.hideInvisible),
    [collapsed, options.hideInvisible, rows],
  );
  const metrics = useMemo(() => treeMetrics(rows), [rows]);

  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
  const end = Math.min(visibleRows.length, Math.ceil((scrollTop + VIEWPORT_HEIGHT) / ROW_HEIGHT) + OVERSCAN);
  const windowRows = visibleRows.slice(start, end);

  const selected = useMemo(
    () => rows.find((row) => row.node.id === selectedNodeId)?.node ?? activeWindow?.root ?? null,
    [rows, selectedNodeId, activeWindow],
  );
  const selectedRow = useMemo(() => rows.find((row) => row.node.id === selectedNodeId), [rows, selectedNodeId]);
  const detailSections = useMemo(
    () => (selected === null ? [] : nodeDetailSections(selected, (selectedRow?.depth ?? 0) + 1, language)),
    [language, selected, selectedRow],
  );

  /** Selecting from the canvas scrolls the row into view, as the reference does. */
  const selectAndReveal = useCallback(
    (nodeId: string) => {
      setSelectedNodeId(nodeId);
      const index = visibleRows.findIndex((row) => row.node.id === nodeId);
      if (index < 0) return;
      const first = Math.floor(scrollTop / ROW_HEIGHT);
      const last = first + Math.floor(VIEWPORT_HEIGHT / ROW_HEIGHT);
      if (index >= first && index <= last) return;
      const target = Math.max(0, (index - Math.floor((last - first) / 2)) * ROW_HEIGHT);
      setScrollTop(target);
      if (scrollRef.current !== null) scrollRef.current.scrollTop = target;
    },
    [scrollTop, visibleRows],
  );

  const toggleCollapsed = useCallback((id: string) => {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const toggleOption = useCallback((key: keyof ViewOptions) => {
    setOptions((current) => ({ ...current, [key]: !current[key] }));
  }, []);

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

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('layout.capture', language)}</h3>
        <div className="form">
          <label className="field">
            <span>{translate('trace.device', language)}</span>
            <select value={serial} onChange={(event) => setSerial(event.target.value)}>
              {devices.length === 0 ? <option value="">{translate('trace.noDevice', language)}</option> : null}
              {devices.map((device) => (
                <option key={device.serial} value={device.serial}>
                  {device.serial}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('layout.captures', language)}</span>
            <select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
              <option value="">-</option>
              {captures.map((record) => (
                <option key={record.id} value={record.id}>
                  {record.id} · {record.packageName} · {record.nodeCount}
                </option>
              ))}
            </select>
          </label>
          {windows.length > 1 ? (
            <label className="field">
              <span>{layoutText('window.title', language)}</span>
              <select value={activeWindowId} onChange={(event) => selectWindow(event.target.value)}>
                {windows.map((window) => (
                  <option key={window.id} value={window.id}>
                    {window.title} · {window.type}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
        </div>
        <button type="button" className="button" disabled={busy || serial.length === 0} onClick={capture}>
          {busy ? translate('layout.capturing', language) : translate('layout.captureAction', language)}
        </button>
      </section>

      {snapshot === null || activeWindow === null ? (
        <p className="content__muted">{translate('layout.none', language)}</p>
      ) : (
        <div className="layout">
          <section className="card layout__pane">
            <div className="pane-header">
              <h3 className="pane-header__title">{layoutText('pane.hierarchy', language)}</h3>
              <div className="pane-header__options">
                <span className="pane-header__note">{visibleRows.length}</span>
                <span className="pane-header__note">
                  {layoutText('metrics.summary', language, metrics.nodeCount, metrics.maxDepth, metrics.widestLevel)}
                </span>
              </div>
            </div>
            <div className="pane-header">
              <div className="pane-header__options">
                <button
                  type="button"
                  className={options.showIds ? 'toggle toggle--on' : 'toggle'}
                  aria-pressed={options.showIds}
                  onClick={() => toggleOption('showIds')}
                >
                  {layoutText('view.showIds', language)}
                </button>
                <button
                  type="button"
                  className={options.hideIndices ? 'toggle toggle--on' : 'toggle'}
                  aria-pressed={options.hideIndices}
                  onClick={() => toggleOption('hideIndices')}
                >
                  {layoutText('view.hideIndices', language)}
                </button>
                <button
                  type="button"
                  className={options.hideInvisible ? 'toggle toggle--on' : 'toggle'}
                  aria-pressed={options.hideInvisible}
                  onClick={() => toggleOption('hideInvisible')}
                >
                  {layoutText('view.hideInvisible', language)}
                </button>
              </div>
            </div>
            <div
              className="tree"
              ref={scrollRef}
              style={{ height: VIEWPORT_HEIGHT }}
              onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
              onMouseLeave={() => setHoveredNodeId(undefined)}
            >
              <div className="tree__spacer" style={{ height: visibleRows.length * ROW_HEIGHT }}>
                {windowRows.map((row, index) => (
                  <div
                    key={row.node.id}
                    className={
                      row.node.id === selectedNodeId
                        ? 'tree__row tree__row--active'
                        : row.node.id === hoveredNodeId
                          ? 'tree__row tree__row--hovered'
                          : hiddenSubtree.has(row.node.id)
                            ? 'tree__row tree__row--hidden'
                            : 'tree__row'
                    }
                    style={{ top: (start + index) * ROW_HEIGHT, paddingLeft: 4 + row.depth * INDENT }}
                    onMouseEnter={() => setHoveredNodeId(row.node.id)}
                  >
                    <span
                      className="tree__twisty"
                      role="presentation"
                      onClick={() => row.hasChildren && toggleCollapsed(row.node.id)}
                    >
                      {row.hasChildren ? (collapsed.has(row.node.id) ? '▸' : '▾') : '·'}
                    </span>
                    <span className="tree__label" role="presentation" onClick={() => selectAndReveal(row.node.id)}>
                      {hierarchyLabel(row, { hideIndex: options.hideIndices, showId: options.showIds })}
                    </span>
                    <span
                      className="tree__action"
                      role="presentation"
                      onClick={() =>
                        setHidden((current) =>
                          current.has(row.node.id) ? showLayer(current, row.node.id) : hideLayer(current, row.node.id),
                        )
                      }
                    >
                      {hidden.has(row.node.id) ? translate('layout.show', language) : translate('layout.hide', language)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </section>

          <section className="card layout__pane">
            <LayoutCanvas
              root={activeWindow.root}
              display={snapshot.display}
              {...(detail?.screenshotBase64 !== undefined ? { screenshotBase64: detail.screenshotBase64 } : {})}
              selectedNodeId={selectedNodeId}
              hiddenSubtree={hiddenSubtree}
              hiddenCount={hidden.size}
              hitOrder={hitOrder}
              language={language}
              onSelect={selectAndReveal}
              onHover={setHoveredNodeId}
              onToggleHitOrder={() =>
                setHitOrder((current) => (current === 'smallest-area' ? 'z-order' : 'smallest-area'))
              }
              onClearHidden={() => setHidden(clearHiddenLayers())}
            />
          </section>

          <section className="card layout__pane">
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
                      {section.rows.map((row) => (
                        <div className={'details__row details__row--' + row.tone} key={row.label}>
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

      {message !== null ? <p className="card__muted">{message}</p> : null}
    </>
  );
}
