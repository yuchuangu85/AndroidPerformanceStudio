import { useEffect, useState, type JSX } from 'react';
import { translate, type ShellStringKey, type UiLanguage } from '../../../shared/i18n';
import {
  SAMPLING_TEMPLATES,
  samplingTemplateDefaults,
  type ApplicationUiSettings,
  type ApplicationUiSettingsPatch,
  type SimpleperfCallGraphPreference,
  type SimpleperfCaptureDefaults,
  type SimpleperfScopePreference,
  type SimpleperfTargetPreference,
} from '../../../shared/settings-contract';
import { SettingsChoice, SettingsField, SettingsSection } from './controls';

/** The reference's CaptureSettingsSection, in sidebar order. */
export type SimpleperfSectionId =
  | 'SAMPLING_TEMPLATE'
  | 'CAPTURE_CONFIGURATION'
  | 'ADVANCED_PARAMETERS'
  | 'FLAME_GRAPH'
  | 'SIMPLEPERF_ENGINE'
  | 'USER_GUIDE';

export const SIMPLEPERF_SECTIONS: readonly SimpleperfSectionId[] = [
  'SAMPLING_TEMPLATE',
  'CAPTURE_CONFIGURATION',
  'ADVANCED_PARAMETERS',
  'FLAME_GRAPH',
  'SIMPLEPERF_ENGINE',
  'USER_GUIDE',
];

export const SIMPLEPERF_SECTION_KEYS: Record<SimpleperfSectionId, ShellStringKey> = {
  SAMPLING_TEMPLATE: 'settings.samplingTemplate',
  CAPTURE_CONFIGURATION: 'settings.captureConfiguration',
  ADVANCED_PARAMETERS: 'settings.advancedParameters',
  FLAME_GRAPH: 'settings.flameGraph',
  SIMPLEPERF_ENGINE: 'settings.simpleperfEngine',
  USER_GUIDE: 'settings.userGuide',
};

const TEMPLATE_NAME_KEYS: Record<SimpleperfCaptureDefaults['template'], ShellStringKey> = {
  APP_CPU_BASIC: 'settings.template.APP_CPU_BASIC',
  UI_THREAD_FOCUS: 'settings.template.UI_THREAD_FOCUS',
  NATIVE_HOTSPOT: 'settings.template.NATIVE_HOTSPOT',
  LOW_OVERHEAD: 'settings.template.LOW_OVERHEAD',
  SYSTEM_PROCESS: 'settings.template.SYSTEM_PROCESS',
};

const TEMPLATE_DESCRIPTION_KEYS: Record<SimpleperfCaptureDefaults['template'], ShellStringKey> = {
  APP_CPU_BASIC: 'settings.template.APP_CPU_BASIC.description',
  UI_THREAD_FOCUS: 'settings.template.UI_THREAD_FOCUS.description',
  NATIVE_HOTSPOT: 'settings.template.NATIVE_HOTSPOT.description',
  LOW_OVERHEAD: 'settings.template.LOW_OVERHEAD.description',
  SYSTEM_PROCESS: 'settings.template.SYSTEM_PROCESS.description',
};

/** The events the Simpleperf capture form offers by name. */
const KNOWN_EVENTS: readonly string[] = ['cpu-clock', 'cpu-cycles', 'task-clock'];

export interface SimpleperfSettingsPageProps {
  readonly language: UiLanguage;
  readonly settings: ApplicationUiSettings;
  readonly section: SimpleperfSectionId;
  readonly onPatch: (patch: ApplicationUiSettingsPatch) => void;
}

