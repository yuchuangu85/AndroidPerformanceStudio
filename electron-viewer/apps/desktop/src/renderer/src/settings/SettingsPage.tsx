import { useCallback, useState, type JSX } from 'react';
import { translate, type UiLanguage } from '../../../shared/i18n';
import type { AppDestination } from '../../../shared/destinations';
import type {
  ApplicationUiSettings,
  ApplicationUiSettingsPatch,
} from '../../../shared/settings-contract';
import type { AppInfo } from '../../../shared/ipc';
import { AboutSettingsPage } from './AboutSettings';
import { AiSettingsPage } from './AiSettings';
import { GeneralSettings } from './GeneralSettings';
import { LayoutInspectorSettingsPage } from './LayoutInspectorSettings';
import {
  SIMPLEPERF_SECTIONS,
  SIMPLEPERF_SECTION_KEYS,
  SimpleperfSettingsPage,
  type SimpleperfSectionId,
} from './SimpleperfSettings';

/** SettingsPage: General, Layout Inspector, Simpleperf, AI, About. */
export type SettingsPageId = 'GENERAL' | 'LAYOUT_INSPECTOR' | 'SIMPLEPERF' | 'AI' | 'ABOUT';

export interface SettingsPageProps {
  readonly language: UiLanguage;
  readonly settings: ApplicationUiSettings;
  readonly appInfo: AppInfo;
  /** Resolves true when the patch reached settings.json. */
  readonly onPatch: (patch: ApplicationUiSettingsPatch) => Promise<boolean>;
  readonly onClose: () => void;
  readonly onOpenDestination: (destination: AppDestination) => void;
}

export function SettingsPage({
  language,
  settings,
  appInfo,
  onPatch,
  onClose,
  onOpenDestination,
}: SettingsPageProps): JSX.Element {
  const [page, setPage] = useState<SettingsPageId>('GENERAL');
  const [simpleperfExpanded, setSimpleperfExpanded] = useState(false);
  const [simpleperfSection, setSimpleperfSection] = useState<SimpleperfSectionId>('SAMPLING_TEMPLATE');
  const [saveFailed, setSaveFailed] = useState(false);

  // Every control patches one section; the page reports a failed write once
  // instead of each control carrying its own error line.
  const patch = useCallback(
    (update: ApplicationUiSettingsPatch): void => {
      void onPatch(update).then((saved) => setSaveFailed(!saved));
    },
    [onPatch],
  );

  const selectSimpleperfSection = (section: SimpleperfSectionId): void => {
    setSimpleperfSection(section);
    setSimpleperfExpanded(true);
    setPage('SIMPLEPERF');
  };

  return (
    <main className="content">
      <header className="toolbar">
        <div className="toolbar__leading">
          <h2 className="toolbar__title">{translate('settings.title', language)}</h2>
          <span className="toolbar__subtitle">{appInfo.name}</span>
        </div>
        <div className="toolbar__actions">
          <button type="button" className="button button--primary" onClick={onClose}>
            {translate('settings.done', language)}
          </button>
        </div>
      </header>
      <div className="content__body content__body--settings">
        <nav className="settings__nav" aria-label={translate('settings.title', language)}>
          <SettingsNavRow
            label={translate('settings.general', language)}
            selected={page === 'GENERAL'}
            onClick={() => setPage('GENERAL')}
          />
          <SettingsNavRow
            label={translate('settings.layoutInspector', language)}
            selected={page === 'LAYOUT_INSPECTOR'}
            onClick={() => setPage('LAYOUT_INSPECTOR')}
          />
          <SettingsNavRow
            label={translate('settings.simpleperf', language)}
            selected={page === 'SIMPLEPERF' && !simpleperfExpanded}
            expanded={simpleperfExpanded}
            onClick={() => {
              if (page === 'SIMPLEPERF') setSimpleperfExpanded((current) => !current);
              else {
                setPage('SIMPLEPERF');
                setSimpleperfExpanded(true);
              }
            }}
          />
          {simpleperfExpanded
            ? SIMPLEPERF_SECTIONS.map((section) => (
                <SettingsNavRow
                  key={section}
                  label={translate(SIMPLEPERF_SECTION_KEYS[section], language)}
                  nested={true}
                  selected={page === 'SIMPLEPERF' && section === simpleperfSection}
                  onClick={() => selectSimpleperfSection(section)}
                />
              ))
            : null}
          <SettingsNavRow
            label={translate('settings.ai', language)}
            selected={page === 'AI'}
            onClick={() => setPage('AI')}
          />
          <SettingsNavRow
            label={translate('settings.about', language)}
            selected={page === 'ABOUT'}
            onClick={() => setPage('ABOUT')}
          />
        </nav>
        <div className="settings__panes">
          {saveFailed ? <p className="settings__error">{translate('settings.saveFailed', language)}</p> : null}
          {page === 'GENERAL' ? (
            <GeneralSettings language={language} settings={settings} onPatch={patch} />
          ) : null}
          {page === 'LAYOUT_INSPECTOR' ? (
            <LayoutInspectorSettingsPage language={language} settings={settings} onPatch={patch} />
          ) : null}
          {page === 'SIMPLEPERF' ? (
            <SimpleperfSettingsPage
              language={language}
              settings={settings}
              section={simpleperfSection}
              onPatch={patch}
            />
          ) : null}
          {page === 'AI' ? (
            <AiSettingsPage language={language} onOpenAnalysis={() => onOpenDestination('AI_ANALYSIS')} />
          ) : null}
          {page === 'ABOUT' ? <AboutSettingsPage language={language} appInfo={appInfo} /> : null}
        </div>
      </div>
    </main>
  );
}

interface SettingsNavRowProps {
  readonly label: string;
  readonly selected: boolean;
  readonly nested?: boolean;
  readonly expanded?: boolean;
  readonly onClick: () => void;
}

function SettingsNavRow({ label, selected, nested, expanded, onClick }: SettingsNavRowProps): JSX.Element {
  const className =
    'settings__nav-row' +
    (nested === true ? ' settings__nav-row--nested' : '') +
    (selected ? ' settings__nav-row--selected' : '');
  return (
    <button type="button" className={className} aria-current={selected ? 'page' : undefined} onClick={onClick}>
      {expanded === undefined ? null : (
        <span className="settings__nav-disclosure" aria-hidden="true">
          {expanded ? '▾' : '▸'}
        </span>
      )}
      <span className="settings__nav-label">{label}</span>
    </button>
  );
}
