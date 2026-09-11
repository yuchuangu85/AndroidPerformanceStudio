import { useCallback, useEffect, useState, type JSX } from 'react';
import type { UiLanguage } from '../../shared/i18n';
import type { SourceBackendWorkspace } from '../../shared/ipc';

const STRINGS = {
  title: { en: 'Shared workspace database', zh: '共享工作区数据库' },
  hint: {
    en: 'Workspaces in source-workspaces.db, the file the previous desktop app writes.',
    zh: '存放在 source-workspaces.db 中的工作区，与旧桌面应用同一个文件。',
  },
  refresh: { en: 'Refresh', zh: '刷新' },
  empty: { en: 'No workspace in the shared database yet', zh: '共享数据库里还没有工作区' },
  upload: { en: 'Allow AI source upload', zh: '允许上传源码给 AI' },
  uploadHint: {
    en: 'Off by default. Analysis without it cites no file or line.',
    zh: '默认关闭。关闭时分析结果不会引用文件与行号。',
  },
  files: { en: 'files', zh: '文件' },
  symbols: { en: 'symbols', zh: '符号' },
} as const;

type StringKey = keyof typeof STRINGS;

function t(key: StringKey, language: UiLanguage): string {
  return STRINGS[key][language];
}

export function SourceBackendPanel({ language }: { readonly language: UiLanguage }): JSX.Element {
  const [workspaces, setWorkspaces] = useState<readonly SourceBackendWorkspace[]>([]);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    window.aps
      .listBackendSourceWorkspaces()
      .then(setWorkspaces)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : String(reason)));
  }, []);

  useEffect(reload, [reload]);

  return (
    <section className="card">
      <h3 className="card__title">{t('title', language)}</h3>
      <p className="card__muted">{t('hint', language)}</p>
      <button type="button" className="button" onClick={reload}>
        {t('refresh', language)}
      </button>
      {workspaces.length === 0 ? (
        <p className="card__muted">{t('empty', language)}</p>
      ) : (
        <ul className="list">
          {workspaces.map((workspace) => (
            <li key={workspace.id}>
              <strong>{workspace.displayName}</strong>{
              ' · ' + workspace.providerKind + ' · ' + workspace.phase
              }
              <p className="card__muted">
                {String(workspace.fileCount) + ' ' + t('files', language) + ' · ' +
                  String(workspace.symbolCount) + ' ' + t('symbols', language) +
                  (workspace.revision === undefined ? '' : ' · ' + workspace.revision)}
              </p>
              <label className="field">
                <input
                  type="checkbox"
                  checked={workspace.allowAiSourceUpload}
                  onChange={(event) => {
                    const allowed = event.target.checked;
                    void window.aps
                      .setBackendSourceAiUpload({ workspaceId: workspace.id, allowed })
                      .then(() =>
                        setWorkspaces((current) =>
                          current.map((entry) =>
                            entry.id === workspace.id ? { ...entry, allowAiSourceUpload: allowed } : entry,
                          ),
                        ),
                      )
                      .catch((reason: unknown) =>
                        setError(reason instanceof Error ? reason.message : String(reason)),
                      );
                  }}
                />
                <span>{t('upload', language)}</span>
              </label>
              <p className="card__muted">{t('uploadHint', language)}</p>
            </li>
          ))}
        </ul>
      )}
      {error !== null ? <p className="card__error">{error}</p> : null}
    </section>
  );
}