export function SimpleperfSettingsPage({
  language,
  settings,
  section,
  onPatch,
}: SimpleperfSettingsPageProps): JSX.Element {
  const defaults = settings.simpleperf.captureDefaults;
  const patchDefaults = (captureDefaults: Partial<SimpleperfCaptureDefaults>): void => {
    onPatch({ simpleperf: { captureDefaults } });
  };

  switch (section) {
    case 'SAMPLING_TEMPLATE':
      return (
        <SettingsSection
          title={translate('settings.samplingTemplate', language)}
          description={translate('settings.samplingTemplateHint', language)}
        >
          <SettingsChoice
            label={translate('settings.samplingTemplate', language)}
            value={defaults.template}
            options={SAMPLING_TEMPLATES.map((template) => ({
              value: template,
              label: translate(TEMPLATE_NAME_KEYS[template], language),
              description: translate(TEMPLATE_DESCRIPTION_KEYS[template], language),
            }))}
            onChange={(template) =>
              patchDefaults(samplingTemplateDefaults(template, defaults.target))
            }
          />
        </SettingsSection>
      );

    case 'CAPTURE_CONFIGURATION':
      return (
        <SettingsSection
          title={translate('settings.captureConfiguration', language)}
          description={translate('settings.captureConfigurationHint', language)}
        >
          <SettingsChoice
            label={translate('cpu.target', language)}
            value={defaults.target}
            options={(['APP', 'SYSTEM_WIDE'] as const).map((target) => ({
              value: target,
              label: translate(target === 'APP' ? 'cpu.target.app' : 'cpu.target.system', language),
            }))}
            onChange={(target: SimpleperfTargetPreference) =>
              patchDefaults({
                target,
                scope: target === 'APP' ? 'USER' : 'BOTH',
              })
            }
          />
          <SettingsField label={translate('cpu.event', language)}>
            <select value={defaults.event} onChange={(event) => patchDefaults({ event: event.target.value })}>
              {(KNOWN_EVENTS.includes(defaults.event) ? KNOWN_EVENTS : [...KNOWN_EVENTS, defaults.event]).map(
                (event) => (
                  <option key={event} value={event}>
                    {event}
                  </option>
                ),
              )}
            </select>
          </SettingsField>
          <NumberField
            label={translate('cpu.frequency', language)}
            value={defaults.frequencyHertz}
            minimum={1}
            maximum={100000}
            onCommit={(frequencyHertz) => patchDefaults({ frequencyHertz })}
          />
          <NumberField
            label={translate('cpu.duration', language)}
            value={defaults.durationSeconds}
            minimum={1}
            maximum={3600}
            onCommit={(durationSeconds) => patchDefaults({ durationSeconds })}
          />
          <SettingsChoice
            label={translate('cpu.callGraph', language)}
            value={defaults.callGraph}
            options={(['DWARF', 'FRAME_POINTER', 'NONE'] as const).map((option) => ({
              value: option,
              label: translate(
                option === 'DWARF'
                  ? 'settings.callGraph.dwarf'
                  : option === 'FRAME_POINTER'
                    ? 'settings.callGraph.framePointer'
                    : 'settings.callGraph.none',
                language,
              ),
            }))}
            onChange={(callGraph: SimpleperfCallGraphPreference) => patchDefaults({ callGraph })}
          />
          <SettingsChoice
            label={translate('cpu.scope', language)}
            value={defaults.scope}
            options={(['BOTH', 'USER', 'KERNEL'] as const).map((option) => ({
              value: option,
              label: translate(
                option === 'BOTH'
                  ? 'settings.scope.both'
                  : option === 'USER'
                    ? 'settings.scope.user'
                    : 'settings.scope.kernel',
                language,
              ),
            }))}
            onChange={(scope: SimpleperfScopePreference) => patchDefaults({ scope })}
          />
        </SettingsSection>
      );

    case 'ADVANCED_PARAMETERS':
      return (
        <SettingsSection title={translate('settings.advancedParameters', language)}>
          <p className="settings__section-note">{translate('settings.advancedParametersUnavailable', language)}</p>
        </SettingsSection>
      );

    case 'FLAME_GRAPH':
      return (
        <SettingsSection
          title={translate('settings.frameInformationBox', language)}
          description={translate('settings.frameInformationBehavior', language)}
        >
          <SettingsChoice
            label={translate('settings.flameGraph', language)}
            value={settings.simpleperf.flameTooltipMode}
            options={(['follow-mouse', 'fixed'] as const).map((option) => ({
              value: option,
              label: translate(option === 'fixed' ? 'settings.fixed' : 'settings.followMouse', language),
            }))}
            onChange={(flameTooltipMode) => onPatch({ simpleperf: { flameTooltipMode } })}
          />
        </SettingsSection>
      );

    case 'SIMPLEPERF_ENGINE':
      return (
        <SettingsSection
          title={translate('settings.simpleperfEngine', language)}
          description={translate('settings.engineDescription', language)}
        >
          <SettingsChoice<'local' | 'firefox-local' | 'firefox'>
            label={translate('settings.simpleperfEngine', language)}
            value="local"
            options={[
              { value: 'local', label: translate('settings.engineLocal', language) },
              { value: 'firefox-local', label: translate('settings.engineFirefoxLocal', language) },
              { value: 'firefox', label: translate('settings.engineFirefox', language) },
            ]}
            unavailable={['firefox-local', 'firefox']}
            unavailableNote={translate('settings.notMigrated', language)}
            onChange={() => undefined}
          />
        </SettingsSection>
      );

    case 'USER_GUIDE':
      return (
        <SettingsSection title={translate('settings.userGuide', language)}>
          <p className="settings__section-note">{translate('settings.userGuideUnavailable', language)}</p>
        </SettingsSection>
      );
  }
}

interface NumberFieldProps {
  readonly label: string;
  readonly value: number;
  readonly minimum: number;
  readonly maximum: number;
  readonly onCommit: (value: number) => void;
}

/**
 * Number fields commit on blur or Enter: one stored write per edit, and a
 * half-typed "1" never lands as the frequency.
 */
function NumberField({ label, value, minimum, maximum, onCommit }: NumberFieldProps): JSX.Element {
  const [draft, setDraft] = useState(String(value));
  useEffect(() => setDraft(String(value)), [value]);
  const commit = (): void => {
    const parsed = Number(draft);
    if (!Number.isFinite(parsed)) {
      setDraft(String(value));
      return;
    }
    const clamped = Math.min(maximum, Math.max(minimum, Math.round(parsed)));
    setDraft(String(clamped));
    if (clamped !== value) onCommit(clamped);
  };
  return (
    <SettingsField label={label}>
      <input
        type="number"
        min={minimum}
        max={maximum}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') commit();
        }}
      />
    </SettingsField>
  );
}
