import { useCallback, useEffect, useState, type JSX } from 'react';
import type { MemorySession } from '@aps/memory-profiler';
import type { DeviceSummary, MemorySessionSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface MemoryProfilerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

function formatBytes(value: number): string {
  if (value < 1024) return String(value) + ' B';
  if (value < 1024 * 1024) return (value / 1024).toFixed(1) + ' KB';
  return (value / (1024 * 1024)).toFixed(2) + ' MB';
}

function formatTimestamp(value: number): string {
  return new Date(value).toLocaleString();
}

export function MemoryProfilerPanel({ language, devices }: MemoryProfilerPanelProps): JSX.Element {
  const [sessions, setSessions] = useState<readonly MemorySessionSummary[]>([]);
  const [serial, setSerial] = useState('');
  const [packageName, setPackageName] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [session, setSession] = useState<MemorySession | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listMemorySessions().then((records) => {
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
      .loadMemorySession(selectedId)
      .then((loaded) => setSession(loaded ?? null))
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [selectedId]);

  const capture = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .captureMemory({ serial, packageName })
      .then((outcome) => {
        setMessage(
          outcome.ok
            ? translate('memory.complete', language)
            : translate('memory.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        if (outcome.ok && outcome.id !== undefined) setSelectedId(outcome.id);
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, packageName, refresh, serial]);

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('memory.capture', language)}</h3>
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
            <input
              type="text"
              value={packageName}
              placeholder="com.example.app"
              onChange={(event) => setPackageName(event.target.value)}
            />
          </label>
          <label className="field">
            <span>{translate('memory.sessions', language)}</span>
            <select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
              {sessions.length === 0 ? <option value="">{translate('memory.none', language)}</option> : null}
              {sessions.map((record) => (
                <option key={record.id} value={record.id}>
                  {formatTimestamp(record.capturedAtEpochMillis)} · {formatBytes(record.shallowBytes)}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="button"
            disabled={busy || serial.length === 0 || packageName.trim().length === 0}
            onClick={capture}
          >
            {busy ? translate('memory.capturing', language) : translate('memory.captureAction', language)}
          </button>
        </div>
        {message !== null ? <p className="card__muted">{message}</p> : null}
        <p className="card__muted">{translate('memory.disclaimer', language)}</p>
      </section>

      {session === null ? (
        <section className="card">
          <p className="card__muted">{translate('memory.noneSelected', language)}</p>
        </section>
      ) : (
        <>
          <section className="card">
            <h3 className="card__title">{translate('memory.summary', language)}</h3>
            <ul className="list">
              <li>
                {translate('memory.instances', language)}: {String(session.summary.instanceCount)}
              </li>
              <li>
                {translate('memory.classes', language)}: {String(session.summary.classCount)}
              </li>
              <li>
                {translate('memory.arrays', language)}: {String(session.summary.arrayCount)}
              </li>
              <li>
                {translate('memory.shallow', language)}: {formatBytes(session.summary.shallowBytes)} ·{' '}
                {translate('memory.estimated', language)}
              </li>
              <li>
                {translate('memory.identifierSize', language)}: {String(session.summary.identifierSize)} B · HPROF{' '}
                {session.summary.version}
              </li>
              {session.packageName !== undefined ? <li>{session.packageName}</li> : null}
            </ul>
          </section>

          <section className="card">
            <h3 className="card__title">{translate('memory.suspects', language)}</h3>
            <p className="card__muted">{translate('memory.suspectsNote', language)}</p>
            {session.suspects.length === 0 ? (
              <p className="card__muted">{translate('memory.noSuspects', language)}</p>
            ) : (
              <table className="runs">
                <thead>
                  <tr>
                    <th>{translate('memory.class', language)}</th>
                    <th>{translate('memory.objectId', language)}</th>
                    <th>{translate('memory.retained', language)}</th>
                    <th>{translate('memory.shallow', language)}</th>
                    <th>{translate('memory.chain', language)}</th>
                  </tr>
                </thead>
                <tbody>
                  {session.suspects.map((suspect) => (
                    <tr key={suspect.objectId}>
                      <td>
                        <code>{suspect.className}</code>
                      </td>
                      <td>
                        <code>{suspect.objectId}</code>
                      </td>
                      <td>{formatBytes(suspect.retainedBytes)}</td>
                      <td>{formatBytes(suspect.shallowBytes)}</td>
                      <td>
                        <code>{suspect.referenceChain.join(' → ')}</code>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section className="card">
            <h3 className="card__title">{translate('memory.histogram', language)}</h3>
            <table className="runs">
              <thead>
                <tr>
                  <th>{translate('memory.class', language)}</th>
                  <th>{translate('memory.instances', language)}</th>
                  <th>{translate('memory.shallow', language)}</th>
                </tr>
              </thead>
              <tbody>
                {session.histogram.map((entry) => (
                  <tr key={entry.className}>
                    <td>
                      <code>{entry.className}</code>
                    </td>
                    <td>{String(entry.instanceCount)}</td>
                    <td>{formatBytes(entry.shallowBytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {session.warnings.length > 0 ? (
            <section className="card">
              <h3 className="card__title">{translate('memory.warnings', language)}</h3>
              <ul className="list">
                {session.warnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            </section>
          ) : null}
        </>
      )}
    </>
  );
}
