import { useCallback, useEffect, useState, type JSX } from 'react';
import type { DeviceSummary, TraceAnalyzerSnapshot } from '../../shared/ipc';
import type { UiLanguage } from '../../shared/i18n';

export interface TraceAnalyzerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const DEFAULT_DURATION_MS = 5000;
const DEFAULT_BUFFER_KB = 8192;

export function TraceAnalyzerPanel({ devices }: TraceAnalyzerPanelProps): JSX.Element {
  const [snapshot, setSnapshot] = useState<TraceAnalyzerSnapshot | null>(null);
  const [serial, setSerial] = useState('');
  const [durationMillis, setDurationMillis] = useState(DEFAULT_DURATION_MS);
  const [bufferSizeKb, setBufferSizeKb] = useState(DEFAULT_BUFFER_KB);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.getTraceAnalyzer().then(setSnapshot).catch((reason: unknown) => {
      setMessage(reason instanceof Error ? reason.message : String(reason));
    });
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (serial.length === 0 && devices.length > 0) setSerial(devices[0]?.serial ?? '');
  }, [devices, serial]);

  const capture = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .captureTrace({ serial, durationMillis, bufferSizeKb, dataSource: 'linux.ftrace' })
      .then((outcome) => {
        setMessage(outcome.ok ? 'Capture complete' : String(outcome.error ?? 'Capture failed'));
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [bufferSizeKb, durationMillis, refresh, serial]);

  const open = useCallback((id: string) => {
    window.aps.openTraceInAnalyzer(id).then((outcome) => {
      if (!outcome.ok) setMessage(String(outcome.error ?? 'Could not open the trace'));
    });
  }, []);

  if (snapshot === null) {
    return <p className="content__muted">Loading trace analyzer…</p>;
  }

  return (
    <>
      <p className="content__muted">
        {snapshot.ui.available
          ? 'Bundled Perfetto UI: ' + String(snapshot.ui.directory)
          : 'Perfetto UI assets are not bundled; capture still works.'}
      </p>

      <section className="card">
        <h3 className="card__title">Capture system trace</h3>
        <div className="form">
          <label className="field">
            <span>Device</span>
            <select value={serial} onChange={(event) => setSerial(event.target.value)}>
              {devices.length === 0 ? <option value="">No device</option> : null}
              {devices.map((device) => (
                <option key={device.serial} value={device.serial}>
                  {device.serial}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Duration (ms)</span>
            <input
              type="number"
              min={100}
              value={durationMillis}
              onChange={(event) => setDurationMillis(Number(event.target.value))}
            />
          </label>
          <label className="field">
            <span>Buffer (KB)</span>
            <input
              type="number"
              min={1024}
              value={bufferSizeKb}
              onChange={(event) => setBufferSizeKb(Number(event.target.value))}
            />
          </label>
        </div>
        <button type="button" className="button" disabled={busy || serial.length === 0} onClick={capture}>
          {busy ? 'Capturing…' : 'Capture'}
        </button>
      </section>

      <section className="card">
        <h3 className="card__title">Captured traces</h3>
        {snapshot.traces.length === 0 ? (
          <p className="card__muted">No traces captured yet.</p>
        ) : (
          <ul className="list">
            {snapshot.traces.map((record) => (
              <li key={record.id}>
                <code>{record.id}</code> · {record.durationMillis} ms
                <button type="button" className="button button--inline" onClick={() => open(record.id)}>
                  Open
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {message !== null ? <p className="card__muted">{message}</p> : null}
    </>
  );
}
