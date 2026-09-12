import type { JSX, ReactNode } from 'react';

export interface SettingsSectionProps {
  readonly title: string;
  readonly description?: string;
  readonly children: ReactNode;
}

/** One titled group of settings, the shape the reference's SettingsSection has. */
export function SettingsSection({ title, description, children }: SettingsSectionProps): JSX.Element {
  return (
    <section className="settings__section">
      <h3 className="settings__section-title">{title}</h3>
      {description === undefined ? null : <p className="settings__section-note">{description}</p>}
      <div className="settings__section-body">{children}</div>
    </section>
  );
}

export interface SettingsToggleProps {
  readonly label: string;
  readonly checked: boolean;
  readonly onChange: (checked: boolean) => void;
  readonly disabled?: boolean;
}

/** A labelled macOS-style switch row. */
export function SettingsToggle({ label, checked, onChange, disabled }: SettingsToggleProps): JSX.Element {
  return (
    <div className="settings__row">
      <span className="settings__row-label">{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        disabled={disabled === true}
        className={checked ? 'switch switch--on' : 'switch'}
        onClick={() => onChange(!checked)}
      >
        <span className="switch__knob" />
      </button>
    </div>
  );
}

export interface SettingsChoiceOption<T extends string> {
  readonly value: T;
  readonly label: string;
  readonly description?: string;
}

export interface SettingsChoiceProps<T extends string> {
  readonly label: string;
  readonly options: readonly SettingsChoiceOption<T>[];
  readonly value: T;
  readonly onChange: (value: T) => void;
  /** Values the Electron build cannot honour yet; shown with a note. */
  readonly unavailable?: readonly T[];
  readonly unavailableNote?: string;
}

/** A labelled list of exclusive choices, like MacOSChoiceChip stacks. */
export function SettingsChoice<T extends string>({
  label,
  options,
  value,
  onChange,
  unavailable,
  unavailableNote,
}: SettingsChoiceProps<T>): JSX.Element {
  return (
    <div className="settings__choice-group">
      <span className="settings__row-label">{label}</span>
      <div className="settings__choices">
        {options.map((option) => {
          const disabled = unavailable?.includes(option.value) === true;
          return (
            <button
              key={option.value}
              type="button"
              className={option.value === value ? 'settings__choice settings__choice--selected' : 'settings__choice'}
              aria-pressed={option.value === value}
              disabled={disabled}
              title={disabled ? unavailableNote : undefined}
              onClick={() => onChange(option.value)}
            >
              <span className="settings__choice-label">{option.label}</span>
              {option.description === undefined ? null : (
                <span className="settings__choice-note">{option.description}</span>
              )}
              {disabled ? <span className="settings__choice-note">{unavailableNote}</span> : null}
            </button>
          );
        })}
      </div>
    </div>
  );
}

export interface SettingsFieldProps {
  readonly label: string;
  readonly hint?: string;
  readonly children: ReactNode;
}

export function SettingsField({ label, hint, children }: SettingsFieldProps): JSX.Element {
  return (
    <label className="settings__field">
      <span className="settings__row-label">{label}</span>
      {children}
      {hint === undefined ? null : <span className="settings__field-hint">{hint}</span>}
    </label>
  );
}
