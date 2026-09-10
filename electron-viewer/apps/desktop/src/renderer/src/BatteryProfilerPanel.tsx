import { useCallback, useEffect, useState, type JSX } from 'react';
import type { BatteryCaptureMode, BatteryExperimentResult } from '@aps/battery-profiler';
import type { BatterySessionSummary, DeviceSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface BatteryProfilerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const MODES: readonly BatteryCaptureMode[] = ['INTERACTIVE', 'TIMED', 'REPEATED', 'ONLINE'];

function formatMs(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(0) + ' ms';
}

function formatCount(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(0);
}

function formatKilobytes(value: number | undefined): string {
  return value === undefined ? '-' : (value / 1024).toFixed(1) + ' KB';
}

function formatMilliampHours(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(2) + ' mAh';
}

export function BatteryProfilerPanel({ language, devices }: BatteryProfilerPanelProps): JSX.Element {
  const [sessions, setSessions] = useState<readonly BatterySessionSummary[]>([]);
  const [serial, setSerial] = useState('');
  const [packageName, setPackageName] = useState('');
  const [uid, setUid] = useState(0);
  const [mode, setMode] = useState<BatteryCaptureMode>('INTERACTIVE');
  const [durationSeconds, setDurationSeconds] = useState(60);
  const [pollingIntervalSeconds, setPollingIntervalSeconds] = useState(10);
  const [measuredRuns, setMeasuredRuns] = useState(1);
  const [cooldownSeconds, setCooldownSeconds] = useState(30);
  const [selectedId, setSelectedId] = useState('');
  const [result, setResult] = useState<BatteryExperimentResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listBatterySessions().then((records) => {
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
      setResult(null);
      return;
    }
    window.aps
      .loadBatterySession(selectedId)
      .then((loaded) => setResult(loaded ?? null))
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [selectedId]);

  const capture = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .captureBattery({
        serial,
        packageName,
        uid,
        mode,
        durationSeconds,
        pollingIntervalSeconds,
        measuredRuns,
        cooldownSeconds,
      })
      .then((outcome) => {
        setMessage(
          outcome.ok
            ? translate('battery.complete', language)
            : translate('battery.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        if (outcome.ok && outcome.id !== undefined) setSelectedId(outcome.id);
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [cooldownSeconds, durationSeconds, language, measuredRuns, mode, packageName, pollingIntervalSeconds, refresh, serial, uid]);

  const analysis = result?.analysis;

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('battery.capture', language)}</h3>
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
            <span>{translate('battery.uid', language)}</span>
            <input type="number" min={0} value={uid} onChange={(event) => setUid(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('battery.mode', language)}</span>
            <select value={mode} onChange={(event) => setMode(event.target.value as BatteryCaptureMode)}>
              {MODES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('battery.duration', language)}</span>
            <input type="number" min={5} value={durationSeconds} onChange={(event) => setDurationSeconds(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('battery.polling', language)}</span>
            <input type="number" min={5} value={pollingIntervalSeconds} onChange={(event) => setPollingIntervalSeconds(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('battery.runs', language)}</span>
            <input type="number" min={1} value={measuredRuns} onChange={(event) => setMeasuredRuns(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('battery.cooldown', language)}</span>
            <input type="number" min={0} value={cooldownSeconds} onChange={(event) => setCooldownSeconds(Number(event.target.value))} />
          </label>
          <label className="field">
            <span>{translate('battery.sessions', language)}</span>
            <select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
              <option value="">-</option>
              {sessions.map((record) => (
                <option key={record.id} value={record.id}>
                  {record.id} · {record.packageName} · {record.runCount}
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
        <p className="card__muted">{translate('battery.readonly', language)}</p>
      </section>

      {result === null || analysis === undefined ? (
        <p className="content__muted">{translate('battery.none', language)}</p>
      ) : (
        <>
          <div className="metrics">
            <div className="metric">
              <span className="metric__label">{translate('battery.wakelock', language)}</span>
              <span className="metric__value">{formatMs(analysis.wakelockDurationMs.median)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('battery.alarm', language)}</span>
              <span className="metric__value">{formatCount(analysis.wakeupAlarmCount.median)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('battery.job', language)}</span>
              <span className="metric__value">{formatMs(analysis.jobDurationMs.median)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('battery.sensor', language)}</span>
              <span className="metric__value">{formatMs(analysis.sensorDurationMs.median)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('battery.network', language)}</span>
              <span className="metric__value">{formatKilobytes(analysis.networkBytes.median)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('battery.energy', language)}</span>
              <span className="metric__value">{formatMilliampHours(analysis.energyMah.median)}</span>
            </div>
          </div>

          <p className="card__muted">{translate('battery.modeled', language)}</p>

          <section className="card">
            <h3 className="card__title">{translate('battery.warnings', language)}</h3>
            {analysis.warnings.length === 0 ? (
              <p className="card__muted">-</p>
            ) : (
              <ul className="list">
                {analysis.warnings.slice(0, 8).map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            )}
          </section>

          <section className="card">
            <h3 className="card__title">{translate('battery.runs', language)}</h3>
            <table className="runs">
              <thead>
                <tr>
                  <th>#</th>
                  <th>{translate('battery.wakelock', language)}</th>
                  <th>{translate('battery.alarm', language)}</th>
                  <th>{translate('battery.network', language)}</th>
                </tr>
              </thead>
              <tbody>
                {analysis.runs.map((run) => (
                  <tr key={run.runId}>
                    <td>{run.iteration}</td>
                    <td>
                      {run.wakelocks
                        .slice(0, 2)
                        .map((timer) => timer.name + ' ' + formatMs(timer.durationMs))
                        .join(', ') || '-'}
                    </td>
                    <td>{run.alarms.reduce((total, timer) => total + timer.count, 0)}</td>
                    <td>{formatKilobytes(run.network.wifiRxBytes + run.network.wifiTxBytes + run.network.mobileRxBytes + run.network.mobileTxBytes)}</td>
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
