import { useCallback, useEffect, useState, type JSX } from 'react';
import type { UiLanguage } from '../../shared/i18n';
import { sourceCandidateChoice } from './source-candidate-choice';
import type {
  AiAnalyzeOutcome,
  AiSessionSummary,
  AiSettingsSnapshot,
  AnalysisFinding,
  LayoutCaptureSummary,
  SourceOpenRequest,
  SourceWorkspaceRecord,
} from '../../shared/ipc';

const STRINGS = {
  capture: { en: 'Layout capture', zh: '布局抓取' },
  analyze: { en: 'Analyze', zh: '开始分析' },
  none: { en: 'No layout capture yet', zh: '还没有布局抓取' },
  configured: { en: 'Key stored', zh: '密钥已保存' },
  memoryOnly: { en: 'memory only (no OS key)', zh: '仅内存（无系统密钥）' },
  notConfigured: { en: 'No API key configured', zh: '尚未配置 API key' },
  openSettings: { en: 'AI Settings', zh: 'AI 设置' },
  settingsHint: {
    en: 'The API key, model and endpoint are configured in Settings › AI Settings.',
    zh: 'API key、模型与接口地址在「设置 › AI 设置」中配置。',
  },
  findings: { en: 'Findings', zh: '发现' },
  history: { en: 'Sessions', zh: '会话' },
  empty: { en: 'No findings yet', zh: '还没有结果' },
  workspace: { en: 'Source workspace', zh: '源码工作区' },
  noWorkspace: { en: 'No source context', zh: '不使用源码上下文' },
  sourceCandidates: { en: 'Source citations', zh: '源码引用' },
  openSource: { en: 'Open source', zh: '打开源码' },
  selectSource: { en: 'Select source', zh: '选择源码' },
  selectSourceCandidate: { en: 'Select source candidate', zh: '选择源码候选项' },
  sourceCandidate: { en: 'Source candidate', zh: '源码候选项' },
  cancel: { en: 'Cancel', zh: '取消' },
  workspaceHint: {
    en: 'Findings cite a file and a line only when a workspace is selected and allows AI source upload.',
    zh: '只有在选中工作区且该工作区允许上传源码时，结论才会引用文件与行号。',
  },
} as const;

type StringKey = keyof typeof STRINGS;

function t(key: StringKey, language: UiLanguage): string {
  return STRINGS[key][language];
}

const SEVERITY_CLASS: Record<AnalysisFinding['severity'], string> = {
  INFO: 'card__muted',
  WARNING: 'card__muted',
  ERROR: 'card__error',
};

export interface AiAnalysisPanelProps {
  readonly language: UiLanguage;
  readonly onOpenSettings: () => void;
  readonly onOpenSourceLocation: (request: SourceOpenRequest) => void;
}

/**
 * The analysis workflow. Configuration lives in Settings › AI Settings, so this
 * panel only reports which model the stored settings will use.
 */
