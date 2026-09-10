import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import { analyzeSession, type FrameSession } from '@aps/frame-profiler';
import type { DeviceSummary, FrameSessionSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface FrameProfilerPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const MAX_TIMELINE_BARS = 800;

const SEVERITY_COLOR: Readonly<Record<string, string>> = {
  SMOOTH: '#4c7f5f',
  MINOR: '#d8b34a',
  MAJOR: '#e08a3c',
  SEVERE: '#e0574f',
  FROZEN: '#c2589f',
  UNKNOWN: '#5a6473',
};

function formatRate(value: number | undefined): string {
  return value === undefined ? '-' : (value * 100).toFixed(1) + '%';
}

function formatMs(value: number | undefined): string {
  return value === undefined ? '-' : (value / 1_000_000).toFixed(2) + ' ms';
}

export function FrameProfilerPanel({ language, devices }: FrameProfilerPanelProps): JSX.Element {
  const [sessions, setSessions] = useState<readonly FrameSessionSummary[]>([]);
  const [serial, setSerial] = useState('');
  const [packageName, setPackageName] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [session, setSession] = useState<FrameSession | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listFrameSessions().then((records) => {
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
      .loadFrameSession(selectedId)
      .then((loaded) => setSession(loaded ?? null))
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [selectedId]);

  const capture = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .captureFrame({ serial, packageName })
      .then((outcome) => {
        setMessage(
          outcome.ok
            ? translate('frame.complete', language)
            : translate('frame.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        if (outcome.ok && outcome.id !== undefined) setSelectedId(outcome.id);
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, packageName, refresh, serial]);

  const analysis = useMemo(() => (session === null ? null : analyzeSession(session)), [session]);
  const bars = useMemo(() => {
    if (analysis === null) return [];
    const frames = analysis.frames;
    const stride = Math.max(1, Math.ceil(frames.length / MAX_TIMELINE_BARS));
    return frames
      .filter((_frame, index) => index % stride === 0)
      .map((frame) => {
        const duration = frame.sample.totalDurationNs ?? 0;
        const expected = frame.sample.expectedDurationNs ?? 0;
        return { id: frame.sample.frameId, duration, expected, severity: frame.severity };
      });
  }, [analysis]);

  const worst = analysis?.summary.worstDurationNs ?? 0;

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('frame.capture', language)}</h3>
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
            <span>{translate('frame.sessions', language)}</span>
            <select value={selectedId} onChange={(event) => setSelectedId(event.target.value)}>
              <option value="">-</option>
              {sessions.map((record) => (
                <option key={record.id} value={record.id}>
                  {record.id} · {record.packageName} · {record.frameCount}
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
        {session !== null && session.warnings.length > 0 ? (
          <p className="card__muted">{session.warnings.join(' ')}</p>
        ) : null}
      </section>

      {analysis === null ? (
        <p className="content__muted">{translate('frame.none', language)}</p>
      ) : (
        <>
          <div className="metrics">
            <div className="metric">
              <span className="metric__label">{translate('frame.frames', language)}</span>
              <span className="metric__value">{analysis.summary.totalFrames}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('frame.deadlineMiss', language)}</span>
              <span className="metric__value">{formatRate(analysis.summary.deadlineMissRate)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('frame.platformJank', language)}</span>
              <span className="metric__value">{formatRate(analysis.summary.platformJankRate)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('frame.p50', language)}</span>
              <span className="metric__value">{formatMs(analysis.summary.p50DurationNs)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('frame.p95', language)}</span>
              <span className="metric__value">{formatMs(analysis.summary.p95DurationNs)}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('frame.p99', language)}</span>
              <span className="metric__value">{formatMs(analysis.summary.p99DurationNs)}</span>
            </div>
          </div>

          <section className="card">
            <h3 className="card__title">{translate('frame.timeline', language)}</h3>
            <div className="timeline">
              {bars.map((bar) => (
                <span
                  key={bar.id}
                  className="timeline__bar"
                  title={'frame ' + bar.id + ' · ' + (bar.duration / 1_000_000).toFixed(2) + ' ms'}
                  style={{
                    height: worst > 0 ? Math.max(2, (bar.duration / worst) * 100) + '%' : '2%',
                    background: SEVERITY_COLOR[bar.severity] ?? SEVERITY_COLOR['UNKNOWN'],
                  }}
                />
              ))}
            </div>
          </section>

          <section className="card">
            <h3 className="card__title">{translate('frame.clusters', language)}</h3>
            {analysis.clusters.length === 0 ? (
              <p className="card__muted">{translate('frame.noClusters', language)}</p>
            ) : (
              <ul className="list">
                {analysis.clusters.slice(0, 10).map((cluster) => (
                  <li key={cluster.id}>
                    frames {cluster.firstFrameId}-{cluster.lastFrameId} · {cluster.worstSeverity} ·{' '}
                    {(cluster.durationNs / 1_000_000).toFixed(1)} ms
                    {cluster.dominantReportedStage !== undefined ? ' · ' + cluster.dominantReportedStage : ''}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}

      {message !== null ? <p className="card__muted">{message}</p> : null}
    </>
  );
}
