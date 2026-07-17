package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
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
import com.androidperformancestudio.capture.SamplingParameters
import com.androidperformancestudio.capture.SamplingTemplate
import com.androidperformancestudio.capture.SimpleperfTarget
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeviceTargetVisualGoldenTest {
    @Test
    fun `light device target workspace matches macOS golden`() = verifyGolden("light", dark = false)

    @Test
    fun `dark device target workspace matches macOS golden`() = verifyGolden("dark", dark = true)

    private fun verifyGolden(
        name: String,
        dark: Boolean,
    ) = runDesktopComposeUiTest(width = GOLDEN_WIDTH, height = GOLDEN_HEIGHT) {
        setContent {
            Box(Modifier.fillMaxSize().testTag(GOLDEN_TAG)) {
                HomeScreen(
                    state = macOsGoldenState(),
                    captureState = CaptureState.Idle,
                    reportState = ReportState(),
                    actions = visualDeviceActions(),
                    reportActions = goldenActions(),
                    darkTheme = dark,
                )
            }
        }
        waitForIdle()
        val actual = onNodeWithTag(GOLDEN_TAG).captureToImage()
        val goldenPath = Path.of("src/test/resources/goldens/macos-device-target-$name.png")
        if (System.getenv("UPDATE_DEVICE_TARGET_GOLDENS") == "1") {
            Files.createDirectories(goldenPath.parent)
            ImageIO.write(actual.toBufferedImage(), "png", goldenPath.toFile())
        }
        assertTrue(
            Files.exists(goldenPath),
            "Missing golden $goldenPath; run with UPDATE_DEVICE_TARGET_GOLDENS=1",
        )
        val expected = ImageIO.read(goldenPath.toFile())
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val mismatchRatio = actual.mismatchRatio(expected)
        assertTrue(
            mismatchRatio <= MAXIMUM_MISMATCH_RATIO,
            "macOS device target golden mismatch for $name: $mismatchRatio",
        )
    }
}

private fun macOsGoldenState(): DeviceTargetState {
    val processes =
        listOf(
            ProcessOption(7421, "com.example.gallery", "u0_a164"),
            ProcessOption(7498, "RenderService", "u0_a164"),
            ProcessOption(1183, "surfaceflinger", "system"),
        )
    return DeviceTargetState(
        devices =
            listOf(
                DeviceOption("3A271FDH2001Y7", "Pixel 9 Pro", true),
                DeviceOption("emulator-5554", "Pixel Tablet API 36", true),
                DeviceOption("RF8M91OFFLINE", "Galaxy S24", false),
            ),
        selectedSerial = "3A271FDH2001Y7",
        selection =
            DeviceSelection(
                serial = "3A271FDH2001Y7",
                model = "Pixel 9 Pro",
                androidVersion = "16",
                sdkInt = 36,
                abis = listOf("arm64-v8a"),
                capabilities =
                    CapabilitySummary(
                        status = CapabilityStatus.READY,
                        root = "available",
                        profilingScope = "app and system",
                        simpleperf = "1.0",
                        eventNames = listOf("cpu-cycles", "instructions", "task-clock", "context-switches"),
                        limitations = emptyList(),
                    ),
                packages =
                    listOf(
                        PackageOption("com.example.gallery"),
                        PackageOption("com.android.chrome"),
                        PackageOption("com.google.android.youtube"),
                        PackageOption("com.android.systemui"),
                    ),
                processes = processes,
            ),
        selectedTarget = CaptureTarget.Process(7421, "com.example.gallery"),
        selectedPackageName = "com.example.gallery",
        captureSetup =
            CaptureSetup(
                template = SamplingTemplate.APP_CPU_BASIC,
                parameters =
                    SamplingTemplate.APP_CPU_BASIC.create(
                        SimpleperfTarget.Process(7421, appPackage = "com.example.gallery"),
                    ),
            ),
        threads =
            listOf(
                ThreadOption(7421, 7421, "example.gallery"),
                ThreadOption(7421, 7440, "RenderThread"),
                ThreadOption(7421, 7446, "Jit thread pool"),
                ThreadOption(7421, 7452, "DefaultDispatch"),
            ),
    )
}

private fun visualDeviceActions() =
    DeviceTargetActions(
        onRefresh = {},
        onSelectDevice = {},
        onSearch = {},
        onSelectPackage = {},
        onSelectProcess = {},
        onSelectThread = {},
        onContinue = {},
        onBack = {},
        onSelectTemplate = { _: SamplingTemplate -> },
        onUpdateSamplingParameters = { _: SamplingParameters -> },
        onStartCapture = {},
        onStopCapture = {},
        onCancelCapture = {},
    )

private const val GOLDEN_TAG = "macos-device-target-golden"
private const val GOLDEN_WIDTH = 1100
private const val GOLDEN_HEIGHT = 720
private const val MAXIMUM_MISMATCH_RATIO = 0.03
