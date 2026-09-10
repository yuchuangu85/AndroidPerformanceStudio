import { useCallback, useEffect, useState, type JSX } from 'react';
import type { DeviceSummary, TraceAnalyzerSnapshot } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface TraceAnalyzerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const DEFAULT_DURATION_MS = 5000;
const DEFAULT_BUFFER_KB = 8192;

export function TraceAnalyzerPanel({ language, devices }: TraceAnalyzerPanelProps): JSX.Element {
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
        setMessage(
          outcome.ok
            ? translate('trace.complete', language)
            : translate('trace.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [bufferSizeKb, durationMillis, language, refresh, serial]);

  const open = useCallback(
    (id: string) => {
      window.aps.openTraceInAnalyzer(id).then((outcome) => {
        if (!outcome.ok) setMessage(String(outcome.error ?? translate('trace.failed', language)));
      });
    },
    [language],
  );

  if (snapshot === null) {
    return <p className="content__muted">{translate('trace.loading', language)}</p>;
  }

  return (
    <>
      <p className="content__muted">
        {snapshot.ui.available
          ? translate('trace.uiBundled', language) + ': ' + String(snapshot.ui.directory)
          : translate('trace.uiMissing', language)}
      </p>
      {!snapshot.ui.available ? (
        <button type="button" className="button" onClick={() => void window.aps.openPublicPerfettoUi()}>
          {translate('trace.openPublicUi', language)}
        </button>
      ) : null}

      <section className="card">
        <h3 className="card__title">{translate('trace.capture', language)}</h3>
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
            <span>{translate('trace.duration', language)}</span>
            <input
              type="number"
              min={100}
              value={durationMillis}
              onChange={(event) => setDurationMillis(Number(event.target.value))}
            />
          </label>
          <label className="field">
            <span>{translate('trace.buffer', language)}</span>
            <input
              type="number"
              min={1024}
              value={bufferSizeKb}
              onChange={(event) => setBufferSizeKb(Number(event.target.value))}
            />
          </label>
        </div>
        <button type="button" className="button" disabled={busy || serial.length === 0} onClick={capture}>
          {busy ? translate('trace.capturing', language) : translate('trace.captureAction', language)}
        </button>
      </section>

      <section className="card">
        <h3 className="card__title">{translate('trace.captured', language)}</h3>
        {snapshot.traces.length === 0 ? (
          <p className="card__muted">{translate('trace.none', language)}</p>
        ) : (
          <ul className="list">
            {snapshot.traces.map((record) => (
              <li key={record.id}>
                <code>{record.id}</code> · {record.durationMillis} ms
                <button type="button" className="button button--inline" onClick={() => open(record.id)}>
                  {translate('trace.open', language)}
                </button>
                <button
                  type="button"
                  className="button button--inline"
                  onClick={() => void window.aps.revealTrace(record.id)}
                >
                  {translate('trace.reveal', language)}
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
