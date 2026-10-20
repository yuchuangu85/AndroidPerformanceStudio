package com.androidperformancestudio.gpu.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.gpu.model.AgiCapability
import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.gpu.model.ArtifactLocationStatus
import com.androidperformancestudio.gpu.model.ArtifactOpenRoute
import com.androidperformancestudio.gpu.model.GpuArtifact
import com.androidperformancestudio.gpu.model.GpuArtifactKind
import com.androidperformancestudio.gpu.model.GpuDeviceContext
import com.androidperformancestudio.gpu.presentation.generated.resources.Res
import com.androidperformancestudio.gpu.presentation.generated.resources.artifact_search_placeholder
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GpuAnalysisHubScreenTest {
    @Test
    fun `wide workspace keeps toolbar, summary, rail, tabs and both insight rails in order`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { ViewerTheme(darkTheme = true) { hubWithToolbar(hubState(), noopActions(), UiLanguage.ENGLISH) } }

            val toolbar = onNodeWithTag("gpu-analysis-toolbar").fetchSemanticsNode().boundsInRoot
            val summary = onNodeWithTag("gpu-summary-indexed").fetchSemanticsNode().boundsInRoot
            val rail = onNodeWithTag("gpu-artifact-rail").fetchSemanticsNode().boundsInRoot
            val tabs = onNodeWithTag("gpu-hub-tabs").fetchSemanticsNode().boundsInRoot
            val capability = onNodeWithTag("gpu-capability-rail").fetchSemanticsNode().boundsInRoot
            val openWith = onNodeWithTag("gpu-open-with-rail").fetchSemanticsNode().boundsInRoot

            assertTrue(toolbar.bottom <= summary.top, "the analysis toolbar stays above the summary row")
            assertTrue(summary.bottom <= rail.top, "the summary row stays above the workspace body")
            assertTrue(rail.right <= tabs.left, "the artifact rail stays left of the analysis center")
            assertTrue(tabs.right <= capability.left, "the insight rail stays right of the analysis center")
            assertTrue(capability.bottom <= openWith.top, "capability stays above the open-with panel")
        }

    @Test
    fun `analysis toolbar exposes the three documented integration actions`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var launched = 0
            var imported = 0
            var traceAnalyzer = 0
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(
                        hubState(),
                        noopActions().copy(
                            onLaunchAgi = { launched++ },
                            onImportArtifact = { imported++ },
                            onOpenTraceAnalyzer = { traceAnalyzer++ },
                        ),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithText("Analysis tools").assertIsDisplayed()
            onNodeWithTag("gpu-action-launch-agi").assertIsEnabled().performClick()
            onNodeWithTag("gpu-action-import-artifact").assertIsEnabled().performClick()
            onNodeWithTag("gpu-action-open-trace-analyzer").assertIsEnabled().performClick()

            assertEquals(1, launched)
            assertEquals(1, imported)
            assertEquals(1, traceAnalyzer)
        }

    @Test
    fun `summary reports local index facts instead of AGI frame counters`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val state =
                hubState(
                    artifacts = listOf(frameArtifact, traceArtifact, missingArtifact),
                    statuses =
                        mapOf(
                            frameArtifact.id to ArtifactLocationStatus.AVAILABLE,
                            traceArtifact.id to ArtifactLocationStatus.AVAILABLE,
                            missingArtifact.id to ArtifactLocationStatus.MISSING,
                        ),
                )
            setContent { ViewerTheme(darkTheme = true) { hubWithToolbar(state, noopActions(), UiLanguage.ENGLISH) } }

            onNodeWithTag("gpu-summary-indexed").assertContentDescriptionEquals("Indexed artifacts: 3")
            onNodeWithTag("gpu-summary-available").assertContentDescriptionEquals("Verified available: 2")
            onNodeWithTag("gpu-summary-attention").assertContentDescriptionEquals("Needs attention: 1")
            onNodeWithTag("gpu-summary-capability").assertContentDescriptionEquals("AGI capability: 3.3.3")
            onNodeWithText("2 of 3 verified by size and SHA-256").assertExists()
            onNodeWithText("1 of 3 indexed artifacts need recovery").assertExists()

            // The reference frame-profile panels must not exist as rendered evidence anywhere.
            listOf("GPU Counters", "Frame Timeline", "Render Passes", "Draw Calls", "Shader Hotspots", "Tile Overdraw", "FPS (Average)", "GPU Busy").forEach { forbidden ->
                onNodeWithText(forbidden).assertDoesNotExist()
            }
        }

    @Test
    fun `artifact rail search narrows the visible capture cards`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var state by mutableStateOf(hubState(artifacts = listOf(frameArtifact, traceArtifact)))
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(
                        state,
                        noopActions().copy(onFilterArtifacts = { state = state.copy(artifactFilter = it) }),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("gpu-artifact-card-${frameArtifact.id}").assertExists()
            onNodeWithTag("gpu-artifact-card-${traceArtifact.id}").assertExists()

            onNodeWithContentDescription(localizedStringResource(Res.string.artifact_search_placeholder, UiLanguage.ENGLISH))
                .performTextInput("pftrace")
            onNodeWithTag("gpu-artifact-card-${traceArtifact.id}").assertExists()
            onNodeWithTag("gpu-artifact-card-${frameArtifact.id}").assertDoesNotExist()

            runOnIdle { state = state.copy(artifactFilter = "unmatched-query") }
            onNodeWithText("No artifact matches this search.").assertExists()
        }

    @Test
    fun `selecting an artifact in the rail selects it in the workspace`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var state by mutableStateOf(hubState(artifacts = listOf(frameArtifact, traceArtifact)))
            val selected = mutableListOf<String>()
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(
                        state,
                        noopActions().copy(
                            onSelectArtifact = { id ->
                                selected += id
                                state = state.copy(selectedArtifactId = id)
                            },
                        ),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithTag("gpu-artifact-card-${traceArtifact.id}").performClick()
            assertEquals(listOf(traceArtifact.id), selected)
            onNodeWithTag("gpu-artifact-card-${traceArtifact.id}").assertIsSelected()
            onNodeWithTag("gpu-artifact-preview").assertExists()
            onNodeWithTag("gpu-summary-indexed").assertContentDescriptionEquals("Indexed artifacts: 2")
        }

    @Test
    fun `hub tabs switch between the documented replacement surfaces`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var state by mutableStateOf(hubState())
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(state, noopActions().copy(onSelectHubTab = { state = state.copy(hubTab = it) }), UiLanguage.ENGLISH)
                }
            }

            onNodeWithTag("gpu-artifact-preview").assertExists()

            onNodeWithTag("segmented-selector-item-1").performClick()
            onNodeWithTag("gpu-artifact-table").assertExists()
            listOf("Kind", "File", "Device", "Package", "API", "Captured", "Status", "Action").forEach { column ->
                onNodeWithText(column).assertExists()
            }

            onNodeWithTag("segmented-selector-item-2").performClick()
            onNodeWithTag("gpu-related-evidence").assertExists()
            onNodeWithText("Related Evidence").assertExists()
            onNodeWithText("Perfetto Trace · 1").assertExists()

            onNodeWithTag("segmented-selector-item-3").performClick()
            onNodeWithTag("gpu-tool-capability").assertExists()
            onNodeWithText("Closed until a stable parser exists").assertExists()
            listOf(
                "GPU busy and vertex/fragment counters",
                "Shader hotspots",
                "Render pass table",
                "Draw call table",
                "Tile overdraw and frame attachment preview",
            ).forEach { closed -> onNodeWithText("• $closed").assertExists() }
            onAllNodesWithText("Launch supported").assertCountEquals(2)
            onAllNodesWithText("Artifact open supported").assertCountEquals(2)
        }

    @Test
    fun `unverified locations disable opening while preserving recovery actions`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var state by
                mutableStateOf(
                    hubState(
                        statuses = mapOf(frameArtifact.id to ArtifactLocationStatus.AVAILABLE, traceArtifact.id to ArtifactLocationStatus.MISSING),
                        selectedArtifactId = traceArtifact.id,
                    ),
                )
            val callbacks = mutableListOf<String>()
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(
                        state,
                        noopActions().copy(
                            onOpenArtifact = { callbacks += "open" },
                            onVerifyArtifact = { callbacks += "verify" },
                            onRevealArtifact = { callbacks += "reveal" },
                            onRelocateArtifact = { callbacks += "relocate" },
                        ),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onAllNodesWithText("File missing").assertCountEquals(2)
            onNodeWithTag("gpu-open-primary").assertIsNotEnabled()
            onNodeWithTag("gpu-preview-open").assertIsNotEnabled()
            onNodeWithTag("gpu-open-verify").assertIsEnabled().performClick()
            onNodeWithTag("gpu-open-reveal").assertIsEnabled().performClick()
            onNodeWithTag("gpu-open-relocate").assertIsEnabled().performClick()
            assertEquals(listOf("verify", "reveal", "relocate"), callbacks)

            runOnIdle { state = state.copy(isBusy = true) }
            onNodeWithTag("gpu-open-verify").assertIsNotEnabled()
            onNodeWithTag("gpu-open-reveal").assertIsNotEnabled()
            onNodeWithTag("gpu-open-relocate").assertIsNotEnabled()
            onNodeWithTag("gpu-action-launch-agi").assertIsNotEnabled()
        }

    @Test
    fun `device errors and refresh state leave artifact evidence available`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var refreshed = 0
            var state by mutableStateOf(hubState(deviceError = "ADB is unavailable"))
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(state, noopActions().copy(onRefreshDevices = { refreshed++ }), UiLanguage.ENGLISH)
                }
            }

            onNodeWithText("ADB is unavailable").assertExists()
            onNodeWithText("No authorized Android devices are connected.").assertExists()
            onNodeWithText("Refresh devices").assertIsEnabled().performClick()
            assertEquals(1, refreshed)
            onNodeWithTag("gpu-artifact-rail").assertExists()

            runOnIdle { state = state.copy(isRefreshingDevices = true, deviceError = null) }
            onNodeWithText("Reading graphics properties…").assertExists()
            onNodeWithText("Refresh devices").assertIsNotEnabled()
            onNodeWithText("No authorized Android devices are connected.").assertDoesNotExist()
            onNodeWithText("ADB is unavailable").assertDoesNotExist()
        }

    @Test
    fun `selecting a connected device dispatches its serial`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            var selectedSerial: String? = null
            val device = GpuDeviceContext("serial-1", "Test Phone", 34, "Fixture Vendor", "Fixture Renderer", "Fixture Driver")
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(
                        hubState(devices = listOf(device)),
                        noopActions().copy(onSelectDevice = { selectedSerial = it }),
                        UiLanguage.ENGLISH,
                    )
                }
            }

            onNodeWithContentDescription("Device / Graphics Context").performClick()
            onNodeWithText("Test Phone · serial-1").performClick()

            assertEquals("serial-1", selectedSerial)
        }

    @Test
    fun `narrow workspace stacks the rail and insights instead of dropping them`() =
        runDesktopComposeUiTest(width = 880, height = 640) {
            setContent { ViewerTheme(darkTheme = false) { hubWithToolbar(hubState(), noopActions(), UiLanguage.ENGLISH) } }

            val indexed = onNodeWithTag("gpu-summary-indexed").fetchSemanticsNode().boundsInRoot
            val capabilityCard = onNodeWithTag("gpu-summary-capability").fetchSemanticsNode().boundsInRoot
            val rail = onNodeWithTag("gpu-artifact-rail").fetchSemanticsNode().boundsInRoot
            val tabs = onNodeWithTag("gpu-hub-tabs").fetchSemanticsNode().boundsInRoot

            assertTrue(indexed.bottom <= capabilityCard.top, "the summary row wraps into two rows")
            assertTrue(rail.bottom <= tabs.top, "the artifact rail stays above the analysis center")
            onNodeWithTag("gpu-capability-rail").assertExists()
            onNodeWithTag("gpu-open-with-rail").assertExists()
            onNodeWithTag("gpu-artifact-selector").assertExists()
        }

    @Test
    fun `unavailable capability keeps launch disabled without hiding artifact evidence`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val state = hubState(capability = null)
            setContent { ViewerTheme(darkTheme = true) { hubWithToolbar(state, noopActions(), UiLanguage.ENGLISH) } }

            onNodeWithText("Launch AGI").assertIsNotEnabled()
            onNodeWithTag("gpu-summary-capability").assertContentDescriptionEquals("AGI capability: AGI is not configured")
            onNodeWithText("No stable automation arguments were detected.").assertExists()
            onNodeWithTag("gpu-artifact-rail").assertExists()
        }

    @Test
    fun `short native content viewport keeps every recovery action reachable`() =
        runDesktopComposeUiTest(width = 1064, height = 560) {
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(hubState(selectedArtifactId = traceArtifact.id), noopActions(), UiLanguage.ENGLISH)
                }
            }

            onNodeWithTag("gpu-analysis-toolbar").assertExists()
            onNodeWithTag("gpu-artifact-rail").assertExists()
            onNodeWithTag("gpu-open-relocate").performScrollTo().assertIsEnabled()
            onNodeWithTag("gpu-open-verify").assertIsEnabled()
            onNodeWithTag("gpu-open-reveal").assertIsEnabled()
        }

    @Test
    fun `wide but short workspace expands the analysis panel and keeps its views reachable`() =
        runDesktopComposeUiTest(width = 1440, height = 620) {
            var state by mutableStateOf(hubState())
            setContent {
                ViewerTheme(darkTheme = true) {
                    hubWithToolbar(state, noopActions().copy(onSelectHubTab = { state = state.copy(hubTab = it) }), UiLanguage.ENGLISH)
                }
            }

            val tabs = onNodeWithTag("gpu-hub-tabs").fetchSemanticsNode().boundsInRoot
            val preview = onNodeWithTag("gpu-artifact-preview").fetchSemanticsNode().boundsInRoot
            assertTrue(preview.top >= tabs.bottom, "the analysis panel follows the tab strip")
            assertTrue(preview.height > 0f, "the expanded analysis panel keeps a positive height")

            onNodeWithTag("segmented-selector-item-1").performClick()
            onNodeWithTag("gpu-artifact-table").assertExists()
            onNodeWithTag("gpu-artifact-row-action-${frameArtifact.id}").assertIsEnabled()
        }

    @Composable
    private fun hubWithToolbar(
        state: GpuIntegrationState,
        actions: GpuIntegrationActions,
        language: UiLanguage,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier.fillMaxSize()) {
            GpuAnalysisToolbar(
                state = state,
                actions = actions,
                selectedArtifact = state.artifacts.firstOrNull { it.id == state.selectedArtifactId },
                language = language,
                modifier = Modifier.fillMaxWidth(),
            )
            GpuAnalysisHubScreen(state, actions, language, Modifier.weight(1f))
        }
    }

    private fun noopActions() = GpuIntegrationActions(onOpenArtifact = {}, onVerifyArtifact = {}, onRevealArtifact = {}, onRelocateArtifact = {})

    private fun hubState(
        artifacts: List<GpuArtifact> = listOf(frameArtifact, traceArtifact),
        statuses: Map<String, ArtifactLocationStatus> = artifacts.associate { it.id to ArtifactLocationStatus.AVAILABLE },
        capability: AgiCapability? = agiCapability,
        devices: List<GpuDeviceContext> = emptyList(),
        deviceError: String? = null,
        selectedArtifactId: String? = null,
    ) = GpuIntegrationState(
        capability = capability,
        artifacts = artifacts,
        selectedArtifactId = selectedArtifactId ?: artifacts.firstOrNull()?.id,
        devices = devices,
        deviceError = deviceError,
        availabilityByArtifactId =
            artifacts.associate { artifact ->
                val status = statuses.getValue(artifact.id)
                artifact.id to
                    GpuArtifactAvailability(
                        locationStatus = status,
                        resolvedPath = artifact.path.takeIf { status == ArtifactLocationStatus.AVAILABLE },
                        primaryAction = if (status == ArtifactLocationStatus.AVAILABLE) ArtifactPrimaryAction.OPEN_AGI else ArtifactPrimaryAction.NONE,
                    )
            },
    )

    private companion object {
        private val frameArtifact =
            GpuArtifact(
                id = "frame",
                kind = GpuArtifactKind.AGI_FRAME_PROFILE,
                path = Path.of("/tmp/com.example.app_frame.gputrace"),
                sha256 = "sha256-frame",
                sizeBytes = 2048,
                agiVersion = "3.3.3",
                device = GpuDeviceContext("serial-pixel", "Pixel 8 Pro", 34, "Arm", "Mali-G715", "r45p0"),
                packageName = "com.example.app",
                graphicsApi = null,
                capturedAt = Instant.parse("2024-11-15T06:32:00Z"),
                notes = "Frame profile",
                openRoute = ArtifactOpenRoute.AGI,
            )
        private val traceArtifact =
            GpuArtifact(
                id = "trace",
                kind = GpuArtifactKind.PERFETTO_TRACE,
                path = Path.of("/tmp/com.example.app_trace.pftrace"),
                sha256 = "sha256-trace",
                sizeBytes = 4096,
                agiVersion = null,
                device = GpuDeviceContext("serial-pixel", "Pixel 8 Pro", 34, "Arm", "Mali-G715", "r45p0"),
                packageName = "com.example.app",
                graphicsApi = null,
                capturedAt = Instant.parse("2024-11-15T06:40:00Z"),
                notes = null,
                openRoute = ArtifactOpenRoute.PERFETTO,
            )
        private val missingArtifact =
            GpuArtifact(
                id = "missing",
                kind = GpuArtifactKind.SCREENSHOT,
                path = Path.of("/tmp/screenshot.png"),
                sha256 = "sha256-missing",
                sizeBytes = 1024,
                agiVersion = null,
                device = null,
                packageName = null,
                graphicsApi = null,
                capturedAt = null,
                notes = null,
                openRoute = ArtifactOpenRoute.DESKTOP,
            )
        private val agiCapability =
            AgiCapability(
                executable = Path.of("/tmp/agi"),
                version = "3.3.3",
                launchSupported = true,
                artifactOpenSupported = true,
                launchMode = AgiLaunchMode.VERIFIED_CLI,
                supportedArguments = setOf("--frame-profile", "--output"),
                warnings = emptyList(),
            )
    }
}
