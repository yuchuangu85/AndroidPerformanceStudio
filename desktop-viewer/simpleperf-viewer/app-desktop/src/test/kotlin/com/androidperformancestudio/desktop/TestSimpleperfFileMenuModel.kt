package com.androidperformancestudio.desktop

import com.androidperformancestudio.app_desktop.generated.resources.Res
import com.androidperformancestudio.app_desktop.generated.resources.*
import androidx.compose.ui.input.key.Key
import java.nio.file.Path
import java.util.Locale
import org.jetbrains.compose.resources.getString

internal suspend fun testSimpleperfFileMenuModel(
    locale: Locale,
    recentSessions: List<Path>,
    exportEnabled: Boolean,
    isMacOs: Boolean,
    configurationEnabled: Boolean = true,
): SimpleperfFileMenuModel =
    withDefaultLocale(locale) {
        SimpleperfFileMenuModel(
            fileTitle = getString(Res.string.file),
            openLabel = getString(Res.string.open),
            settingsLabel = getString(Res.string.settings).takeUnless { isMacOs },
            exportMenu =
                SimpleperfExportMenuModel(
                    title = getString(Res.string.export),
                    sessionPackageLabel = getString(Res.string.session_package),
                    reportLabel = getString(Res.string.json_csv),
                    geckoProfileLabel = getString(Res.string.firefox_profiler_json),
                    rawProtobufLabel = getString(Res.string.raw_protobuf),
                    screenshotLabel = getString(Res.string.screenshot),
                    simpleperfReportLabel = getString(Res.string.simpleperf_report),
                    htmlReportLabel = getString(Res.string.report_html_py),
                    externalOpenLabel = getString(Res.string.external_open),
                ),
            configurationMenu =
                SimpleperfConfigurationMenuModel(
                    title = getString(Res.string.configuration),
                    samplingTemplateLabel = getString(Res.string.capture_templates),
                    captureConfigurationLabel = getString(Res.string.capture_configuration),
                    advancedParametersLabel = getString(Res.string.advanced_parameters),
                    enabled = configurationEnabled,
                ),
            openRecentTitle = getString(Res.string.open_recent),
            noRecentLabel = getString(Res.string.no_recent_sessions),
            clearRecentLabel = getString(Res.string.clear_menu),
            recentItems = recentSessions.toRecentMenuItems(),
            exportEnabled = exportEnabled,
            openShortcut = primaryShortcut(Key.O, isMacOs),
            exportShortcut = primaryShortcut(Key.E, isMacOs),
            settingsShortcut = primaryShortcut(Key.Comma, isMacOs).takeUnless { isMacOs },
        )
    }

private suspend fun <T> withDefaultLocale(locale: Locale, block: suspend () -> T): T {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    return try {
        block()
    } finally {
        Locale.setDefault(previous)
    }
}
