package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
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
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.SamplingTemplate
import com.androidperformancestudio.capture.SimpleperfTarget
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.LocalTitleBarTargetHost
import com.androidperformancestudio.ui.LocalWindowMenuBarActive
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
    fun `hosted CPU Process selector replaces App and selects apps without a running pid`() =
        runDesktopComposeUiTest(width = 1280, height = 760) {
            val state = mutableStateOf(readyState())
            val host = readyHost()
            setContent { HostedCpu(state, host) }

            onNodeWithContentDescription("应用选择器").assertDoesNotExist()
            onNodeWithContentDescription("进程选择器").assertIsEnabled()
            assertEquals(null, host.selectedProcessPid)

            onNodeWithContentDescription("进程选择器").performClick()
            onNodeWithText("$PROCESS_NAME ($PROCESS_ID)").assertDoesNotExist()
            onNodeWithText(STOPPED_APP_PACKAGE).performClick()
            waitForIdle()
            assertEquals(STOPPED_APP_PACKAGE, state.value.selectedPackageName)
            assertEquals(CaptureTarget.App(STOPPED_APP_PACKAGE), state.value.selectedTarget)
            assertEquals(
                SimpleperfTarget.App(STOPPED_APP_PACKAGE),
                state.value.captureSetup
                    ?.parameters
                    ?.target,
            )
            assertEquals(STOPPED_APP_PACKAGE, host.selectedPackageName)
            assertEquals(null, host.selectedProcessPid)

            // The shell's periodic process refresh must not replace the app capture target.
            runOnIdle { host.updateProcesses(host.processes) }
            waitForIdle()
            onNodeWithText(STOPPED_APP_PACKAGE).assertExists()
            assertEquals(CaptureTarget.App(STOPPED_APP_PACKAGE), state.value.selectedTarget)
        }

    @Test
    fun `entering CPU converts the shared process to an app target even for the same package`() =
        runDesktopComposeUiTest(width = 1280, height = 760) {
            val state =
                mutableStateOf(
                    readyState().copy(
                        selectedTarget = CaptureTarget.Process(PROCESS_ID, PROCESS_NAME),
                        captureSetup =
                            CaptureSetup(
                                SamplingTemplate.APP_CPU_BASIC,
                                SamplingTemplate.APP_CPU_BASIC.create(SimpleperfTarget.Process(PROCESS_ID)),
                            ),
                    ),
                )
            val host = readyHost()
            setContent { HostedCpu(state, host) }

            waitForIdle()
            assertEquals(CaptureTarget.App(PACKAGE_NAME), state.value.selectedTarget)
            assertEquals(
                SimpleperfTarget.App(PACKAGE_NAME),
                state.value.captureSetup
                    ?.parameters
                    ?.target,
            )
            assertEquals(PACKAGE_NAME, host.selectedPackageName)
            assertEquals(null, host.selectedProcessPid)
        }

    @Test
    fun `leaving retained CPU restores Process list and reentering restores App list`() =
        runDesktopComposeUiTest(width = 1280, height = 760) {
            val state = mutableStateOf(readyState())
            val host = readyHost()
            val active = mutableStateOf(true)
            setContent { HostedCpu(state, host, active = active.value) }

            onNodeWithContentDescription("进程选择器").performClick()
            onNodeWithText(OTHER_APP_PACKAGE).performClick()
            waitForIdle()
            assertEquals(CaptureTarget.App(OTHER_APP_PACKAGE), state.value.selectedTarget)

            runOnIdle { active.value = false }
            onNodeWithContentDescription("进程选择器").performClick()
            onNodeWithText("$PROCESS_NAME ($PROCESS_ID)").performClick()
            waitForIdle()
            assertEquals(PROCESS_ID, host.selectedProcessPid)
            assertEquals(CaptureTarget.App(OTHER_APP_PACKAGE), state.value.selectedTarget)

            runOnIdle { active.value = true }
            onNodeWithContentDescription("应用选择器").assertDoesNotExist()
            onNodeWithContentDescription("进程选择器").performClick()
            onNodeWithText(STOPPED_APP_PACKAGE).performClick()
            waitForIdle()
            assertEquals(CaptureTarget.App(STOPPED_APP_PACKAGE), state.value.selectedTarget)
        }

    @Test
    fun `hosted CPU app selection is disabled during loading capture and device handoff`() =
        runDesktopComposeUiTest(width = 1280, height = 760) {
            val state = mutableStateOf(readyState())
            val host = readyHost()
            val capture = mutableStateOf<CaptureState>(CaptureState.Idle)
            setContent { HostedCpu(state, host, captureState = capture.value) }
            onNodeWithContentDescription("进程选择器").assertIsEnabled()

            runOnIdle { state.value = state.value.copy(isLoading = true) }
            onNodeWithContentDescription("进程选择器").assertIsNotEnabled()
            runOnIdle { state.value = state.value.copy(isLoading = false) }
            onNodeWithContentDescription("进程选择器").assertIsEnabled()

            runOnIdle { capture.value = CaptureState.Recording(Path.of("session"), "simpleperf record") }
            onNodeWithContentDescription("进程选择器").assertIsNotEnabled()
            runOnIdle { capture.value = CaptureState.Idle }
            onNodeWithContentDescription("进程选择器").assertIsEnabled()

            runOnIdle { host.updateDevices(listOf(TitleBarDeviceOption("serial-2", "Pixel 9"))) }
            onNodeWithContentDescription("进程选择器").assertIsNotEnabled()
        }

    @Test
    fun `CPU header app selector is only the standalone fallback for Process slot`() {
        val source =
            Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/presentation/DeviceTargetPage.kt"))
        val header = source.substringAfter("HeaderToolbar(").substringBefore("ReportWorkspace(")
        assertTrue(header.contains("TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE)"))
        assertTrue(header.contains("AppSelector("))
        assertTrue(header.contains("actions.onSelectPackage"))
        assertTrue(!header.contains("ProcessSelector("))
        assertTrue(!header.contains("ThreadSelector("))
        assertTrue(header.contains("TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS)"))
        assertTrue(
            source.contains(
                "TitleBarRefreshOrInline(enabled = enabled && !state.isLoading, onRefresh = actions.onRefresh)",
            ),
        )
    }

    @Composable
    @Suppress("FunctionNaming", "ktlint:standard:function-naming")
    private fun HostedCpu(
        state: MutableState<DeviceTargetState>,
        host: TitleBarTargetHost,
        active: Boolean = true,
        captureState: CaptureState = CaptureState.Idle,
    ) {
        ViewerTheme(darkTheme = false) {
            Column {
                TitleBarTargetControls(host, UiLanguage.SIMPLIFIED_CHINESE)
                CompositionLocalProvider(
                    LocalTitleBarTargetHost provides host.takeIf { active },
                    LocalWindowMenuBarActive provides active,
                ) {
                    Box(Modifier.weight(1f)) {
                        HomeScreen(
                            state = state.value,
                            captureState = captureState,
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

    private fun readyHost() =
        TitleBarTargetHost().apply {
            updateDevices(listOf(TitleBarDeviceOption("serial-1", "Pixel 8")))
            updateProcesses(
                listOf(
                    TitleBarProcessOption(PROCESS_ID, PROCESS_NAME, PACKAGE_NAME, "u0_a1"),
                    TitleBarProcessOption(OTHER_APP_PID, OTHER_APP_PACKAGE, OTHER_APP_PACKAGE, "u0_a2"),
                ),
            )
            selectProcess(PROCESS_ID)
        }

    @Test
    fun `English toolbar stays stable while the target loads`() = verifyLayout(UiLanguage.ENGLISH)

    @Test
    fun `Chinese toolbar stays stable while the target loads`() = verifyLayout(UiLanguage.SIMPLIFIED_CHINESE)

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

                    runOnIdle { state.value = state.value.copy(isLoading = true) }
                    onNodeWithText(localizedStringResource(SimpleperfViewerRes.sp_target_refreshing, language))
                        .assertExists()
                    selectorDescriptions.forEach { description ->
                        val selector = onNodeWithContentDescription(description)
                        selector.assertIsNotEnabled()
                        assertEquals(
                            before.getValue(description),
                            selector.fetchSemanticsNode().boundsInRoot,
                            "$description moved while loading at width $width, dark=$darkTheme",
                        )
                    }

                    runOnIdle { state.value = state.value.copy(isLoading = false) }
                    onNodeWithText(localizedStringResource(SimpleperfViewerRes.sp_target_refresh, language))
                        .assertExists()
                    selectorDescriptions.forEach { description ->
                        assertEquals(
                            before.getValue(description),
                            onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot,
                        )
                    }
                }
            }
        }
    }

    private fun selectorDescriptions(language: UiLanguage): List<String> =
        listOf(
            SimpleperfViewerRes.sp_target_device_selector,
            SimpleperfViewerRes.sp_target_app_selector,
        ).map { localizedStringResource(it, language) }

    private fun processSelectionActions(state: MutableState<DeviceTargetState>) =
        DeviceTargetActions(
            onRefresh = {},
            onSelectDevice = {},
            onSearch = {},
            onSelectPackage = { packageName ->
                state.value =
                    state.value.copy(
                        selectedPackageName = packageName,
                        selectedTarget = CaptureTarget.App(packageName),
                        captureSetup =
                            CaptureSetup(
                                SamplingTemplate.APP_CPU_BASIC,
                                SamplingTemplate.APP_CPU_BASIC.create(SimpleperfTarget.App(packageName)),
                            ),
                        threads = emptyList(),
                    )
            },
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
                    packages =
                        listOf(
                            PackageOption(PACKAGE_NAME),
                            PackageOption(OTHER_APP_PACKAGE),
                            PackageOption(STOPPED_APP_PACKAGE),
                        ),
                    processes =
                        listOf(
                            ProcessOption(PROCESS_ID, PROCESS_NAME, "u0_a1"),
                            ProcessOption(OTHER_APP_PID, OTHER_APP_PACKAGE, "u0_a2"),
                        ),
                ),
        )

    private companion object {
        const val PACKAGE_NAME = "com.example.demo"
        const val PROCESS_NAME = "$PACKAGE_NAME:worker"
        const val PROCESS_ID = 42
        const val OTHER_APP_PACKAGE = "com.other.app"
        const val OTHER_APP_PID = 777
        const val STOPPED_APP_PACKAGE = "com.installed.not.running"
    }
}
