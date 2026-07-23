package dev.agentperf.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import com.androidperformancestudio.desktop.SimpleperfLanguagePreference
import com.androidperformancestudio.desktop.SimpleperfThemePreference
import com.androidperformancestudio.desktop.SimpleperfUiSettings
import com.androidperformancestudio.desktop.SimpleperfWorkspace
import com.androidperformancestudio.perfetto.app.PerfettoWorkspace
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun FrameWindowScope.UnifiedDesktopApp(settingsRequest: Long = 0L) {
    val navigator = remember { AppNavigator() }
    var showApplicationSettings by remember { mutableStateOf(false) }
    val applicationSettingsStore = remember { ApplicationUiSettingsStore.desktop() }
    val externalAnalysisLauncher = remember { ExternalAnalysisLauncher() }
    val userDocumentationLauncher = remember { UserDocumentationLauncher() }
    val coroutineScope = rememberCoroutineScope()
    var applicationSettings by remember { mutableStateOf(applicationSettingsStore.load()) }
    val updateApplicationSettings: (ApplicationUiSettings) -> Unit = { updated ->
        applicationSettings = updated
        applicationSettingsStore.save(updated)
    }
    val chinese =
        applicationSettings.language.resolve(Locale.getDefault()) == ApplicationLanguage.SIMPLIFIED_CHINESE
    val darkTheme = applicationSettings.theme.resolveDark(isSystemInDarkTheme())
    val simpleperfSettings =
        SimpleperfUiSettings(
            theme = SimpleperfThemePreference.parse(applicationSettings.theme.storageValue),
            language = SimpleperfLanguagePreference.parse(applicationSettings.language.storageValue),
        )

    LaunchedEffect(settingsRequest) {
        if (shouldOpenSettingsForRequest(settingsRequest)) {
            showApplicationSettings = true
        }
    }
    DisposableEffect(userDocumentationLauncher) {
        onDispose(userDocumentationLauncher::close)
    }
    LaunchedEffect(navigator.destination) {
        if (navigator.destination.shouldMaximizeWindow()) {
            window.placement = WindowPlacement.Maximized
        }
    }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            when (navigator.destination) {
                AppDestination.HOME ->
                    AppHomePage(
                        chinese = chinese,
                        onOpenLayoutInspector = { navigator.open(AppDestination.LAYOUT_INSPECTOR) },
                        onOpenSimpleperf = { navigator.open(AppDestination.SIMPLEPERF) },
                        onOpenPerfetto = { navigator.open(AppDestination.PERFETTO) },
                        onOpenMemoryProfiler = { navigator.open(AppDestination.MEMORY_PROFILER) },
                        onOpenFrameProfiler = { navigator.open(AppDestination.FRAME_PROFILER) },
                        onOpenStartupProfiler = { navigator.open(AppDestination.STARTUP_PROFILER) },
                        onOpenBatteryProfiler = { navigator.open(AppDestination.BATTERY_PROFILER) },
                    )
                AppDestination.LAYOUT_INSPECTOR ->
                    DesktopViewerApp(
                        commonThemePreference = applicationSettings.theme.storageValue,
                        commonLanguagePreference = applicationSettings.language.storageValue,
                    )
                AppDestination.SIMPLEPERF ->
                    SimpleperfWorkspace(
                        window = window,
                        settings = simpleperfSettings,
                        onOpenUserGuide = {
                            val lang = if (chinese) UserDocumentationLanguage.SIMPLIFIED_CHINESE else UserDocumentationLanguage.ENGLISH
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching { userDocumentationLauncher.open(lang) }
                            }
                        },
                    )
                AppDestination.PERFETTO ->
                    PerfettoWorkspace(
                        onOpenUserGuide = {
                            val lang = if (chinese) UserDocumentationLanguage.SIMPLIFIED_CHINESE else UserDocumentationLanguage.ENGLISH
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching { userDocumentationLauncher.open(lang) }
                            }
                        },
                    )
                AppDestination.MEMORY_PROFILER ->
                    ComingSoonPage(
                        title = if (chinese) "Memory Profiler" else "Memory Profiler",
                        chinese = chinese,
                        onBack = { navigator.open(AppDestination.HOME) },
                    )
                AppDestination.FRAME_PROFILER ->
                    ComingSoonPage(
                        title = if (chinese) "Frame Profiler" else "Frame Profiler",
                        chinese = chinese,
                        onBack = { navigator.open(AppDestination.HOME) },
                    )
                AppDestination.STARTUP_PROFILER ->
                    ComingSoonPage(
                        title = if (chinese) "Startup Profiler" else "Startup Profiler",
                        chinese = chinese,
                        onBack = { navigator.open(AppDestination.HOME) },
                    )
                AppDestination.BATTERY_PROFILER ->
                    ComingSoonPage(
                        title = if (chinese) "Battery Profiler" else "Battery Profiler",
                        chinese = chinese,
                        onBack = { navigator.open(AppDestination.HOME) },
                    )
            }
            if (showApplicationSettings) {
                ApplicationSettingsDialog(
                    settings = applicationSettings,
                    chinese = chinese,
                    onSettingsChanged = updateApplicationSettings,
                    onDismiss = { showApplicationSettings = false },
                )
            }
        }
    }
}
