import { useCallback, useEffect, useState, type JSX } from 'react';
import type { RegressionReport } from '@aps/benchmark-regression';
import type { BenchmarkRunSummary, DeviceSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface BenchmarkRegressionPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

const CLASSIFICATION_ORDER = ['REGRESSED', 'IMPROVED', 'INCOMPATIBLE', 'INCONCLUSIVE', 'STABLE'];

function formatValue(value: number | undefined): string {
  return value === undefined ? '-' : value.toFixed(2);
}

function formatDelta(value: number | undefined): string {
  return value === undefined ? '-' : (value > 0 ? '+' : '') + value.toFixed(2) + '%';
}

export function BenchmarkRegressionPanel({ language }: BenchmarkRegressionPanelProps): JSX.Element {
  const [runs, setRuns] = useState<readonly BenchmarkRunSummary[]>([]);
  const [baselineId, setBaselineId] = useState('');
  const [currentId, setCurrentId] = useState('');
  const [relativeThreshold, setRelativeThreshold] = useState(5);
  const [absoluteThreshold, setAbsoluteThreshold] = useState(0);
  const [report, setReport] = useState<RegressionReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listBenchmarkRuns().then((records) => {
      setRuns(records);
      if (records.length >= 2) {
        setBaselineId((current) => (current.length > 0 ? current : records[1]?.id ?? ''));
        setCurrentId((current) => (current.length > 0 ? current : records[0]?.id ?? ''));
      } else if (records.length === 1) {
        setCurrentId((current) => (current.length > 0 ? current : records[0]?.id ?? ''));
      }
    });
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const importRun = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .importBenchmarkRun()
      .then((outcome) => {
        if (outcome.cancelled === true) return;
        setMessage(
          outcome.ok
            ? translate('benchmark.complete', language)
            : translate('benchmark.failed', language) + ': ' + String(outcome.error ?? ''),
        );
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, refresh]);

  const compare = useCallback(() => {
    setMessage(null);
    window.aps
      .compareBenchmarkRuns({
        baselineId,
        currentId,
        relativeThresholdPercent: relativeThreshold,
        ...(absoluteThreshold > 0 ? { absoluteThreshold } : {}),
      })
      .then((outcome) => {
        if (!outcome.ok) {
          setMessage(String(outcome.error ?? ''));
          return;
        }
        setReport(outcome.report ?? null);
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [absoluteThreshold, baselineId, currentId, relativeThreshold]);

  const comparisons = report?.comparisons ?? [];
  const sorted = [...comparisons].sort(
    (left, right) => CLASSIFICATION_ORDER.indexOf(left.classification) - CLASSIFICATION_ORDER.indexOf(right.classification),
  );

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('benchmark.import', language)}</h3>
        <div className="form">
          <label className="field">
            <span>{translate('benchmark.baseline', language)}</span>
            <select value={baselineId} onChange={(event) => setBaselineId(event.target.value)}>
              <option value="">-</option>
              {runs.map((run) => (
                <option key={run.id} value={run.id}>
                  {run.id} · {run.deviceModel ?? 'unknown'} · API {run.apiLevel ?? '?'} · {run.variant ?? '?'}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('benchmark.current', language)}</span>
            <select value={currentId} onChange={(event) => setCurrentId(event.target.value)}>
              <option value="">-</option>
              {runs.map((run) => (
                <option key={run.id} value={run.id}>
                  {run.id} · {run.deviceModel ?? 'unknown'} · API {run.apiLevel ?? '?'} · {run.variant ?? '?'}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>{translate('benchmark.relativeThreshold', language)}</span>
            <input
              type="number"
              min={0}
              value={relativeThreshold}
              onChange={(event) => setRelativeThreshold(Number(event.target.value))}
            />
          </label>
          <label className="field">
            <span>{translate('benchmark.absoluteThreshold', language)}</span>
            <input
              type="number"
              min={0}
              value={absoluteThreshold}
              onChange={(event) => setAbsoluteThreshold(Number(event.target.value))}
            />
          </label>
        </div>
        <button type="button" className="button" disabled={busy} onClick={importRun}>
          {translate('benchmark.import', language)}
        </button>
        <button
          type="button"
          className="button button--inline"
          disabled={baselineId.length === 0 || currentId.length === 0}
          onClick={compare}
        >
          {translate('benchmark.compareAction', language)}
        </button>
        <p className="card__muted">{translate('benchmark.gateNote', language)}</p>
      </section>

      {report === null ? (
        <p className="content__muted">{translate('benchmark.none', language)}</p>
      ) : (
        <>
          <div className="metrics">
            <div className="metric">
              <span className="metric__label">{translate('benchmark.regressions', language)}</span>
              <span className="metric__value">{report.regressionCount}</span>
            </div>
            <div className="metric">
              <span className="metric__label">{translate('benchmark.compatibility', language)}</span>
              <span className="metric__value">{report.compatibilityIssues.length}</span>
            </div>
          </div>

          <section className="card">
            <h3 className="card__title">{translate('benchmark.compatibility', language)}</h3>
            {report.compatibilityIssues.length === 0 ? (
              <p className="card__muted">{translate('benchmark.compatible', language)}</p>
            ) : (
              <ul className="list">
                {report.compatibilityIssues.map((issue) => (
                  <li key={issue.field}>
                    {issue.hard ? '[hard] ' : '[soft] '}
                    {issue.field}: {issue.baseline ?? '-'} → {issue.current ?? '-'}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card">
            <h3 className="card__title">{translate('benchmark.compare', language)}</h3>
            <table className="runs">
              <thead>
                <tr>
                  <th>{translate('benchmark.case', language)}</th>
                  <th>{translate('benchmark.metric', language)}</th>
                  <th>{translate('benchmark.baseline', language)}</th>
                  <th>{translate('benchmark.current', language)}</th>
                  <th>{translate('benchmark.delta', language)}</th>
                  <th>{translate('benchmark.classification', language)}</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((comparison) => (
                  <tr key={comparison.caseIdentity + '|' + comparison.metricName}>
                    <td>{comparison.caseIdentity}</td>
                    <td>{comparison.metricName}</td>
                    <td>{formatValue(comparison.baselineValue)}</td>
                    <td>{formatValue(comparison.currentValue)}</td>
                    <td>{formatDelta(comparison.relativeDeltaPercent)}</td>
                    <td className={'verdict verdict--' + comparison.classification.toLowerCase()}>
                      {comparison.classification}
                      {comparison.confidence === 'PARTIAL' ? ' (partial samples)' : ''}
                    </td>
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
