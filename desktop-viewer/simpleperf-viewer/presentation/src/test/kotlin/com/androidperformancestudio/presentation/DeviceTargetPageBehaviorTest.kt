package com.androidperformancestudio.presentation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.CapabilityStatus
import com.androidperformancestudio.application.CapabilitySummary
import com.androidperformancestudio.application.CaptureTarget
import com.androidperformancestudio.application.DeviceOption
import com.androidperformancestudio.application.DeviceSelection
import com.androidperformancestudio.application.DeviceTargetState
import com.androidperformancestudio.application.PackageOption
import com.androidperformancestudio.application.ProcessOption
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ThreadOption
import com.androidperformancestudio.capture.CaptureState
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingTemplate
import com.androidperformancestudio.capture.SimpleperfTarget
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeviceTargetPageBehaviorTest {
    @Test
    fun `home button invokes navigation callback`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            var navigationCount = 0
            setContent {
                HomeScreen(
                    state = DeviceTargetState(),
                    captureState = CaptureState.Idle,
                    reportState = ReportState(),
                    actions = deviceActions(),
                    reportActions = goldenActions(),
                    onNavigateHome = { navigationCount++ },
                )
            }

            onNodeWithContentDescription("Back to home").performClick()
            assertEquals(1, navigationCount)
        }

    @Test
    fun `settings update flame tooltip placement and simpleperf engine`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            var tooltipMode = FlameTooltipMode.FIXED
            var engine = SimpleperfEngine.LOCAL
            var userGuideOpenCount = 0
            setContent {
                HomeScreen(
                    state = DeviceTargetState(),
                    captureState = CaptureState.Idle,
                    reportState = ReportState(),
                    actions = deviceActions(),
                    reportActions = goldenActions(),
                    flameTooltipMode = tooltipMode,
                    onFlameTooltipModeChange = { tooltipMode = it },
                    simpleperfEngine = engine,
                    onSimpleperfEngineChange = { engine = it },
                    onOpenUserGuide = { userGuideOpenCount++ },
                )
            }

            onNodeWithText("Settings").assertDoesNotExist()
            onNodeWithContentDescription("Settings").performClick()
            onNodeWithContentDescription("Capture settings: Flame graph").performClick()
            onNodeWithText("Follow mouse").performClick()
            assertEquals(FlameTooltipMode.FOLLOW_MOUSE, tooltipMode)

            onNodeWithContentDescription("Capture settings: Simpleperf engine").performClick()
            onNodeWithText("Firefox Profiler local engine").performClick()
            assertEquals(SimpleperfEngine.FIREFOX_PROFILER_LOCAL, engine)
            onNodeWithText("Firefox Profiler").performClick()
            assertEquals(SimpleperfEngine.FIREFOX_PROFILER, engine)

            onNodeWithContentDescription("Capture settings: User guide").performClick()
            onNodeWithText("Open User Guide in Browser").performClick()
            assertEquals(1, userGuideOpenCount)
        }

    @Test
    fun `device and toolbar actions remain wired`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            val selectedDevices = mutableListOf<String>()
            val actions = deviceActions(onSelectDevice = selectedDevices::add)

            setContent {
                HomeScreen(
                    state =
                        DeviceTargetState(
                            devices =
                                listOf(
                                    DeviceOption("emulator-5554", "Pixel 8 Pro", true),
                                    DeviceOption("offline-1", "Pixel Offline", false),
                                ),
                        ),
                    captureState = CaptureState.Idle,
                    reportState = ReportState(),
                    actions = actions,
                    reportActions = goldenActions(),
                )
            }

            onNodeWithContentDescription("Device selector").performClick()
            onNodeWithText("Pixel Offline").assertIsNotEnabled()
            onNodeWithText("Pixel 8 Pro").performClick()
            onNodeWithText("Open Session").assertDoesNotExist()
            onNodeWithText("Capture target").assertDoesNotExist()

            onNodeWithText("Device & Target").assertDoesNotExist()
            val deviceSelector = onNodeWithContentDescription("Device selector").fetchSemanticsNode().boundsInRoot
            val appSelector = onNodeWithContentDescription("App selector").fetchSemanticsNode().boundsInRoot
            val refresh = onNodeWithText("Refresh").fetchSemanticsNode().boundsInRoot
            val getData = onNodeWithText("Get data").fetchSemanticsNode().boundsInRoot
            val capabilities = onNodeWithText("Capabilities").fetchSemanticsNode().boundsInRoot
            val settings = onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
            assertTrue(deviceSelector.left < TOOLBAR_LEFT_ALIGNMENT_LIMIT)
            assertTrue(deviceSelector.width < appSelector.width)
            assertTrue(getData.left > refresh.right)
            assertTrue(capabilities.left > getData.right)
            assertTrue(settings.left > capabilities.right)
            onNodeWithText("Settings").assertDoesNotExist()
            onNodeWithContentDescription("Settings").performClick()
            onNodeWithContentDescription("Capture settings: Sampling template").assertExists()

            assertEquals(listOf("emulator-5554"), selectedDevices)
        }

    @Test
    @Suppress("LongMethod")
    fun `cascaded target and capture actions remain wired`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            val packages = mutableListOf<String>()
            val processes = mutableListOf<Int>()
            val threads = mutableListOf<ThreadOption>()
            val templates = mutableListOf<SamplingTemplate>()
            var startCount = 0
            val thread = ThreadOption(pid = 42, tid = 43, name = "RenderThread")
            val uiState = androidx.compose.runtime.mutableStateOf(readyState(thread))
            val settingsSection = androidx.compose.runtime.mutableStateOf<CaptureSettingsSection?>(null)
            val actions =
                deviceActions(
                    onSelectPackage = { packageName ->
                        packages += packageName
                        uiState.value =
                            uiState.value.copy(
                                selectedPackageName = packageName,
                                selectedTarget = CaptureTarget.App(packageName),
                                threads = emptyList(),
                            )
                    },
                    onSelectProcess = { pid ->
                        processes += pid
                        uiState.value =
                            uiState.value.copy(
                                selectedTarget = CaptureTarget.Process(pid, "com.example.second:worker"),
                                threads = listOf(thread),
                            )
                    },
                    onSelectThread = { selectedThread ->
                        threads += selectedThread
                        uiState.value =
                            uiState.value.copy(
                                selectedTarget =
                                    CaptureTarget.Thread(
                                        selectedThread.pid,
                                        selectedThread.tid,
                                        selectedThread.name,
                                    ),
                            )
                    },
                    onSelectTemplate = templates::add,
                    onStartCapture = { startCount++ },
                )

            setContent {
                HomeScreen(
                    state = uiState.value,
                    captureState = CaptureState.Idle,
                    reportState = ReportState(),
                    actions = actions,
                    reportActions = goldenActions(),
                    captureSettingsSection = settingsSection.value,
                    onCaptureSettingsSectionChange = { settingsSection.value = it },
                )
            }

            onNodeWithContentDescription("Search package, process, user or PID").assertDoesNotExist()
            onNodeWithContentDescription("App selector").performClick()
            onNodeWithText("com.example.second").performClick()
            onNodeWithContentDescription("Process selector").performClick()
            onNodeWithText("unrelated.process").assertDoesNotExist()
            onNodeWithText("com.example.second:worker").performClick()
            assertEquals(
                "com.example.second",
                onNodeWithContentDescription("App selector")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.StateDescription],
            )
            onNodeWithContentDescription("Thread selector").performClick()
            onNodeWithText("RenderThread").performClick()
            onNodeWithText("Capabilities").performClick()
            onNodeWithText("Device capability").assertExists()
            onNodeWithText("system-wide").assertExists()
            onNodeWithText("Get data").performClick()
            onNodeWithText("Device capability").assertDoesNotExist()
            onNodeWithText("Capture Configuration").assertDoesNotExist()
            onNodeWithContentDescription("Settings").performClick()
            onNodeWithContentDescription("Capture settings: Sampling template").assertExists()
            onNodeWithContentDescription("Capture settings: Capture configuration").performClick()
            onNodeWithText("Event and rate").assertExists()
            onNodeWithContentDescription("Capture settings: Advanced parameters").performClick()
            onNodeWithText("Call graph").assertExists()
            onNodeWithContentDescription("Capture settings: Sampling template").performClick()
            onNodeWithText("Back to Device & Target").assertDoesNotExist()
            onNodeWithText("Continue to Capture").assertDoesNotExist()
            onNodeWithText("Low Overhead").performClick()
            onNodeWithText("Done").performClick()
            onNodeWithText("Get data").performClick()

            assertEquals(listOf("com.example.second"), packages)
            assertEquals(listOf(42), processes)
            assertEquals(listOf(thread), threads)
            assertEquals(listOf(SamplingTemplate.LOW_OVERHEAD), templates)
            assertEquals(1, startCount)
        }

    @Test
    fun `active capture locks selectors and exposes stop controls`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            var stopCount = 0
            var cancelCount = 0
            val actions =
                deviceActions(
                    onStopCapture = { stopCount++ },
                    onCancelCapture = { cancelCount++ },
                )

            setContent {
                HomeScreen(
                    state = readyState(ThreadOption(pid = 42, tid = 43, name = "RenderThread")),
                    captureState = CaptureState.Recording(Path.of("session"), "simpleperf record"),
                    reportState = ReportState(),
                    actions = actions,
                    reportActions = goldenActions(),
                )
            }

            onNodeWithContentDescription("Device selector").assertIsNotEnabled()
            onNodeWithText("Get data").assertDoesNotExist()
            onNodeWithText("Stop and analyze").performClick()
            onNodeWithText("Cancel").performClick()

            assertEquals(1, stopCount)
            assertEquals(1, cancelCount)
        }
}

