import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import { walkNode, type LayoutSnapshot, type UiNode } from '@aps/layout-inspector';
import type { DeviceSummary, LayoutCaptureDetail, LayoutCaptureSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface LayoutInspectorPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const MAX_TREE_ROWS = 2000;

interface TreeRow {
  readonly node: UiNode;
  readonly depth: number;
}

function flattenTree(root: UiNode): TreeRow[] {
  const rows: TreeRow[] = [];
  walkNode(root, (node, depth) => {
    if (rows.length < MAX_TREE_ROWS) rows.push({ node, depth });
  });
  return rows;
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
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

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
        setSelectedNodeId(loaded?.snapshot.root.id ?? '');
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
  const rows = useMemo(() => (snapshot === null ? [] : flattenTree(snapshot.root)), [snapshot]);
  const selected = useMemo(
    () => rows.find((row) => row.node.id === selectedNodeId)?.node ?? snapshot?.root ?? null,
    [rows, selectedNodeId, snapshot],
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
        </div>
        <button type="button" className="button" disabled={busy || serial.length === 0} onClick={capture}>
          {busy ? translate('layout.capturing', language) : translate('layout.captureAction', language)}
        </button>
      </section>

      {snapshot === null ? (
        <p className="content__muted">{translate('layout.none', language)}</p>
      ) : (
        <div className="layout">
          <section className="card layout__pane">
            <h3 className="card__title">{translate('layout.hierarchy', language)}</h3>
            <div className="tree">
              {rows.map((row) => (
                <button
                  key={row.node.id}
                  type="button"
                  className={row.node.id === selectedNodeId ? 'tree__row tree__row--active' : 'tree__row'}
                  style={{ paddingLeft: 6 + row.depth * 12 }}
                  onClick={() => setSelectedNodeId(row.node.id)}
                  title={row.node.className}
                >
                  {labelOf(row.node)}
                </button>
              ))}
            </div>
          </section>

          <section className="card layout__pane">
            <h3 className="card__title">{translate('layout.preview', language)}</h3>
            <div className="preview">
              {detail?.screenshotBase64 !== undefined ? (
                <>
                  <img className="preview__image" alt="device screenshot" src={'data:image/png;base64,' + detail.screenshotBase64} />
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
