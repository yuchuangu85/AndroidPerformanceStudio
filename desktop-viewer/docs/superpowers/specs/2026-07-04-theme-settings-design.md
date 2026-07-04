# Theme Settings Design

## Goal

Add an application settings entry and a settings interface whose first option
controls the complete desktop viewer theme.

## Entry and Interface

- A drawn gear icon appears at the far right of the top status bar.
- Clicking it opens a modal settings panel.
- The initial settings group is `主题`.
- The available choices are `跟随系统`, `亮色主题`, and `暗色主题`.
- The selected choice applies immediately; the panel can be dismissed by its
  close action or by clicking outside.

## Theme Behavior

- The default choice is `跟随系统`.
- `跟随系统` resolves through Compose `isSystemInDarkTheme()`.
- The selected preference is persisted with Java Preferences and restored at
  the next launch.
- Invalid or unavailable stored values safely fall back to `跟随系统`.

## Visual Scope

The theme controls the full viewer, including the status bar, hierarchy,
canvas background, properties, findings, splitters, labels, selection states,
section headers, switches, and settings panel. Severity colors retain their
semantic blue, orange, and red identities.

## Structure

- `ThemePreference.kt`: pure preference model, resolution, and persistence.
- `ViewerTheme.kt`: light/dark palettes, Material 3 color schemes, and
  composition-local colors.
- `ThemeSettingsDialog.kt`: modal theme selection interface.
- `DesktopViewerApp.kt`: preference state, settings entry, and palette usage.

## Verification

- Unit tests cover default selection, invalid-value fallback, light/dark/system
  resolution, and persistence.
- Existing desktop and full-project tests remain green.
- The desktop application is restarted and monitored for runtime errors.
