import { useEffect, useState, type JSX } from 'react';
import { translate, type UiLanguage } from '../../../shared/i18n';
import type { AiSettingsSnapshot } from '../../../shared/ipc';
import { SettingsField, SettingsSection } from './controls';

export interface AiSettingsPageProps {
  readonly language: UiLanguage;
  readonly onOpenAnalysis: () => void;
}

/**
 * The AI Settings page: credential, model and endpoint. The AI Analysis page
 * reads what is saved here, so the key is never echoed back to the field.
 */
export function AiSettingsPage({ language, onOpenAnalysis }: AiSettingsPageProps): JSX.Element {
  const [snapshot, setSnapshot] = useState<AiSettingsSnapshot | null>(null);
  const [apiKey, setApiKey] = useState('');
  const [model, setModel] = useState('');
  const [endpoint, setEndpoint] = useState('');
  const [models, setModels] = useState<readonly string[]>([]);
  const [working, setWorking] = useState(false);
  const [modelsError, setModelsError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    window.aps
      .getAiSettings()
      .then((loaded) => {
        setSnapshot(loaded);
        setModel(loaded.model);
        setEndpoint(loaded.endpoint);
      })
      .catch((reason: unknown) => setSaveError(reason instanceof Error ? reason.message : String(reason)));
  }, []);

  const configured = snapshot?.configured === true;
  const run = (body: () => Promise<void>): void => {
    setWorking(true);
    setSaveError(null);
    setSaved(false);
    body()
      .catch((reason: unknown) => setSaveError(reason instanceof Error ? reason.message : String(reason)))
      .finally(() => setWorking(false));
  };

  return (
    <>
      <SettingsSection
        title={translate('settings.aiKey', language)}
        description={
          snapshot === null
            ? undefined
            : configured
              ? translate('settings.aiKeyConfigured', language) +
                (snapshot.persistent ? '' : ' · ' + translate('settings.aiKeyMemoryOnly', language))
              : translate('settings.aiKeyMissing', language)
        }
      >
        <SettingsField label={translate('settings.aiKey', language)}>
          <input
            type="password"
            value={apiKey}
            spellCheck={false}
            placeholder="sk-…"
            onChange={(event) => setApiKey(event.target.value)}
          />
        </SettingsField>
        <div className="settings__actions">
          <button
            type="button"
            className="button"
            disabled={working || apiKey.trim().length === 0}
            onClick={() =>
              run(async () => {
                setSnapshot(await window.aps.saveAiCredential(apiKey));
                setApiKey('');
                setSaved(true);
              })
            }
          >
            {translate('settings.aiSave', language)}
          </button>
          <button
            type="button"
            className="button"
            disabled={working || !configured}
            onClick={() =>
              run(async () => {
                setSnapshot(await window.aps.clearAiCredential());
              })
            }
          >
            {translate('settings.aiRemove', language)}
          </button>
        </div>
      </SettingsSection>

      <SettingsSection
        title={translate('settings.aiModel', language)}
        description={translate('settings.aiHint', language)}
      >
        <SettingsField label={translate('settings.aiModel', language)}>
          <input value={model} spellCheck={false} onChange={(event) => setModel(event.target.value)} />
        </SettingsField>
        <SettingsField label={translate('settings.aiEndpoint', language)}>
          <input value={endpoint} spellCheck={false} onChange={(event) => setEndpoint(event.target.value)} />
        </SettingsField>
        <div className="settings__actions">
          <button
            type="button"
            className="button"
            disabled={working || !configured}
            onClick={() =>
              run(async () => {
                setModelsError(null);
                try {
                  setModels(await window.aps.listAiModels());
                } catch (reason) {
                  setModelsError(reason instanceof Error ? reason.message : String(reason));
                }
              })
            }
          >
            {translate('settings.aiRefreshModels', language)}
          </button>
          <button
            type="button"
            className="button button--primary"
            disabled={working || model.trim().length === 0 || endpoint.trim().length === 0}
            onClick={() =>
              run(async () => {
                if (apiKey.trim().length > 0) await window.aps.saveAiCredential(apiKey);
                setSnapshot(await window.aps.saveAiConfiguration({ model, endpoint }));
                setApiKey('');
                setSaved(true);
              })
            }
          >
            {translate('settings.aiSave', language)}
          </button>
          <button type="button" className="button" onClick={onOpenAnalysis}>
            {translate('settings.aiOpenAnalysis', language)}
          </button>
          {working ? <span className="settings__section-note">{translate('settings.aiWorking', language)}</span> : null}
          {saved && !working ? (
            <span className="settings__section-note">{translate('settings.aiSaved', language)}</span>
          ) : null}
        </div>
        {modelsError === null ? null : (
          <p className="settings__error">
            {translate('settings.aiModelsFailed', language) + modelsError}
          </p>
        )}
        {models.length === 0 ? null : (
          <SettingsField label={translate('settings.aiChooseModel', language)}>
            <select value={models.includes(model) ? model : ''} onChange={(event) => setModel(event.target.value)}>
              <option value="" disabled>
                {translate('settings.aiChooseModel', language)}
              </option>
              {models.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </select>
          </SettingsField>
        )}
      </SettingsSection>

      {saveError === null ? null : <p className="settings__error">{translate('settings.aiSaveFailed', language) + saveError}</p>}
    </>
  );
}
