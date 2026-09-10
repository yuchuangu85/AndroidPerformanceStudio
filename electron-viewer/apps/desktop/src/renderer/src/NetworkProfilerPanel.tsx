import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import {
  callDurationNs,
  phaseDurationNs,
  type HttpCall,
  type NetworkCaptureResult,
  type NetworkConfidence,
  type NetworkPhase,
} from '@aps/network-profiler';
import type { DeviceSummary, NetworkSessionSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface NetworkProfilerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const MEASURED: ReadonlySet<NetworkConfidence> = new Set<NetworkConfidence>(['EXACT', 'DERIVED']);

function formatMs(value: number | undefined): string {
  return value === undefined ? '-' : (value / 1_000_000).toFixed(2) + ' ms';
}

function segmentsOf(call: HttpCall): Array<{ phase: NetworkPhase; durationNs: number }> {
  const exchange = call.exchanges[0];
  if (exchange === undefined) return [];
  return exchange.phases
    .filter((phase) => phase.kind !== 'TOTAL')
    .map((phase) => ({ phase, durationNs: phaseDurationNs(phase) ?? 0 }))
    .filter((segment) => segment.durationNs > 0);
}

export function NetworkProfilerPanel({ language }: NetworkProfilerPanelProps): JSX.Element {
  const [sessions, setSessions] = useState<readonly NetworkSessionSummary[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [capture, setCapture] = useState<NetworkCaptureResult | null>(null);
  const [selectedCallId, setSelectedCallId] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listNetworkSessions().then((records) => {
      setSessions(records);
      if (records.length > 0 && selectedId.length === 0) setSelectedId(records[0]?.id ?? '');
    });
  }, [selectedId]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    if (selectedId.length === 0) {
      setCapture(null);
      return;
    }
    window.aps
      .loadNetworkSession(selectedId)
      .then((loaded) => {
        setCapture(loaded ?? null);
        setSelectedCallId(loaded?.calls[0]?.callId ?? '');
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [selectedId]);

  const importHar = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .importNetworkHar()
      .then((outcome) => {
        if (outcome.cancelled === true) return;
        setMessage(
          outcome.ok
            ? translate('network.complete', language)
            : translate('network.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        if (outcome.ok && outcome.id !== undefined) setSelectedId(outcome.id);
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, refresh]);

  const selectedCall = useMemo(
    () => capture?.calls.find((call) => call.callId === selectedCallId) ?? null,
    [capture, selectedCallId],
  );
  const segments = useMemo(() => (selectedCall === null ? [] : segmentsOf(selectedCall)), [selectedCall]);
  const totalNs = useMemo(() => {
    if (selectedCall === null) return 0;
    const measured = callDurationNs(selectedCall);
    if (measured !== undefined && measured > 0) return measured;
    return segments.reduce((total, segment) => total + segment.durationNs, 0);
  }, [segments, selectedCall]);

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('network.import', language)}</h3>
        <div className="form">
          <label className="field">
            <span>{translate('network.sessions', language)}</span>
            <select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
              <option value="">-</option>
              {sessions.map((record) => (
                <option key={record.id} value={record.id}>
                  {record.callCount} calls · {record.status}
                  {record.producer !== undefined ? ' · ' + record.producer : ''}
                </option>
              ))}
            </select>
          </label>
        </div>
        <button type="button" className="button" disabled={busy} onClick={importHar}>
          {busy ? translate('frame.capturing', language) : translate('network.import', language)}
        </button>
        <p className="card__muted">{translate('network.redacted', language)}</p>
      </section>

      {capture === null ? (
        <p className="content__muted">{translate('network.none', language)}</p>
      ) : (
        <>
          <section className="card">
            <h3 className="card__title">{translate('network.calls', language)}</h3>
            <table className="runs">
              <thead>
                <tr>
                  <th>{translate('network.method', language)}</th>
                  <th>{translate('network.url', language)}</th>
                  <th>{translate('network.status', language)}</th>
                  <th>{translate('network.duration', language)}</th>
                  <th>{translate('network.outcome', language)}</th>
                </tr>
              </thead>
              <tbody>
                {capture.calls.slice(0, 200).map((call) => (
                  <tr
                    key={call.callId}
                    className={call.callId === selectedCallId ? 'runs__selected' : undefined}
                    onClick={() => setSelectedCallId(call.callId)}
                  >
                    <td>{call.method}</td>
                    <td className="runs__url">{call.redactedUrl}</td>
                    <td>{call.exchanges[0]?.statusCode ?? '-'}</td>
                    <td>{formatMs(callDurationNs(call))}</td>
                    <td>{call.outcome}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="card">
            <h3 className="card__title">{translate('network.phases', language)}</h3>
            {selectedCall === null ? (
              <p className="card__muted">{translate('network.selectCall', language)}</p>
            ) : (
              <>
                <div className="phase-bar">
                  {segments.map((segment) => (
                    <span
                      key={segment.phase.kind}
                      className={MEASURED.has(segment.phase.confidence) ? 'phase-bar__segment' : 'phase-bar__segment phase-bar__segment--inferred'}
                      style={{ width: totalNs > 0 ? (segment.durationNs / totalNs) * 100 + '%' : '0%' }}
                      title={segment.phase.kind + ' · ' + formatMs(segment.durationNs) + ' · ' + segment.phase.confidence}
                    />
                  ))}
                </div>
                <ul className="list">
                  {segments.map((segment) => (
                    <li key={segment.phase.kind}>
                      {segment.phase.kind} · {formatMs(segment.durationNs)} · {segment.phase.confidence}
                      {segment.phase.availability !== 'VALUE' ? ' · ' + segment.phase.availability : ''}
                    </li>
                  ))}
                </ul>
                <p className="card__muted">{translate('network.confidence', language)}</p>
              </>
            )}
          </section>
        </>
      )}

      {message !== null ? <p className="card__muted">{message}</p> : null}
    </>
  );
}
