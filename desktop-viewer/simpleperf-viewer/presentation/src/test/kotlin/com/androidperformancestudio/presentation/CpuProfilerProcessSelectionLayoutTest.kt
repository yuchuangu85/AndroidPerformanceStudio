package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.CapabilitySummary
import com.androidperformancestudio.application.CaptureSetup
import com.androidperformancestudio.application.CaptureTarget
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetState
import com.androidperformancestudio.application.PackageOption
import com.androidperformancestudio.application.ProcessOption
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.SamplingTemplate
import com.androidperformancestudio.capture.SimpleperfTarget
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.TitleBarDeviceOption
import com.androidperformancestudio.ui.TitleBarProcessOption
import com.androidperformancestudio.ui.TitleBarTargetControls
import com.androidperformancestudio.ui.TitleBarTargetHost
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CpuProfilerProcessSelectionLayoutTest {
    @Test
    fun `hosted CPU title bar lists every device process and selects one outside the app`() =
        runDesktopComposeUiTest(width = 1280, height = 760) {
            val state = mutableStateOf(readyState())
            val host = TitleBarTargetHost()
            setContent {
                ViewerTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalTitleBarTargetHost provides host) {
                        Column {
                            TitleBarTargetControls(host, UiLanguage.SIMPLIFIED_CHINESE)
                            Box(Modifier.weight(1f)) {
                                HomeScreen(
                                    state = state.value,
                                    captureState = CaptureState.Idle,
                                    reportState = ReportState(),
                                    actions = processSelectionActions(state),
                                    reportActions = goldenActions(),
                                    language = UiLanguage.SIMPLIFIED_CHINESE,
                                    darkTheme = false,
                                )
                            }
                        }
                    }
                }
            }

            runOnIdle {
                host.updateDevices(listOf(TitleBarDeviceOption("serial-1", "Pixel 8")))
                host.updateProcesses(
                    listOf(
                        TitleBarProcessOption(PROCESS_ID, PROCESS_NAME, PACKAGE_NAME, "u0_a1"),
                        TitleBarProcessOption(NATIVE_PROCESS_ID, NATIVE_PROCESS_NAME, null, "root"),
                    ),
                )
            }
            waitForIdle()

            // The fixed title bar lists every device process, including ones outside the selected app.
            onNodeWithContentDescription("进程选择器").performClick()
            onNodeWithText("${NATIVE_PROCESS_NAME} (${NATIVE_PROCESS_ID})").performClick()
            waitForIdle()
            assertEquals(NATIVE_PROCESS_ID, state.value.selectedProcessId)
        }

    @Test
    fun `CPU title bar second target is the device-wide process selector`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation/DeviceTargetPage.kt"))
        assertTrue(source.contains("TitleBarRefreshOrInline(enabled = enabled && !state.isLoading, onRefresh = actions.onRefresh)"))
        val titleBarSlot =
            source
                .substringAfter("TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {")
                .substringBefore("if (!targetsInTitleBar) HeaderSpacer()")
        assertTrue(titleBarSlot.contains("ProcessSelector("))
        assertTrue(titleBarSlot.contains("state.selection?.processes.orEmpty()"))
        assertTrue(titleBarSlot.contains("actions.onSelectProcess"))
        assertTrue(titleBarSlot.contains("SimpleperfLocalization(language)"))
        assertTrue(!titleBarSlot.contains("AppSelector("))
        val appSelectorInToolbar =
            source
                .substringAfter("TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE) {")
                .substringBefore("TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS) {")
        assertTrue(appSelectorInToolbar.contains("AppSelector("))
        assertTrue(appSelectorInToolbar.contains("actions.onSelectPackage"))
    }

    @Test
    fun `English process selection keeps toolbar stable`() = verifyLayout(UiLanguage.ENGLISH)

    @Test
    fun `Chinese process selection keeps toolbar stable`() = verifyLayout(UiLanguage.SIMPLIFIED_CHINESE)

    private fun verifyLayout(language: UiLanguage) {
        listOf(980, 1440).forEach { width ->
            listOf(false, true).forEach { darkTheme ->
                runDesktopComposeUiTest(width = width, height = 760) {
                    val state = mutableStateOf(readyState())
                    setContent {
                        ViewerTheme(darkTheme = darkTheme) {
                            HomeScreen(
                                state = state.value,
                                captureState = CaptureState.Idle,
                                reportState = ReportState(),
                                actions = processSelectionActions(state),
                                reportActions = goldenActions(),
                                language = language,
                                darkTheme = darkTheme,
                            )
                        }
                    }
                    val selectorDescriptions = selectorDescriptions(language)
                    val before =
                        selectorDescriptions.associateWith { description ->
                            onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
                        }

                    onNodeWithContentDescription(selectorDescriptions[2]).performClick()
                    onNodeWithText(PROCESS_NAME).performClick()
                    onNodeWithText(localizedStringResource(SimpleperfViewerRes.sp_target_refreshing, language))
                        .assertExists()
                    selectorDescriptions.forEach { description ->
                        val selector = onNodeWithContentDescription(description)
                        selector.assertIsNotEnabled()
                        assertEquals(
                            before.getValue(description),
                            selector.fetchSemanticsNode().boundsInRoot,
                            "$description moved while loading threads at width $width, dark=$darkTheme",
                        )
                    }

                    runOnIdle {
                        state.value =
                            state.value.copy(
                                threads = listOf(ThreadOption(PROCESS_ID, PROCESS_ID + 1, "RenderThread")),
                                isLoading = false,
                            )
                    }
                    onNodeWithText(localizedStringResource(SimpleperfViewerRes.sp_target_refresh, language))
                        .assertExists()
                    selectorDescriptions.forEach { description ->
                        assertEquals(
                            before.getValue(description),
                            onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot,
                        )
                    }
                    onNodeWithContentDescription(selectorDescriptions[3]).performClick()
                    onNodeWithText("RenderThread").assertExists()
                }
            }
        }
    }

    private fun selectorDescriptions(language: UiLanguage): List<String> =
        listOf(
            SimpleperfViewerRes.sp_target_device_selector,
            SimpleperfViewerRes.sp_target_app_selector,
            SimpleperfViewerRes.sp_target_process_selector,
            SimpleperfViewerRes.sp_target_thread_selector,
        ).map { localizedStringResource(it, language) }

    private fun processSelectionActions(state: MutableState<DeviceTargetState>) =
        DeviceTargetActions(
            onRefresh = {},
            onSelectDevice = {},
            onSearch = {},
            onSelectPackage = {},
            onSelectProcess = { pid ->
                state.value =
                    state.value.copy(
                        selectedTarget = CaptureTarget.Process(pid, PROCESS_NAME),
                        captureSetup =
                            CaptureSetup(
                                SamplingTemplate.APP_CPU_BASIC,
                                SamplingTemplate.APP_CPU_BASIC.create(SimpleperfTarget.Process(pid)),
                            ),
                        threads = emptyList(),
                        isLoading = true,
                    )
            },
            onSelectThread = {},
            onContinue = {},
            onBack = {},
            onSelectTemplate = {},
            onUpdateSamplingParameters = {},
            onStartCapture = {},
            onStopCapture = {},
            onCancelCapture = {},
        )

    private fun readyState(): DeviceTargetState =
        DeviceTargetState(
            devices = listOf(DeviceOption("serial-1", "Pixel 8", true)),
            selectedSerial = "serial-1",
            selectedPackageName = PACKAGE_NAME,
            selectedTarget = CaptureTarget.App(PACKAGE_NAME),
            captureSetup =
                CaptureSetup(
                    SamplingTemplate.APP_CPU_BASIC,
                    SamplingTemplate.APP_CPU_BASIC.create(SimpleperfTarget.App(PACKAGE_NAME)),
                ),
            selection =
                DeviceSelection(
                    serial = "serial-1",
                    model = "Pixel 8",
                    androidVersion = "15",
                    sdkInt = 35,
                    abis = listOf("arm64-v8a"),
                    capabilities =
                        CapabilitySummary(
                            CapabilityStatus.READY,
                            "available",
                            "system-wide",
                            "available",
                            listOf("cpu-clock"),
                            emptyList(),
                        ),
                    packages = listOf(PackageOption(PACKAGE_NAME)),
                    processes =
                        listOf(
                            ProcessOption(PROCESS_ID, PROCESS_NAME, "u0_a1"),
                            ProcessOption(NATIVE_PROCESS_ID, NATIVE_PROCESS_NAME, "root"),
                        ),
                ),
        )

    private companion object {
        const val PACKAGE_NAME = "com.example.demo"
        const val PROCESS_NAME = "$PACKAGE_NAME:worker"
        const val PROCESS_ID = 42
        const val NATIVE_PROCESS_NAME = "surfaceflinger"
        const val NATIVE_PROCESS_ID = 654
    }
}
