import { useEffect, useState, type JSX } from 'react';
import type { AppInfo } from '../../shared/app-info';

export function App(): JSX.Element {
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    window.aps
      .getAppInfo()
      .then((value) => {
        if (!cancelled) setInfo(value);
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main className="shell">
      <header className="shell__header">
        <p className="shell__eyebrow">Phase 0 · Foundation</p>
        <h1 className="shell__title">Android Performance Studio</h1>
        <p className="shell__subtitle">
          Electron rewrite in progress. No releases are produced during the rewrite.
        </p>
      </header>

      <section className="card">
        <h2 className="card__title">Runtime</h2>
        {error !== null ? <p className="card__error">IPC error: {error}</p> : null}
        {info === null ? (
          <p className="card__muted">Loading application info…</p>
        ) : (
          <dl className="facts">
            <div className="facts__row">
              <dt>Application</dt>
              <dd>{info.name}</dd>
            </div>
            <div className="facts__row">
              <dt>Version</dt>
              <dd>{info.version}</dd>
            </div>
            <div className="facts__row">
              <dt>Capture Artifact contract</dt>
              <dd>v{info.contractVersion}</dd>
            </div>
            <div className="facts__row">
              <dt>Platform</dt>
              <dd>{info.platform}</dd>
            </div>
          </dl>
        )}
      </section>
    </main>
  );
}
