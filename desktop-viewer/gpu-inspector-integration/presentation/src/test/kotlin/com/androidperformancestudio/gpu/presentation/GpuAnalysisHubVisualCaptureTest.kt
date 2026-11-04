package com.androidperformancestudio.gpu.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Automated rendering of the page 09 workspace. The PNG is written only when
 * `APS_GPU_HUB_SCREENSHOT` is exported; this is automated evidence, not native-window acceptance.
 */
@OptIn(ExperimentalTestApi::class)
class GpuAnalysisHubVisualCaptureTest {
    @Test
    fun `workspace renders a dense dark gpu hub layout`() =
        runDesktopComposeUiTest(width = 1360, height = 900) {
            val state = hubState()
            val actions = GpuIntegrationActions({}, {}, {}, {})
            setContent {
                ViewerTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().testTag("gpu-hub-visual-capture")) {
                        Column(Modifier.fillMaxSize()) {
                            GpuAnalysisToolbar(
                                state = state,
                                actions = actions,
                                selectedArtifact = state.artifacts.firstOrNull { it.id == state.selectedArtifactId },
                                language = UiLanguage.SIMPLIFIED_CHINESE,
                            )
                            GpuAnalysisHubScreen(state, actions, UiLanguage.SIMPLIFIED_CHINESE, Modifier.weight(1f))
                        }
                    }
                }
            }

            onNodeWithTag("gpu-analysis-toolbar").assertExists()
            onNodeWithTag("gpu-artifact-rail").assertExists()
            onNodeWithTag("gpu-hub-tabs").assertExists()
            onNodeWithTag("gpu-artifact-preview").assertExists()
            waitForIdle()

            val screenshot = onNodeWithTag("gpu-hub-visual-capture").captureToImage()
            assertEquals(1360, screenshot.width)
            assertEquals(900, screenshot.height)

            System.getenv("APS_GPU_HUB_SCREENSHOT")?.let { output ->
                ImageIO.write(screenshot.toBufferedImage(), "png", Path.of(output).toFile())
            }

            assertTrue(distinctSampleColors(screenshot) > 1, "GPU Analysis Hub workspace should render visible content")
        }

    @Test
    fun `workspace renders a light english layout at the minimum window size`() =
        runDesktopComposeUiTest(width = 1280, height = 720) {
            val state = hubState()
            val actions = GpuIntegrationActions({}, {}, {}, {})
            setContent {
                ViewerTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().testTag("gpu-hub-visual-capture-compact")) {
                        Column(Modifier.fillMaxSize()) {
                            GpuAnalysisToolbar(
                                state = state,
                                actions = actions,
                                selectedArtifact = state.artifacts.firstOrNull { it.id == state.selectedArtifactId },
                                language = UiLanguage.ENGLISH,
                            )
                            GpuAnalysisHubScreen(state, actions, UiLanguage.ENGLISH, Modifier.weight(1f))
                        }
                    }
                }
            }

            onNodeWithTag("gpu-artifact-table").assertDoesNotExist()
            onNodeWithTag("gpu-artifact-preview").assertExists()
            waitForIdle()

            val screenshot = onNodeWithTag("gpu-hub-visual-capture-compact").captureToImage()
            assertEquals(1280, screenshot.width)
            assertEquals(720, screenshot.height)

            System.getenv("APS_GPU_HUB_SCREENSHOT_COMPACT")?.let { output ->
                ImageIO.write(screenshot.toBufferedImage(), "png", Path.of(output).toFile())
            }

            assertTrue(distinctSampleColors(screenshot) > 1, "GPU Analysis Hub workspace should render at 1280x720")
        }

    private fun distinctSampleColors(screenshot: ImageBitmap): Int {
        val pixels = screenshot.toPixelMap()
        return listOf(
            pixels[8, 8],
            pixels[screenshot.width / 4, screenshot.height / 2],
            pixels[screenshot.width / 2, screenshot.height / 2],
            pixels[screenshot.width * 3 / 4, screenshot.height / 2],
            pixels[screenshot.width / 2, screenshot.height - 8],
        ).distinct().size
    }

    private fun hubState(): GpuIntegrationState {
        val pixel = GpuDeviceContext("serial-pixel", "Pixel 8 Pro", 34, "Arm", "Mali-G715", "r45p0")
        val artifacts =
            listOf(
                artifact("frame", GpuArtifactKind.AGI_FRAME_PROFILE, "com.example.app_2024-11-15_1432.gputrace", pixel, ArtifactOpenRoute.AGI, 8_912_896),
                artifact("system", GpuArtifactKind.AGI_SYSTEM_PROFILE, "com.example.app_2024-11-14_1806.gpusys", pixel, ArtifactOpenRoute.AGI, 5_242_880),
                artifact("trace", GpuArtifactKind.PERFETTO_TRACE, "com.example.app_2024-11-13_1642.pftrace", pixel, ArtifactOpenRoute.PERFETTO, 2_097_152),
                artifact("report", GpuArtifactKind.EXTERNAL_REPORT, "gpu-report-2024-11-12.md", null, ArtifactOpenRoute.DESKTOP, 18_432),
            )
        val statuses =
            mapOf(
                "frame" to ArtifactLocationStatus.AVAILABLE,
                "system" to ArtifactLocationStatus.CONTENT_CHANGED,
                "trace" to ArtifactLocationStatus.AVAILABLE,
                "report" to ArtifactLocationStatus.MISSING,
            )
        return GpuIntegrationState(
            capability =
                AgiCapability(
                    executable = Path.of("/Applications/AGI/agi"),
                    version = "3.3.3",
                    launchSupported = true,
                    artifactOpenSupported = true,
                    launchMode = AgiLaunchMode.VERIFIED_CLI,
                    supportedArguments = setOf("--frame-profile", "--output"),
                    warnings = listOf("Opening an artifact through this configured executable was not verified."),
                ),
            artifacts = artifacts,
            selectedArtifactId = "frame",
            devices = listOf(pixel),
            selectedDeviceSerial = pixel.serial,
            availabilityByArtifactId =
                artifacts.associate { row ->
                    val status = statuses.getValue(row.id)
                    row.id to
                        GpuArtifactAvailability(
                            locationStatus = status,
                            resolvedPath = row.path.takeIf { status == ArtifactLocationStatus.AVAILABLE },
                            primaryAction = if (status == ArtifactLocationStatus.AVAILABLE) ArtifactPrimaryAction.OPEN_AGI else ArtifactPrimaryAction.NONE,
                        )
                },
        )
    }

    private fun artifact(
        id: String,
        kind: GpuArtifactKind,
        fileName: String,
        device: GpuDeviceContext?,
        route: ArtifactOpenRoute,
        sizeBytes: Long,
    ) = GpuArtifact(
        id = id,
        kind = kind,
        path = Path.of("/Users/engineer/captures", fileName),
        sha256 = "0f1e2d3c4b5a69788796a5b4c3d2e1f0$id",
        sizeBytes = sizeBytes,
        agiVersion = if (route == ArtifactOpenRoute.AGI) "3.3.3" else null,
        device = device,
        packageName = if (device == null) null else "com.example.app",
        graphicsApi = null,
        capturedAt = Instant.parse("2024-11-15T06:32:00Z"),
        notes = "GPU capture evidence",
        openRoute = route,
    )
}

private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = toPixelMap()
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[x, y]
                val argb =
                    (color.alpha.alphaByte() shl 24) or
                        (color.redByte() shl 16) or
                        (color.greenByte() shl 8) or
                        color.blueByte()
                image.setRGB(x, y, argb)
            }
        }
    }
}

private fun Float.alphaByte(): Int = (this * 255f).toInt().coerceIn(0, 255)

private fun Color.redByte(): Int = (red * 255f).toInt().coerceIn(0, 255)

private fun Color.greenByte(): Int = (green * 255f).toInt().coerceIn(0, 255)

private fun Color.blueByte(): Int = (blue * 255f).toInt().coerceIn(0, 255)
