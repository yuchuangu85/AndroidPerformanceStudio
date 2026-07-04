# Localization Settings Design

## Goal

Support English and Simplified Chinese across the desktop viewer, with a persisted language selector that defaults to the operating-system language.

## Preferences

- Follow system (default)
- 简体中文
- English

Changing the preference updates the current UI immediately. The stored value is independent from the theme preference.

## Localization Scope

- Header status, operation menu, shortcuts, and scan control
- Hierarchy, canvas, properties, and findings panels
- Empty/loading states and metrics
- Settings, theme names, and language names
- Finding titles and known analyzer messages
- Layout Inspector detail sections, field labels, and rendering-risk descriptions

Unknown analyzer findings and external error messages remain unchanged as safe fallbacks.

## Verification

- Unit tests cover defaulting, locale resolution, persistence, English/Chinese strings, menu labels, and finding messages.
- Presenter tests verify language-specific output.
- Desktop and full-project tests pass.
