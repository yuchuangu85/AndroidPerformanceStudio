import { useCallback, useEffect, useMemo, useRef, useState, type JSX } from 'react';
import {
  clearHiddenLayers,
  computeHiddenSubtree,
  cycleHitCandidate,
  flattenVisibleTree,
  hitTestCandidates,
  hideLayer,
  showLayer,
  type HitTestOrder,
  type LayoutSnapshot,
  type TreeRow,
  type UiNode,
} from '@aps/layout-inspector';
import type { DeviceSummary, LayoutCaptureDetail, LayoutCaptureSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface LayoutInspectorPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const ROW_HEIGHT = 22;
const VIEWPORT_HEIGHT = 440;
const OVERSCAN = 12;
const DEFAULT_EXPANDED_DEPTH = 2;

function defaultExpanded(root: UiNode): Set<string> {
  const expanded = new Set<string>();
  const visit = (node: UiNode, depth: number): void => {
    if (depth >= DEFAULT_EXPANDED_DEPTH) return;
    expanded.add(node.id);
    for (const child of node.children) visit(child, depth + 1);
  };
  visit(root, 0);
  return expanded;
}

function labelOf(node: UiNode): string {
  const shortClass = node.className.split('.').pop() ?? node.className;
  const name = node.type === 'view' ? node.resourceName : undefined;
  return name !== undefined && name.length > 0 ? shortClass + ' · ' + name : shortClass;
}

function percent(value: number, total: number): string {
  if (total <= 0) return '0%';
  return (Math.min(Math.max(value, 0), total) / total) * 100 + '%';
}

export function LayoutInspectorPanel({ language, devices }: LayoutInspectorPanelProps): JSX.Element {
  const [captures, setCaptures] = useState<readonly LayoutCaptureSummary[]>([]);
  const [serial, setSerial] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<LayoutCaptureDetail | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set());
  const [hitOrder, setHitOrder] = useState<HitTestOrder>('z-order');
  const [scrollTop, setScrollTop] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const lastClick = useRef<string>('');

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
        // Hidden layers and expansion are per-snapshot; a new capture starts clean.
        setExpanded(defaultExpanded(loaded.snapshot.root));
        setHidden(clearHiddenLayers());
        setSelectedNodeId(loaded.snapshot.root.id);
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
  const hiddenSubtree = useMemo(
    () => (snapshot === null ? new Set<string>() : computeHiddenSubtree(hidden, snapshot.root)),
    [hidden, snapshot],
  );
  const rows: TreeRow[] = useMemo(
    () => (snapshot === null ? [] : flattenVisibleTree(snapshot.root, { expanded, hiddenSubtree })),
    [expanded, hiddenSubtree, snapshot],
  );

  const start = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
  const end = Math.min(rows.length, Math.ceil((scrollTop + VIEWPORT_HEIGHT) / ROW_HEIGHT) + OVERSCAN);
  const windowRows = rows.slice(start, end);

  const selected = useMemo(
    () => rows.find((row) => row.node.id === selectedNodeId)?.node ?? snapshot?.root ?? null,
    [rows, selectedNodeId, snapshot],
  );

  const toggleExpanded = useCallback((id: string) => {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const onCanvasClick = useCallback(
    (event: React.MouseEvent<HTMLImageElement>) => {
      if (snapshot === null) return;
      const rect = event.currentTarget.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      const x = ((event.clientX - rect.left) / rect.width) * snapshot.display.widthPx;
      const y = ((event.clientY - rect.top) / rect.height) * snapshot.display.heightPx;
      const candidates = hitTestCandidates(snapshot.root, { x, y, hiddenSubtree, order: hitOrder });
      if (candidates.length === 0) return;
      const key = Math.round(x) + ':' + Math.round(y);
      const next = key === lastClick.current ? cycleHitCandidate(candidates, selectedNodeId) : candidates[0];
      lastClick.current = key;
      if (next !== undefined) setSelectedNodeId(next.id);
    },
    [hiddenSubtree, hitOrder, selectedNodeId, snapshot],
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
          <label className="field">
            <span>{translate('layout.hitOrder', language)}</span>
            <select value={hitOrder} onChange={(event) => setHitOrder(event.target.value as HitTestOrder)}>
              <option value="z-order">{translate('layout.orderZ', language)}</option>
              <option value="smallest-area">{translate('layout.orderSmallest', language)}</option>
            </select>
          </label>
        </div>
        <button type="button" className="button" disabled={busy || serial.length === 0} onClick={capture}>
          {busy ? translate('layout.capturing', language) : translate('layout.captureAction', language)}
        </button>
        {hidden.size > 0 ? (
          <span className="hidden-summary">
            {translate('layout.hidden', language)} {hidden.size} ·{' '}
            <button
              type="button"
              className="button button--inline"
              onClick={() => setHidden(clearHiddenLayers())}
            >
              {translate('layout.clearHidden', language)}
            </button>
          </span>
        ) : null}
      </section>

      {snapshot === null ? (
        <p className="content__muted">{translate('layout.none', language)}</p>
      ) : (
        <div className="layout">
          <section className="card layout__pane">
            <h3 className="card__title">{translate('layout.hierarchy', language)}</h3>
            <div
              className="tree"
              style={{ height: VIEWPORT_HEIGHT }}
              onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
            >
              <div className="tree__spacer" style={{ height: rows.length * ROW_HEIGHT }}>
                {windowRows.map((row, index) => (
                  <div
                    key={row.node.id}
                    className={
                      row.node.id === selectedNodeId
                        ? 'tree__row tree__row--active'
                        : row.hidden || row.hiddenByAncestor
                          ? 'tree__row tree__row--hidden'
                          : 'tree__row'
                    }
                    style={{ top: (start + index) * ROW_HEIGHT, paddingLeft: 4 + row.depth * 12 }}
                  >
                    <span
                      className="tree__twisty"
                      role="presentation"
                      onClick={() => row.hasChildren && toggleExpanded(row.node.id)}
                    >
                      {row.hasChildren ? (row.expanded ? '▾' : '▸') : '·'}
                    </span>
                    <span className="tree__label" role="presentation" onClick={() => setSelectedNodeId(row.node.id)}>
                      {labelOf(row.node)}
                    </span>
                    <span
                      className="tree__action"
                      role="presentation"
                      onClick={() => setHidden((current) => (current.has(row.node.id) ? showLayer(current, row.node.id) : hideLayer(current, row.node.id)))}
                    >
                      {hidden.has(row.node.id) ? translate('layout.show', language) : translate('layout.hide', language)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </section>

          <section className="card layout__pane">
            <h3 className="card__title">{translate('layout.preview', language)}</h3>
            <div className="preview">
              {detail?.screenshotBase64 !== undefined ? (
                <>
                  <img
                    className="preview__image"
                    alt="device screenshot"
                    src={'data:image/png;base64,' + detail.screenshotBase64}
                    onClick={onCanvasClick}
                  />
                  {selected !== null && snapshot.display.widthPx > 0 ? (
                    <div
                      className="preview__overlay"
                      style={{
                        left: percent(selected.bounds.left, snapshot.display.widthPx),
                        top: percent(selected.bounds.top, snapshot.display.heightPx),
                        width: percent(selected.bounds.right - selected.bounds.left, snapshot.display.widthPx),
                        height: percent(selected.bounds.bottom - selected.bounds.top, snapshot.display.heightPx),
                      }}
                    />
                  ) : null}
                </>
              ) : (
                <p className="card__muted">{translate('status.unavailable', language)}</p>
              )}
            </div>
          </section>

          <section className="card layout__pane">
            <h3 className="card__title">{translate('layout.properties', language)}</h3>
            {selected === null ? (
              <p className="card__muted">{translate('layout.noSelection', language)}</p>
            ) : (
              <dl className="facts">
                <div className="facts__row">
                  <dt>id</dt>
                  <dd>{selected.id}</dd>
                </div>
                <div className="facts__row">
                  <dt>class</dt>
                  <dd>{selected.className}</dd>
                </div>
                {selected.text !== undefined ? (
                  <div className="facts__row">
                    <dt>text</dt>
                    <dd>{selected.text}</dd>
                  </div>
                ) : null}
                <div className="facts__row">
                  <dt>bounds</dt>
                  <dd>
                    [{selected.bounds.left},{selected.bounds.top}][{selected.bounds.right},{selected.bounds.bottom}]
                  </dd>
                </div>
                {selected.type === 'view'
                  ? Object.entries(selected.attributes.rawProperties)
                      .filter(([key]) => ['content-desc', 'clickable', 'enabled', 'focused', 'selected'].includes(key))
                      .map(([key, value]) => (
                        <div className="facts__row" key={key}>
                          <dt>{key}</dt>
                          <dd>{value}</dd>
                        </div>
                      ))
                  : null}
              </dl>
            )}
          </section>
        </div>
      )}

      {message !== null ? <p className="card__muted">{message}</p> : null}
    </>
  );
}
