import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import type { FlameGraphPayloadNode } from '@aps/profile-analysis';
import type {
  DeviceSummary,
  MethodProcessOption,
  MethodRankBy,
  MethodSessionRecord,
  MethodSnapshotOutcome,
  MethodTopRow,
} from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';
import { FlameGraph, flamePathTo, nodesByIndex } from './FlameGraph';
import { methodSessionLabel, methodSessionMetadata } from './method-session-presentation';

export interface MethodRecordingPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const RANKS: readonly MethodRankBy[] = ['SELF_MICROS', 'TOTAL_MICROS', 'CALL_COUNT', 'SYMBOL'];

function formatWeight(value: string): string {
  try {
    return BigInt(value).toLocaleString('en-US');
  } catch {
    return value;
  }
}

function formatMillis(micros: number): string {
  return (micros / 1000).toFixed(2) + ' ms';
}

export function MethodRecordingPanel({ language, devices }: MethodRecordingPanelProps): JSX.Element {
  const [sessions, setSessions] = useState<readonly MethodSessionRecord[]>([]);
  const [serial, setSerial] = useState('');
  const [processes, setProcesses] = useState<readonly MethodProcessOption[]>([]);
  const [selectedProcessPid, setSelectedProcessPid] = useState('');
  const [processesLoading, setProcessesLoading] = useState(false);
  const [processDiscoveryError, setProcessDiscoveryError] = useState<string | null>(null);
  const [processRefresh, setProcessRefresh] = useState(0);
  const [durationSeconds, setDurationSeconds] = useState(10);
  const [selectedId, setSelectedId] = useState('');
  const [threadKey, setThreadKey] = useState('');
  const [searchText, setSearchText] = useState('');
  const [direction, setDirection] = useState<'FORWARD' | 'INVERTED'>('FORWARD');
  const [rankBy, setRankBy] = useState<MethodRankBy>('SELF_MICROS');
  const [focusPath, setFocusPath] = useState<readonly string[] | null>(null);
  const [outcome, setOutcome] = useState<MethodSnapshotOutcome | null>(null);
  const [busy, setBusy] = useState(false);
  const [stopRequested, setStopRequested] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listMethodSessions().then((records) => {
      setSessions(records);
      setSelectedId((current) => (current.length > 0 ? current : records[0]?.id ?? ''));
    });
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (serial.length === 0 && devices.length > 0) setSerial(devices[0]?.serial ?? '');
  }, [devices, serial]);

  useEffect(() => {
    let active = true;
    setProcesses([]);
    setSelectedProcessPid('');
    setProcessDiscoveryError(null);
    if (serial.length === 0) {
      setProcessesLoading(false);
      return () => {
        active = false;
      };
    }

    setProcessesLoading(true);
    window.aps
      .listMethodRecordingProcesses(serial)
      .then((result) => {
        if (!active) return;
        if (result.ok) {
          setProcesses(result.processes);
          setSelectedProcessPid(result.processes[0]?.pid.toString() ?? '');
        } else {
          setProcessDiscoveryError(result.error ?? translate('method.processesFailed', language));
        }
      })
      .catch((reason: unknown) => {
        if (active) setProcessDiscoveryError(reason instanceof Error ? reason.message : String(reason));
      })
      .finally(() => {
        if (active) setProcessesLoading(false);
      });

    return () => {
      active = false;
    };
  }, [language, processRefresh, serial]);

  useEffect(() => {
    if (selectedId.length === 0) {
      setOutcome(null);
      return;
    }
    window.aps
      .methodSnapshot({
        id: selectedId,
        ...(threadKey.length > 0 ? { threadKey } : {}),
        searchText,
        direction,
        rankBy,
        transforms: focusPath === null ? [] : [{ kind: 'FOCUS_CALL_NODE', path: focusPath }],
      })
      .then(setOutcome)
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [direction, focusPath, rankBy, searchText, selectedId, threadKey]);

  const selectedProcess = useMemo(
    () => processes.find((process) => process.pid.toString() === selectedProcessPid),
    [processes, selectedProcessPid],
  );

  const capture = useCallback(() => {
    if (selectedProcess === undefined) return;
    setBusy(true);
    setStopRequested(false);
    setMessage(null);
    window.aps
      .captureMethodRecording({
        serial,
        packageName: selectedProcess.packageName,
        pid: selectedProcess.pid,
        durationSeconds,
      })
      .then((result) => {
        setMessage(
          result.ok
            ? translate('method.complete', language)
            : translate('method.failed', language) + ': ' + String(result.error ?? ''),
        );
        if (result.ok && result.id !== undefined) {
          setSelectedId(result.id);
          setThreadKey('');
          setFocusPath(null);
        }
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => {
        setStopRequested(false);
        setBusy(false);
      });
  }, [durationSeconds, language, refresh, selectedProcess, serial]);

  const stopCapture = useCallback(() => {
    setStopRequested(true);
    window.aps
      .stopMethodRecording()
      .then((result) => {
        if (!result.ok) {
          setStopRequested(false);
          setMessage(translate('method.stopFailed', language) + ': ' + String(result.error ?? ''));
        }
      })
      .catch((reason: unknown) => {
        setStopRequested(false);
        setMessage(translate('method.stopFailed', language) + ': ' + (reason instanceof Error ? reason.message : String(reason)));
      });
  }, [language]);

  const importTrace = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .importMethodRecording()
      .then((result) => {
        if (result.ok) {
          setMessage(translate('method.imported', language));
        } else if (result.cancelled !== true) {
          setMessage(translate('method.importFailed', language) + ': ' + String(result.error ?? ''));
        }
        if (result.ok && result.id !== undefined) {
          setSelectedId(result.id);
          setThreadKey('');
          setFocusPath(null);
        }
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, refresh]);

  const session = useMemo(() => sessions.find((record) => record.id === selectedId), [selectedId, sessions]);
  const graph = outcome?.ok === true ? outcome.graph : undefined;
  const methods: readonly MethodTopRow[] = outcome?.ok === true ? outcome.methods ?? [] : [];
  const byIndex = useMemo(() => (graph === undefined ? new Map() : nodesByIndex(graph)), [graph]);

  const focus = useCallback(
    (node: FlameGraphPayloadNode) => {
      setFocusPath(flamePathTo(node, byIndex));
    },
    [byIndex],
  );

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('method.capture', language)}</h3>
        <div className="form">
          <label className="field">
            <span>{translate('trace.device', language)}</span>
            <select value={serial} onChange={(input) => setSerial(input.target.value)}>
              {devices.length === 0 ? <option value="">{translate('trace.noDevice', language)}</option> : null}
              {devices.map((device) => (
                <option key={device.serial} value={device.serial}>
                  {device.serial}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('method.process', language)}</span>
            <select
              value={selectedProcessPid}
              disabled={busy || serial.length === 0 || processesLoading}
              onChange={(input) => setSelectedProcessPid(input.target.value)}
            >
              <option value="">
                {processesLoading
                  ? translate('method.processesLoading', language)
                  : translate('method.noProcesses', language)}
              </option>
              {processes.map((process) => (
                <option key={process.pid} value={process.pid.toString()}>
                  {process.name} · PID {String(process.pid)}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="button"
            disabled={busy || serial.length === 0 || processesLoading}
            onClick={() => setProcessRefresh((current) => current + 1)}
          >
            {translate('method.refreshProcesses', language)}
          </button>
          <label className="field">
            <span>{translate('method.duration', language)}</span>
            <input
              type="number"
              min={1}
              max={120}
              step={1}
              value={durationSeconds}
              onChange={(input) => setDurationSeconds(Number(input.target.value))}
            />
          </label>
          <button
            type="button"
            className="button"
            disabled={busy || serial.length === 0 || selectedProcess === undefined}
            onClick={capture}
          >
            {busy ? translate('method.capturing', language) : translate('method.captureAction', language)}
          </button>
          <button type="button" className="button" disabled={!busy || stopRequested} onClick={stopCapture}>
            {stopRequested ? translate('method.stopping', language) : translate('method.stopAction', language)}
          </button>
          <button type="button" className="button" disabled={busy} onClick={importTrace}>
            {translate('method.importAction', language)}
          </button>
        </div>
        {processDiscoveryError !== null ? (
          <p className="card__error">
            {translate('method.processesFailed', language)}: {processDiscoveryError}
          </p>
        ) : null}
        {message !== null ? <p className="card__muted">{message}</p> : null}
        <p className="card__muted">{translate('method.importNote', language)}</p>
        <p className="card__muted">{translate('method.disclaimer', language)}</p>
      </section>

      <section className="card">
        <h3 className="card__title">{translate('method.sessions', language)}</h3>
        {sessions.length === 0 ? (
          <p className="card__muted">{translate('method.none', language)}</p>
        ) : (
          <div className="form">
            <label className="field">
              <span>{translate('method.session', language)}</span>
              <select
                value={selectedId}
                onChange={(input) => {
                  setSelectedId(input.target.value);
                  setThreadKey('');
                  setFocusPath(null);
                }}
              >
                {sessions.map((record) => (
                  <option key={record.id} value={record.id}>
                    {new Date(record.capturedAtEpochMillis).toLocaleString()} · {methodSessionLabel(record, language)} ·{' '}
                    {String(record.eventCount)} events
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>{translate('cpu.thread', language)}</span>
              <select value={threadKey} onChange={(input) => setThreadKey(input.target.value)}>
                <option value="">{translate('cpu.thread.all', language)}</option>
                {(session?.threadKeys ?? []).map((key) => (
                  <option key={key} value={key}>
                    {key}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>{translate('cpu.search', language)}</span>
              <input
                type="text"
                value={searchText}
                placeholder="com.example"
                onChange={(input) => setSearchText(input.target.value)}
              />
            </label>
            <label className="field">
              <span>{translate('cpu.direction', language)}</span>
              <select
                value={direction}
                onChange={(input) => setDirection(input.target.value === 'INVERTED' ? 'INVERTED' : 'FORWARD')}
              >
                <option value="FORWARD">FORWARD</option>
                <option value="INVERTED">INVERTED</option>
              </select>
            </label>
            <button
              type="button"
              className="button"
              disabled={focusPath === null}
              onClick={() => setFocusPath(null)}
            >
              {translate('cpu.reset', language)}
            </button>
          </div>
        )}
        {session !== undefined ? (
          <p className="card__muted">{methodSessionMetadata(session, language)}</p>
        ) : null}
        {outcome?.ok === false ? <p className="card__error">{outcome.error}</p> : null}
      </section>

      {graph === undefined ? (
        <section className="card">
          <p className="card__muted">{translate('method.noneSelected', language)}</p>
        </section>
      ) : (
        <>
          <section className="card">
            <h3 className="card__title">{translate('cpu.flameGraph', language)}</h3>
            <p className="card__muted">
              {translate('method.totalTime', language)}: {formatWeight(graph.totalWeight)} ns ·{' '}
              {translate('cpu.stacks', language)}: {String(graph.sourceStackCount)} ·{' '}
              {translate('cpu.nodes', language)}: {String(graph.nodeCount)}
            </p>
            {graph.nodes.length === 0 ? (
              <p className="card__muted">
                {translate('cpu.empty', language)}: {graph.emptyReason ?? 'UNKNOWN'}
              </p>
            ) : (
              <FlameGraph graph={graph} language={language} onFocus={focus} />
            )}
          </section>

          <section className="card">
            <h3 className="card__title">{translate('method.topMethods', language)}</h3>
            <div className="form">
              <label className="field">
                <span>{translate('cpu.rankBy', language)}</span>
                <select value={rankBy} onChange={(input) => setRankBy(input.target.value as MethodRankBy)}>
                  {RANKS.map((value) => (
                    <option key={value} value={value}>
                      {translate(('method.rank.' + value) as 'method.rank.SYMBOL', language)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <table className="runs">
              <thead>
                <tr>
                  <th>{translate('memory.class', language)}</th>
                  <th>{translate('cpu.self', language)}</th>
                  <th>{translate('cpu.inclusive', language)}</th>
                  <th>{translate('method.calls', language)}</th>
                  <th>{translate('method.threads', language)}</th>
                </tr>
              </thead>
              <tbody>
                {methods.map((row) => (
                  <tr key={row.functionId}>
                    <td>
                      <code>{row.symbolName}</code>
                    </td>
                    <td>{formatMillis(row.selfMicros)}</td>
                    <td>{formatMillis(row.totalMicros)}</td>
                    <td>{String(row.callCount)}</td>
                    <td>{String(row.threadCount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          {session !== undefined && session.warnings.length > 0 ? (
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
