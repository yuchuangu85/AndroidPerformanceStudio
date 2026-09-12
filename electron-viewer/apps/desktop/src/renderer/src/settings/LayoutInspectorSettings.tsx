import { useEffect, useState, type JSX } from 'react';
import { translate, type ShellStringKey, type UiLanguage } from '../../../shared/i18n';
import {
  CANVAS_BORDER_COLOR_PRESETS,
  DEFAULT_CANVAS_BORDER_COLORS,
  parseArgbColor,
  type ApplicationUiSettings,
  type ApplicationUiSettingsPatch,
  type CanvasHitTestOrderPreference,
} from '../../../shared/settings-contract';
import { argbToCss } from '../layout-inspector/canvas';
import { SettingsChoice, SettingsSection, SettingsToggle } from './controls';

export interface LayoutInspectorSettingsPageProps {
  readonly language: UiLanguage;
  readonly settings: ApplicationUiSettings;
  readonly onPatch: (patch: ApplicationUiSettingsPatch) => void;
}

type BooleanLayoutField =
  | 'hideInvisibleHierarchyViews'
  | 'hideInvisibleFindings'
  | 'hideHierarchyIndices'
  | 'showHierarchyIds'
  | 'showHierarchyLayerVisibilityButtons'
  | 'showVisibleViewBounds';

const TOGGLE_KEYS: readonly (readonly [BooleanLayoutField, ShellStringKey])[] = [
  ['hideInvisibleHierarchyViews', 'settings.hideInvisibleHierarchyViews'],
  ['hideInvisibleFindings', 'settings.hideInvisibleFindings'],
  ['hideHierarchyIndices', 'settings.hideHierarchyIndices'],
  ['showHierarchyIds', 'settings.showHierarchyIds'],
  ['showHierarchyLayerVisibilityButtons', 'settings.showHierarchyLayerVisibilityButtons'],
  ['showVisibleViewBounds', 'settings.showVisibleViewBounds'],
];

export function LayoutInspectorSettingsPage({
  language,
  settings,
  onPatch,
}: LayoutInspectorSettingsPageProps): JSX.Element {
  const view = settings.layoutInspector;
  const toggle = (field: BooleanLayoutField, checked: boolean): void => {
    const update: Partial<Record<BooleanLayoutField, boolean>> = { [field]: checked };
    onPatch({ layoutInspector: update });
  };
  return (
    <>
      <SettingsSection title={translate('settings.viewAndHierarchy', language)}>
        {TOGGLE_KEYS.map(([field, key]) => (
          <SettingsToggle
            key={field}
            label={translate(key, language)}
            checked={view[field] === true}
            onChange={(checked) => toggle(field, checked)}
          />
        ))}
        <SettingsChoice
          label={translate('settings.canvasHitTestOrder', language)}
          value={view.canvasHitTestOrder}
          options={(['smallest-area', 'z-order'] as const).map((option) => ({
            value: option,
            label: translate(
              option === 'z-order' ? 'settings.zOrder' : 'settings.smallestAreaFirst',
              language,
            ),
          }))}
          onChange={(value: CanvasHitTestOrderPreference) =>
            onPatch({ layoutInspector: { canvasHitTestOrder: value } })
          }
        />
      </SettingsSection>

      <SettingsSection title={translate('settings.canvasBorderColors', language)}>
        {(
          [
            ['normal', 'settings.normal'],
            ['hovered', 'settings.hovered'],
            ['selected', 'settings.selected'],
          ] as const
        ).map(([field, key]) => (
          <ColorSetting
            key={field}
            label={translate(key, language)}
            resetLabel={translate('settings.reset', language)}
            value={view.canvasBorderColors[field]}
            fallback={DEFAULT_CANVAS_BORDER_COLORS[field]}
            onChange={(color) =>
              onPatch({ layoutInspector: { canvasBorderColors: { [field]: color } } })
            }
          />
        ))}
      </SettingsSection>

      <SettingsSection title={translate('settings.captureArchive', language)}>
        <p className="settings__section-note">{translate('settings.captureArchiveUnavailable', language)}</p>
      </SettingsSection>
    </>
  );
}

interface ColorSettingProps {
  readonly label: string;
  readonly resetLabel: string;
  readonly value: string;
  readonly fallback: string;
  readonly onChange: (color: string) => void;
}

/** Swatch, hex field, reset and the reference's six presets. */
function ColorSetting({ label, resetLabel, value, fallback, onChange }: ColorSettingProps): JSX.Element {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  const commit = (text: string): void => {
    setDraft(text);
    const parsed = parseArgbColor(text);
    if (parsed !== undefined) onChange(parsed);
  };
  return (
    <div className="settings__color">
      <span className="settings__row-label">{label}</span>
      <span className="settings__swatch" style={{ background: argbToCss(value) }} aria-hidden="true" />
      <input
        className="settings__color-input"
        value={draft}
        spellCheck={false}
        aria-label={label}
        onChange={(event) => commit(event.target.value)}
      />
      <button
        type="button"
        className="button"
        onClick={() => {
          setDraft(fallback);
          onChange(fallback);
        }}
      >
        {resetLabel}
      </button>
      <span className="settings__presets">
        {CANVAS_BORDER_COLOR_PRESETS.map((preset) => (
          <button
            key={preset}
            type="button"
            className="settings__preset"
            style={{ background: argbToCss(preset) }}
            aria-label={preset}
            title={preset}
            onClick={() => {
              setDraft(preset);
              onChange(preset);
            }}
          />
        ))}
      </span>
    </div>
  );
}
