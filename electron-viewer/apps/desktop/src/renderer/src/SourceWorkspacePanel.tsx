import { useCallback, useEffect, useMemo, useState, type JSX } from 'react';
import type { SourceResolutionEvidence } from '@aps/source-workspace';
import type {
  DeviceSummary,
  SourceCandidateSummary,
  SourceReadOutcome,
  SourceSymbolSummary,
  SourceWorkspaceRecord,
} from '../../shared/ipc';
import { translate, type UiLanguage } from '../../shared/i18n';

export interface SourceWorkspacePanelProps {
  readonly language: UiLanguage;
  readonly devices: readonly DeviceSummary[];
}

type EvidenceKind = SourceResolutionEvidence['kind'];

const EVIDENCE_KINDS: readonly EvidenceKind[] = [
  'TYPE_NAME',
  'MANAGED_SYMBOL',
  'SOURCE_FILE_LINE',
  'ANDROID_RESOURCE',
  'NATIVE_SYMBOL',
];

const CONFIDENCE_ORDER: Record<SourceCandidateSummary['confidence'], number> = {
  EXACT: 0,
  PROBABLE: 1,
  WEAK: 2,
};

export function SourceWorkspacePanel({ language }: SourceWorkspacePanelProps): JSX.Element {
  const [workspaces, setWorkspaces] = useState<readonly SourceWorkspaceRecord[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [query, setQuery] = useState('');
  const [symbols, setSymbols] = useState<readonly SourceSymbolSummary[]>([]);
  const [kind, setKind] = useState<EvidenceKind>('TYPE_NAME');
  const [typeName, setTypeName] = useState('');
  const [className, setClassName] = useState('');
  const [methodName, setMethodName] = useState('');
  const [resourceType, setResourceType] = useState('string');
  const [resourceName, setResourceName] = useState('');
  const [fileName, setFileName] = useState('');
  const [packageName, setPackageName] = useState('');
  const [line, setLine] = useState(1);
  const [nativeSymbol, setNativeSymbol] = useState('');
  const [buildVerified, setBuildVerified] = useState(false);
  const [candidates, setCandidates] = useState<readonly SourceCandidateSummary[]>([]);
  const [content, setContent] = useState<SourceReadOutcome | null>(null);
  const [targetLine, setTargetLine] = useState<number | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(() => {
    window.aps.listSourceWorkspaces().then((records) => {
      setWorkspaces(records);
      setSelectedId((current) => (current.length > 0 ? current : records[0]?.id ?? ''));
    });
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const workspace = useMemo(
    () => workspaces.find((record) => record.id === selectedId),
    [selectedId, workspaces],
  );

  const openFile = useCallback((relativePath: string, startLine?: number) => {
    setTargetLine(startLine);
    window.aps
      .readSourceFile({ workspaceId: selectedId, relativePath })
      .then((outcome) => {
        if (!outcome.ok) {
          setMessage(outcome.error ?? 'read failed');
          setContent(null);
          return;
        }
        setContent(outcome);
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [selectedId]);

  const add = useCallback(() => {
    setBusy(true);
    setMessage(null);
    window.aps
      .addSourceWorkspace()
      .then((result) => {
        setMessage(
          result.ok
            ? translate('source.indexed', language)
            : translate('source.failed', language) + ': ' + String(result.error ?? ''),
        );
        if (result.ok && result.id !== undefined) setSelectedId(result.id);
        refresh();
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  }, [language, refresh]);

  const reindex = useCallback(() => {
    if (selectedId.length === 0) return;
    setBusy(true);
    setMessage(null);
    window.aps
      .reindexSourceWorkspace(selectedId)
      .then((result) => {
        setMessage(result.ok ? translate('source.indexed', language) : String(result.error ?? ''));
        refresh();
      })
      .finally(() => setBusy(false));
  }, [language, refresh, selectedId]);

  const search = useCallback(() => {
    if (selectedId.length === 0 || query.trim().length === 0) {
      setSymbols([]);
      return;
    }
    window.aps
      .searchSourceSymbols({ workspaceId: selectedId, query, limit: 50 })
      .then(setSymbols)
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [query, selectedId]);

  const evidence = useCallback((): SourceResolutionEvidence[] => {
    const id = 'ui-1';
    switch (kind) {
      case 'TYPE_NAME':
        return [{ kind, id, qualifiedName: typeName }];
      case 'MANAGED_SYMBOL':
        return [
          {
            kind,
            id,
            methodName,
            ...(className.length > 0 ? { className } : {}),
          },
        ];
      case 'ANDROID_RESOURCE':
        return [{ kind, id, resourceType, resourceName }];
      case 'NATIVE_SYMBOL':
        return [{ kind, id, symbolName: nativeSymbol }];
      case 'SOURCE_FILE_LINE': {
        // The package hash is what a Compose trace records; compute it the same way.
        const hash = javaHash(packageName);
        return [{ kind, id, fileName, packageHash: hash, line }];
      }
    }
  }, [className, fileName, kind, line, methodName, nativeSymbol, packageName, resourceName, resourceType, typeName]);

  const resolve = useCallback(() => {
    if (selectedId.length === 0) return;
    setMessage(null);
    window.aps
      .resolveSourceEvidence({
        workspaceId: selectedId,
        evidence: evidence(),
        buildIdentityMatch: buildVerified ? 'VERIFIED' : 'UNVERIFIED',
      })
      .then((outcome) => {
        if (!outcome.ok) {
          setMessage(outcome.error ?? 'resolve failed');
          setCandidates([]);
          return;
        }
        setCandidates([...(outcome.candidates ?? [])].sort(
          (left, right) => CONFIDENCE_ORDER[left.confidence] - CONFIDENCE_ORDER[right.confidence],
        ));
      })
      .catch((reason: unknown) => setMessage(reason instanceof Error ? reason.message : String(reason)));
  }, [buildVerified, evidence, selectedId]);

  const lines = content?.text === undefined ? [] : content.text.split('\n');

  return (
    <>
      <section className="card">
        <h3 className="card__title">{translate('source.workspaces', language)}</h3>
        {workspaces.length === 0 ? (
          <p className="card__muted">{translate('source.none', language)}</p>
        ) : (
          <div className="form">
            <label className="field">
              <span>{translate('source.workspace', language)}</span>
              <select
                value={selectedId}
                onChange={(input) => {
                  setSelectedId(input.target.value);
                  setSymbols([]);
                  setCandidates([]);
                  setContent(null);
                }}
              >
                {workspaces.map((record) => (
                  <option key={record.id} value={record.id}>
                    {record.displayName} · {record.fileCount} files · {record.symbolCount} symbols
                  </option>
                ))}
              </select>
            </label>
            <button type="button" className="button" disabled={busy} onClick={reindex}>
              {translate('source.reindex', language)}
            </button>
            <button
              type="button"
              className="button"
              onClick={() => {
                void window.aps.removeSourceWorkspace(selectedId).then(() => {
                  setContent(null);
                  setCandidates([]);
                  setSymbols([]);
                  refresh();
                });
              }}
            >
              {translate('source.remove', language)}
            </button>
          </div>
        )}
        <div className="form">
          <button type="button" className="button" disabled={busy} onClick={add}>
            {busy ? translate('source.indexing', language) : translate('source.add', language)}
          </button>
        </div>
        {workspace !== undefined ? (
          <p className="card__muted">
            <code>{workspace.root}</code> · {workspace.phase}
            {workspace.revision !== undefined ? ' · ' + workspace.revision : ''}
            {workspace.indexedAtEpochMillis !== undefined
              ? ' · ' + new Date(workspace.indexedAtEpochMillis).toLocaleString()
              : ''}
          </p>
        ) : null}
        {message !== null ? <p className="card__muted">{message}</p> : null}
      </section>

      <section className="card">
        <h3 className="card__title">{translate('source.search', language)}</h3>
        <div className="form">
          <label className="field">
            <span>{translate('source.symbol', language)}</span>
            <input
              type="text"
              value={query}
              placeholder="MainActivity"
              onChange={(input) => setQuery(input.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') search();
              }}
            />
          </label>
          <button type="button" className="button" disabled={selectedId.length === 0} onClick={search}>
            {translate('source.searchAction', language)}
          </button>
        </div>
        {symbols.length === 0 ? (
          <p className="card__muted">{translate('source.searchHint', language)}</p>
        ) : (
          <table className="runs">
            <thead>
              <tr>
                <th>{translate('source.kind', language)}</th>
                <th>{translate('source.qualifiedName', language)}</th>
                <th>{translate('source.file', language)}</th>
              </tr>
            </thead>
            <tbody>
              {symbols.map((symbol) => (
                <tr key={symbol.kind + symbol.qualifiedName + symbol.relativePath}>
                  <td>{symbol.kind}</td>
                  <td>
                    <code>{symbol.qualifiedName}</code>
                    {symbol.signature !== undefined ? <span className="card__muted"> ({symbol.signature})</span> : null}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="button button--inline"
                      onClick={() => openFile(symbol.relativePath, symbol.startLine)}
                    >
                      {symbol.relativePath.split('/').pop()}:{String(symbol.startLine)}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h3 className="card__title">{translate('source.resolve', language)}</h3>
        <div className="form">
          <label className="field">
            <span>{translate('source.evidence', language)}</span>
            <select value={kind} onChange={(input) => setKind(input.target.value as EvidenceKind)}>
              {EVIDENCE_KINDS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          {kind === 'TYPE_NAME' ? (
            <label className="field">
              <span>{translate('source.qualifiedName', language)}</span>
              <input type="text" value={typeName} onChange={(input) => setTypeName(input.target.value)} />
            </label>
          ) : null}
          {kind === 'MANAGED_SYMBOL' ? (
            <>
              <label className="field">
                <span>{translate('source.className', language)}</span>
                <input type="text" value={className} onChange={(input) => setClassName(input.target.value)} />
              </label>
              <label className="field">
                <span>{translate('source.methodName', language)}</span>
                <input type="text" value={methodName} onChange={(input) => setMethodName(input.target.value)} />
              </label>
            </>
          ) : null}
          {kind === 'ANDROID_RESOURCE' ? (
            <>
              <label className="field">
                <span>{translate('source.resourceType', language)}</span>
                <input type="text" value={resourceType} onChange={(input) => setResourceType(input.target.value)} />
              </label>
              <label className="field">
                <span>{translate('source.resourceName', language)}</span>
                <input type="text" value={resourceName} onChange={(input) => setResourceName(input.target.value)} />
              </label>
            </>
          ) : null}
          {kind === 'NATIVE_SYMBOL' ? (
            <label className="field">
              <span>{translate('source.symbol', language)}</span>
              <input type="text" value={nativeSymbol} onChange={(input) => setNativeSymbol(input.target.value)} />
            </label>
          ) : null}
          {kind === 'SOURCE_FILE_LINE' ? (
            <>
              <label className="field">
                <span>{translate('source.fileName', language)}</span>
                <input type="text" value={fileName} onChange={(input) => setFileName(input.target.value)} />
              </label>
              <label className="field">
                <span>{translate('source.package', language)}</span>
                <input type="text" value={packageName} onChange={(input) => setPackageName(input.target.value)} />
              </label>
              <label className="field">
                <span>{translate('source.line', language)}</span>
                <input type="number" min={1} value={line} onChange={(input) => setLine(Number(input.target.value))} />
              </label>
            </>
          ) : null}
          <label className="field">
            <span>{translate('source.buildIdentity', language)}</span>
            <select
              value={buildVerified ? 'VERIFIED' : 'UNVERIFIED'}
              onChange={(input) => setBuildVerified(input.target.value === 'VERIFIED')}
            >
              <option value="UNVERIFIED">UNVERIFIED</option>
              <option value="VERIFIED">VERIFIED</option>
            </select>
          </label>
          <button type="button" className="button" disabled={selectedId.length === 0} onClick={resolve}>
            {translate('source.resolveAction', language)}
          </button>
        </div>
        <p className="card__muted">{translate('source.buildIdentityNote', language)}</p>
        {candidates.length === 0 ? (
          <p className="card__muted">{translate('source.noCandidates', language)}</p>
        ) : (
          <table className="runs">
            <thead>
              <tr>
                <th>{translate('source.confidence', language)}</th>
                <th>{translate('source.file', language)}</th>
                <th>{translate('source.reasons', language)}</th>
              </tr>
            </thead>
            <tbody>
              {candidates.map((candidate) => (
                <tr key={candidate.id}>
                  <td>{candidate.confidence}</td>
                  <td>
                    <button
                      type="button"
                      className="button button--inline"
                      onClick={() => openFile(candidate.relativePath, candidate.startLine)}
                    >
                      {candidate.relativePath.split('/').pop()}
                      {candidate.startLine !== undefined ? ':' + String(candidate.startLine) : ''}
                    </button>
                  </td>
                  <td>{candidate.reasons.join(', ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {content?.ok === true && content.text !== undefined ? (
        <section className="card">
          <h3 className="card__title">
            {translate('source.content', language)}: <code>{content.relativePath}</code>
          </h3>
          <p className="card__muted">
            {content.language} ·{' '}
            {content.state === 'STALE' ? translate('source.stale', language) : translate('source.current', language)}
          </p>
          <div className="source-view">
            <pre>
              {lines.map((text, index) => {
                const lineNumber = index + 1;
                return (
                  <span
                    key={String(lineNumber)}
                    className={lineNumber === targetLine ? 'source-view__line source-view__line--target' : 'source-view__line'}
                  >
                    {String(lineNumber).padStart(5, ' ') + '  ' + text + '\n'}
                  </span>
                );
              })}
            </pre>
          </div>
        </section>
      ) : null}
    </>
  );
}

/** Java String.hashCode, matching what a Compose trace records. */
function javaHash(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (Math.imul(31, hash) + value.charCodeAt(index)) | 0;
  }
  return hash === -2147483648 ? -2147483648 : Math.abs(hash);
}
