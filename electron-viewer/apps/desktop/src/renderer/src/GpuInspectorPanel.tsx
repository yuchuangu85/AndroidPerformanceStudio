import { useCallback, useEffect, useState, type JSX } from 'react';
import type { AgiCapability } from '@aps/gpu-inspector';
import type { DeviceSummary, GpuArtifactSummary } from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface GpuInspectorPanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
}

export function GpuInspectorPanel({ language }: GpuInspectorPanelProps): JSX.Element {
  const [capability, setCapability] = useState<AgiCapability | null>(null);
  const [artifacts, setArtifacts] = useState<readonly GpuArtifactSummary[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.getAgiStatus().then(setCapability).catch(() => setCapability(null));
    window.aps.listGpuArtifacts().then(setArtifacts).catch(() => setArtifacts([]));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const run = useCallback(
    (action: () => Promise<{ ok: boolean; error?: string; note?: string; cancelled?: boolean }>) => {
      setBusy(true);
      setMessage(null);
      action()
        .then((outcome) => {
          if (outcome.cancelled === true) return;
          setMessage(
            outcome.ok
              ? outcome.note ?? translate('gpu.complete', language)
              : translate('gpu.failed', language) + ': ' + String(outcome.error ?? ''),
          );
          refresh();
        })
        .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
        .finally(() => setBusy(false));
    },
    [language, refresh],
  );

  const locationNote = (artifact: GpuArtifactSummary): string | null => {
    if (artifact.locationStatus === 'MISSING') return translate('gpu.locationMissing', language);
    if (artifact.locationStatus === 'SIZE_CHANGED') return translate('gpu.locationSizeChanged', language);
    return null;
  };

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('gpu.title', language)}</h3>
        {capability === null ? (
          <p className="card__muted">{translate('gpu.unavailable', language)}</p>
        ) : (
          <p className="card__muted">
            {capability.executable ?? '-'}
            {capability.version !== undefined ? ' · ' + capability.version : ''}
            {' · '}
            {capability.launchMode}
          </p>
        )}
        {capability?.warnings.map((warning) => <p className="card__muted" key={warning}>{warning}</p>)}
        <button
          type="button"
          className="button"
          disabled={busy || capability?.launchSupported !== true}
          onClick={() => run(() => window.aps.launchAgi())}
        >
          {translate('gpu.launch', language)}
        </button>
        <button
          type="button"
          className="button button--inline"
          disabled={busy}
          onClick={() => run(() => window.aps.importGpuArtifact())}
        >
          {translate('gpu.import', language)}
        </button>
        <p className="card__muted">{translate('gpu.contentAddressed', language)}</p>
      </section>

      <section className="card">
        <h3 className="card__title">{translate('gpu.artifacts', language)}</h3>
        {artifacts.length === 0 ? (
          <p className="card__muted">{translate('gpu.none', language)}</p>
        ) : (
          <table className="runs">
            <thead>
              <tr>
                <th>{translate('gpu.kind', language)}</th>
                <th>{translate('gpu.route', language)}</th>
                <th>{translate('gpu.location', language)}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {artifacts.map((artifact) => (
                <tr key={artifact.id}>
                  <td>{artifact.kind}</td>
                  <td>{artifact.openRoute}</td>
                  <td className="runs__url">
                    {artifact.path} · {formatSize(artifact.sizeBytes)} · {artifact.locationStatus} (
                    {artifact.locationCount})
                  </td>
                  <td>
                    <button
                      type="button"
                      className="button button--inline"
                      disabled={busy}
                      onClick={() => run(() => window.aps.openGpuArtifact(artifact.id))}
                    >
                      {translate('gpu.open', language)}
                    </button>
                    <button
                      type="button"
                      className="button button--inline"
                      disabled={busy}
                      onClick={() => run(() => window.aps.revealGpuArtifact(artifact.id))}
                    >
                      {translate('gpu.reveal', language)}
                    </button>
                    <button
                      type="button"
                      className="button button--inline"
                      disabled={busy}
                      onClick={() => run(() => window.aps.relocateGpuArtifact(artifact.id))}
                    >
                      {translate('gpu.relocate', language)}
                    </button>
                    {locationNote(artifact) !== null ? (
                      <span className="card__muted"> {locationNote(artifact)}</span>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {message !== null ? <p className="card__muted">{message}</p> : null}
    </>
  );
}