export function AiAnalysisPanel({ language, onOpenSettings, onOpenSourceLocation }: AiAnalysisPanelProps): JSX.Element {
  const [settings, setSettings] = useState<AiSettingsSnapshot | null>(null);
  const [captures, setCaptures] = useState<readonly LayoutCaptureSummary[]>([]);
  const [captureId, setCaptureId] = useState('');
  const [workspaces, setWorkspaces] = useState<readonly SourceWorkspaceRecord[]>([]);
  const [workspaceId, setWorkspaceId] = useState('');
  const [outcome, setOutcome] = useState<AiAnalyzeOutcome | null>(null);
  const [sessions, setSessions] = useState<readonly AiSessionSummary[]>([]);
  const [findings, setFindings] = useState<readonly AnalysisFinding[]>([]);
  const [findingSessionId, setFindingSessionId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sourceCandidateChoices, setSourceCandidateChoices] = useState<readonly string[]>([]);

  const reload = useCallback(() => {
    void window.aps.getAiSettings().then(setSettings);
    void window.aps.listAiSessions().then(setSessions);
    void window.aps.listSourceWorkspaces().then(setWorkspaces);
    void window.aps.listLayoutCaptures().then((listed) => {
      setCaptures(listed);
      setCaptureId((current) => (current.length > 0 ? current : (listed[0]?.id ?? '')));
    });
  }, []);

  useEffect(reload, [reload]);

  const openSourceCandidate = (candidateId: string): void => {
    if (findingSessionId.length === 0) {
      setError('The selected findings have no persisted AI session.');
      return;
    }
    void window.aps.openAiSourceCandidate({ sessionId: findingSessionId, candidateId }).then((result) => {
      if (!result.ok || result.location === undefined) {
        setError(result.error ?? 'Unable to open the cited source.');
        return;
      }
      onOpenSourceLocation(result.location);
    }).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : String(reason)));
  };

  const requestSourceCandidate = (candidateIds: readonly string[]): void => {
    const choice = sourceCandidateChoice(candidateIds);
    if (choice.kind === 'open') {
      openSourceCandidate(choice.candidateId);
    } else if (choice.kind === 'choose') {
      setSourceCandidateChoices(choice.candidateIds);
    }
  };

  const run = (body: () => Promise<void>): void => {
    setBusy(true);
    setError(null);
    body()
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setBusy(false));
  };

  return (
    <section className="card">
      <h3 className="card__title">{t('openSettings', language)}</h3>
      <p className="card__muted">
        {settings === null
          ? ''
          : settings.configured
            ? t('configured', language) +
              ' · ' +
              settings.model +
              (settings.persistent ? '' : ' · ' + t('memoryOnly', language))
            : t('notConfigured', language)}
      </p>
      <p className="card__muted">{t('settingsHint', language)}</p>
      <button type="button" className="button" onClick={onOpenSettings}>
        {t('openSettings', language)}
      </button>

      <h3 className="card__title">{t('capture', language)}</h3>
      {captures.length === 0 ? (
        <p className="card__muted">{t('none', language)}</p>
      ) : (
        <label className="field">
          <span>{t('capture', language)}</span>
          <select value={captureId} onChange={(event) => setCaptureId(event.target.value)}>
            {captures.map((capture) => (
              <option key={capture.id} value={capture.id}>
                {capture.id}
              </option>
            ))}
          </select>
        </label>
      )}
      <label className="field">
        <span>{t('workspace', language)}</span>
        <select value={workspaceId} onChange={(event) => setWorkspaceId(event.target.value)}>
          <option value="">{t('noWorkspace', language)}</option>
          {workspaces.map((workspace) => (
            <option key={workspace.id} value={workspace.id}>
              {workspace.displayName}
            </option>
          ))}
        </select>
      </label>
      <p className="card__muted">{t('workspaceHint', language)}</p>
      <button
        type="button"
        className="button"
        disabled={busy || captureId.length === 0}
        onClick={() =>
          run(async () => {
            const result = await window.aps.analyzeLayoutWithAi({
              captureId,
              ...(workspaceId.length > 0 ? { workspaceId } : {}),
            });
            setOutcome(result);
            setFindings(result.findings ?? []);
            setFindingSessionId(result.sessionId);
            setSessions(await window.aps.listAiSessions());
          })
        }
      >
        {t('analyze', language)}
      </button>

      {outcome !== null && !outcome.ok ? <p className="card__error">{outcome.error}</p> : null}
      {outcome !== null && outcome.ok ? <p className="card__muted">{outcome.summary}</p> : null}

      <h3 className="card__title">{t('findings', language)}</h3>
      {findings.length === 0 ? (
        <p className="card__muted">{t('empty', language)}</p>
      ) : (
        <ul className="list">
          {findings.map((finding) => (
            <li key={finding.id}>
              <strong>{finding.title}</strong>{
              ' ' + finding.severity + ' · ' + finding.analysisConfidence.toFixed(2)
              }
              <p className={SEVERITY_CLASS[finding.severity]}>{finding.explanation}</p>
              <p className="card__muted">{finding.recommendation}</p>
              {finding.sourceCandidateIds.length > 0 ? (
                <div className="form">
                  <span className="card__muted">{t('sourceCandidates', language)}</span>
                  <button
                    type="button"
                    className="button button--inline"
                    onClick={() => requestSourceCandidate(finding.sourceCandidateIds)}
                  >
                    {finding.sourceCandidateIds.length === 1 ? t('openSource', language) : t('selectSource', language)}
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      <h3 className="card__title">{t('history', language)}</h3>
      <ul className="list">
        {sessions.map((session) => (
          <li key={session.id}>
            <button
              type="button"
              className="button"
              onClick={() => void window.aps.loadAiFindings(session.id).then((loaded) => { setFindings(loaded); setFindingSessionId(session.id); })}
            >
              {session.createdAt + ' · ' + session.status + (session.model === null ? '' : ' · ' + session.model)}
            </button>
            {session.errorMessage !== null ? <span className="card__error"> {session.errorMessage}</span> : null}
          </li>
        ))}
      </ul>

      {error !== null ? <p className="card__error">{error}</p> : null}

      {sourceCandidateChoices.length === 0 ? null : (
        <div className="ai-source-dialog-backdrop" role="presentation">
          <section className="ai-source-dialog" role="dialog" aria-modal="true" aria-label={t('selectSourceCandidate', language)}>
            <h4>{t('selectSourceCandidate', language)}</h4>
            <div className="ai-source-dialog__choices">
              {sourceCandidateChoices.map((candidateId, index) => (
                <button
                  key={candidateId}
                  type="button"
                  className="button"
                  onClick={() => {
                    setSourceCandidateChoices([]);
                    openSourceCandidate(candidateId);
                  }}
                >
                  {t('sourceCandidate', language) + ' ' + String(index + 1) + ' · ' + candidateId.slice(0, 12)}
                </button>
              ))}
            </div>
            <button type="button" className="button button--inline" onClick={() => setSourceCandidateChoices([])}>
              {t('cancel', language)}
            </button>
          </section>
        </div>
      )}
    </section>
  );
}