private fun readyState(thread: ThreadOption): DeviceTargetState {
    val capabilities =
        CapabilitySummary(
            status = CapabilityStatus.READY,
            root = "available",
            profilingScope = "system-wide",
            simpleperf = "available",
            eventNames = listOf("cpu-cycles", "instructions"),
            limitations = emptyList(),
        )
    return DeviceTargetState(
        devices = listOf(DeviceOption("emulator-5554", "Pixel 8 Pro", true)),
        selectedSerial = "emulator-5554",
        selection =
            DeviceSelection(
                serial = "emulator-5554",
                model = "Pixel 8 Pro",
                androidVersion = "16",
                sdkInt = 36,
                abis = listOf("arm64-v8a"),
                capabilities = capabilities,
                packages =
                    listOf(
                        PackageOption("com.example.demo"),
                        PackageOption("com.example.second"),
                    ),
                processes =
                    listOf(
                        ProcessOption(40, "com.example.demo", "u0_a123"),
                        ProcessOption(42, "com.example.second:worker", "u0_a124"),
                        ProcessOption(44, "unrelated.process", "u0_a125"),
                    ),
            ),
        selectedPackageName = "com.example.demo",
        selectedTarget = CaptureTarget.App("com.example.demo"),
        captureSetup =
            com.androidperformancestudio.application.CaptureSetup(
                template = SamplingTemplate.APP_CPU_BASIC,
                parameters = SamplingTemplate.APP_CPU_BASIC.create(SimpleperfTarget.App("com.example.demo")),
            ),
        threads = listOf(thread),
    )
}

@Suppress("LongParameterList")
private fun deviceActions(
    onSelectDevice: (String) -> Unit = {},
    onSelectPackage: (String) -> Unit = {},
    onSelectProcess: (Int) -> Unit = {},
    onSelectThread: (ThreadOption) -> Unit = {},
    onContinue: () -> Unit = {},
    onSelectTemplate: (SamplingTemplate) -> Unit = {},
    onStartCapture: () -> Unit = {},
    onStopCapture: () -> Unit = {},
    onCancelCapture: () -> Unit = {},
) = DeviceTargetActions(
    onRefresh = {},
    onSelectDevice = onSelectDevice,
    onSearch = {},
    onSelectPackage = onSelectPackage,
    onSelectProcess = onSelectProcess,
    onSelectThread = onSelectThread,
    onContinue = onContinue,
    onBack = {},
    onSelectTemplate = onSelectTemplate,
    onUpdateSamplingParameters = { _: SamplingParameters -> },
    onStartCapture = onStartCapture,
    onStopCapture = onStopCapture,
    onCancelCapture = onCancelCapture,
)

private const val TOOLBAR_LEFT_ALIGNMENT_LIMIT = 20f
