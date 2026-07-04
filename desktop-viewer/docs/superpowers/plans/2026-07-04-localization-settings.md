# Localization Settings Implementation Plan

1. Add failing tests for language preference resolution, persistence, and localized strings.
2. Add `LanguagePreference`, `ViewerLanguage`, `ViewerStrings`, and a composition local.
3. Add structured finding arguments so known analyzer messages can be rendered in either language.
4. Pass localized strings through presenters and replace hardcoded UI text.
5. Extend the settings dialog with a language section and immediate persisted selection.
6. Run desktop and full-project tests, then restart the application.
