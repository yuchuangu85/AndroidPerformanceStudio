package com.androidperformancestudio.desktop

import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource

internal fun languagePreferenceLabel(
    preference: ApplicationLanguagePreference,
    language: UiLanguage,
): String =
    when (preference) {
        ApplicationLanguagePreference.SYSTEM -> localizedStringResource(Res.string.system, language)
        ApplicationLanguagePreference.SIMPLIFIED_CHINESE -> localizedStringResource(Res.string.simplified_chinese, language)
        ApplicationLanguagePreference.ENGLISH -> localizedStringResource(Res.string.english, language)
    }

internal fun themePreferenceLabel(
    preference: ApplicationThemePreference,
    language: UiLanguage,
): String =
    when (preference) {
        ApplicationThemePreference.SYSTEM -> localizedStringResource(Res.string.system, language)
        ApplicationThemePreference.LIGHT -> localizedStringResource(Res.string.light, language)
        ApplicationThemePreference.DARK -> localizedStringResource(Res.string.dark, language)
    }
