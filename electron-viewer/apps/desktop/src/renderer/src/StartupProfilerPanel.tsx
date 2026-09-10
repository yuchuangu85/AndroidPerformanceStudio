import { useCallback, useEffect, useState, type JSX } from 'react';
import type { StartupSession, StartupType } from '@aps/startup-profiler';
import type { DeviceSummary, StartupSessionSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface StartupProfilerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const STARTUP_TYPES: readonly StartupType[] = ['COLD', 'WARM', 'HOT'];

function formatMs(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(0) + ' ms';
}

function formatCount(value: number): string {
  return String(value);
}

export function StartupProfilerPanel({ language, devices }: StartupProfilerPanelProps): JSX.Element {
  const [sessions, setSessions] = useState<readonly StartupSessionSummary[]>([]);
  const [serial, setSerial] = useState('');
  const [packageName, setPackageName] = useState('');
  const [componentName, setComponentName] = useState('');
  const [requestedType, setRequestedType] = useState<StartupType>('COLD');
  const [warmupRuns, setWarmupRuns] = useState(0);
  const [measuredRuns, setMeasuredRuns] = useState(5);
  const [timeoutSeconds, setTimeoutSeconds] = useState(30);
  const [selectedId, setSelectedId] = useState('');
  const [session, setSession] = useState<StartupSession | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listStartupSessions().then((records) => {
      setSessions(records);
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
      setSession(null);
      return;
    }
    window.aps
      .loadStartupSession(selectedId)
      .then((loaded) => setSession(loaded ?? null))
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [selectedId]);

  const capture = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .captureStartup({
        serial,
        packageName,
        ...(componentName.trim().length > 0 ? { componentName } : {}),
        requestedType,
        warmupRuns,
        measuredRuns,
        timeoutSeconds,
      })
      .then((outcome) => {
        setMessage(
          outcome.ok
            ? translate('startup.complete', language)
            : translate('startup.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        if (outcome.ok && outcome.id !== undefined) setSelectedId(outcome.id);
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [componentName, language, measuredRuns, packageName, refresh, requestedType, serial, timeoutSeconds, warmupRuns]);

  const statistics = session?.statistics;
  const runs = session?.runs ?? [];

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('startup.capture', language)}</h3>
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
            <span>{translate('frame.package', language)}</span>
            <input type="text" value={packageName} placeholder="com.example.app" onChange={(event) => setPackageName(event.target.value)} />
          </label>
          <label className="field">
            <span>{translate('startup.component', language)}</span>
            <input
              type="text"
              value={componentName}
              placeholder="com.example.app/.MainActivity"
              onChange={(event) => setComponentName(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{translate('startup.mode', language)}</span>
            <select value={requestedType} onChange={(event) => setRequestedType(event.target.value as StartupType)}>
              {STARTUP_TYPES.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('startup.warmup', language)}</span>
            <input type="number" min={0} value={warmupRuns} onChange={(event) => setWarmupRuns(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('startup.measured', language)}</span>
            <input type="number" min={1} value={measuredRuns} onChange={(event) => setMeasuredRuns(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('startup.timeout', language)}</span>
            <input type="number" min={5} value={timeoutSeconds} onChange={(event) => setTimeoutSeconds(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('startup.sessions', language)}</span>
            <select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
              <option value="">-</option>
              {sessions.map((record) => (
                <option key={record.id} value={record.id}>
                  {record.id} · {record.packageName} · {record.measuredRuns}
                </option>
              ))}
            </select>
          </label>
        </div>
        <button
          type="button"
          className="button"
          disabled={busy || serial.length === 0 || packageName.trim().length === 0}
          onClick={capture}
        >
          {busy ? translate('frame.capturing', language) : translate('frame.captureAction', language)}
        </button>
      </section>

      {session === null || statistics === undefined ? (
        <p className="content__muted">{translate('startup.none', language)}</p>
      ) : (
        <>
          <div className="metrics">
            <div className="metric">
              <span className="metric__label">{translate('startup.median', language)}</span>
              <span className="metric__value">{formatMs(statistics.totalTimeMs.medianMs)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('startup.p90', language)}</span>
              <span className="metric__value">{formatMs(statistics.totalTimeMs.p90Ms)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('startup.p95', language)}</span>
              <span className="metric__value">{formatMs(statistics.totalTimeMs.p95Ms)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('startup.ttid', language)}</span>
              <span className="metric__value">{formatMs(statistics.ttidMs.medianMs)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('startup.ttfd', language)}</span>
              <span className="metric__value">{formatCount(statistics.ttfdMs.count)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('startup.mismatch', language)}</span>
              <span className="metric__value">{formatCount(statistics.modeMismatchRuns)}</span>
            </div>
          </div>

          {statistics.totalTimeMs.p90LowResolution ? (
            <p className="card__muted">{translate('startup.lowResolution', language)}</p>
          ) : null}

          <section className="card">
            <h3 className="card__title">{translate('startup.runs', language)}</h3>
            <table className="runs">
              <thead>
                <tr>
                  <th>#</th>
                  <th>{translate('startup.observed', language)}</th>
                  <th>{translate('startup.totalTime', language)}</th>
                  <th>{translate('startup.ttid', language)}</th>
                  <th>{translate('startup.ttfd', language)}</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.id} className={run.measured ? undefined : 'runs__warmup'}>
                    <td>
                      {run.iteration}
                      {run.measured ? '' : ' (warmup)'}
                    </td>
                    <td>{run.observedType}</td>
                    <td>{formatMs(run.platform.totalTimeMs)}</td>
                    <td>{formatMs(run.platform.displayedTimeMs)}</td>
                    <td>{formatMs(run.platform.fullyDrawnTimeMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </>
      )}

      {message !== null ? <p className="card__muted">{message}</p> : null}
    </>
  );
}
