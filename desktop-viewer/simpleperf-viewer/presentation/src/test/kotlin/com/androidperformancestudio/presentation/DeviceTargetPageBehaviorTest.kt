package com.androidperformancestudio.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeviceTargetPageBehaviorTest {
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
                            isLoading = true,
                        ),
                    captureState = CaptureState.Idle,
                    reportState = ReportState(),
                    actions = actions,
                    reportActions = goldenActions(),
                )
            }

            onNodeWithText("Pixel 8 Pro").performClick()
            onNodeWithText("Pixel Offline").assertIsNotEnabled()
            onNodeWithText("Open Session").assertDoesNotExist()
            onNodeWithText("Refreshing…").assertIsNotEnabled()

            assertEquals(listOf("emulator-5554"), selectedDevices)
        }

    @Test
    fun `target and continue actions remain wired`() =
        runDesktopComposeUiTest(width = 1100, height = 760) {
            val packages = mutableListOf<String>()
            val processes = mutableListOf<Int>()
            val threads = mutableListOf<ThreadOption>()
            var continueCount = 0
            val thread = ThreadOption(pid = 42, tid = 43, name = "RenderThread")
            val actions =
                deviceActions(
                    onSelectPackage = packages::add,
                    onSelectProcess = processes::add,
                    onSelectThread = threads::add,
                    onContinue = { continueCount++ },
                )

            setContent {
                HomeScreen(
                    state = readyState(thread),
                    captureState = CaptureState.Idle,
                    reportState = ReportState(),
                    actions = actions,
                    reportActions = goldenActions(),
                )
            }

            onNodeWithContentDescription("Search package, process, user or PID").assertExists()
            onNodeWithText("com.example.demo").assertIsSelected().performClick()
            onNodeWithText("example_process").performClick()
            onNodeWithText("RenderThread").performClick()
            onNodeWithText("Continue to Capture").performClick()

            assertEquals(listOf("com.example.demo"), packages)
            assertEquals(listOf(42), processes)
            assertEquals(listOf(thread), threads)
            assertEquals(1, continueCount)
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
                packages = listOf(PackageOption("com.example.demo")),
                processes = listOf(ProcessOption(42, "example_process", "u0_a123")),
            ),
        selectedTarget = CaptureTarget.App("com.example.demo"),
        threads = listOf(thread),
    )
}

private fun deviceActions(
    onSelectDevice: (String) -> Unit = {},
    onSelectPackage: (String) -> Unit = {},
    onSelectProcess: (Int) -> Unit = {},
    onSelectThread: (ThreadOption) -> Unit = {},
    onContinue: () -> Unit = {},
) = DeviceTargetActions(
    onRefresh = {},
    onSelectDevice = onSelectDevice,
    onSearch = {},
    onSelectPackage = onSelectPackage,
    onSelectProcess = onSelectProcess,
    onSelectThread = onSelectThread,
    onContinue = onContinue,
    onBack = {},
    onSelectTemplate = { _: SamplingTemplate -> },
    onUpdateSamplingParameters = { _: SamplingParameters -> },
    onStartCapture = {},
    onStopCapture = {},
    onCancelCapture = {},
)
