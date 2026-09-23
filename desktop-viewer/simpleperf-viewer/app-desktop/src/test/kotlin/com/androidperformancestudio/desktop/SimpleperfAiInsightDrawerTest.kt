package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SimpleperfAiInsightDrawerTest {
    @Test
    fun `wide workspace docks insights and close restores the workspace`() =
        runDesktopComposeUiTest(width = 1300, height = 700) {
            var insight by mutableStateOf<SimpleperfAiInsight?>(sampleInsight())
            setContent {
                ViewerTheme(darkTheme = false) {
                    SimpleperfAiWorkspace(
                        insight = insight,
                        language = UiLanguage.ENGLISH,
                        darkTheme = false,
                        onCloseInsight = { insight = null },
                        onOpenSourceCandidate = null,
                    ) {
                        Box(Modifier.fillMaxSize().testTag("workspace-fixture"))
                    }
                }
            }

            val workspace = onNodeWithTag("simpleperf-workspace-content").fetchSemanticsNode().boundsInRoot
            val drawer = onNodeWithTag("simpleperf-ai-insight-drawer").fetchSemanticsNode().boundsInRoot
            assertTrue(workspace.right <= drawer.left + 1f)
            onNodeWithText("Close").performClick()
            onNodeWithTag("simpleperf-ai-insight-drawer").assertDoesNotExist()
            onNodeWithTag("workspace-fixture").assertExists()
        }

    @Test
    fun `narrow workspace overlays the drawer instead of crushing the profiler`() =
        runDesktopComposeUiTest(width = 800, height = 600) {
            setContent {
                ViewerTheme(darkTheme = false) {
                    SimpleperfAiWorkspace(
                        insight = sampleInsight(),
                        language = UiLanguage.ENGLISH,
                        darkTheme = false,
                        onCloseInsight = {},
                        onOpenSourceCandidate = null,
                    ) {
                        Box(Modifier.fillMaxSize().testTag("workspace-fixture"))
                    }
                }
            }

            val workspace = onNodeWithTag("workspace-fixture").fetchSemanticsNode().boundsInRoot
            val drawer = onNodeWithTag("simpleperf-ai-insight-drawer").fetchSemanticsNode().boundsInRoot
            assertTrue(workspace.right > drawer.left)
            assertTrue(drawer.left > workspace.left)
        }

    @Test
    fun `drawer retains analyzed evidence scope and source navigation`() =
        runDesktopComposeUiTest(width = 500, height = 600) {
            var sourceId: String? = null
            var closed = false
            setContent {
                ViewerTheme(darkTheme = false) {
                    SimpleperfAiInsightDrawer(
                        insight = sampleInsight(),
                        language = UiLanguage.ENGLISH,
                        onClose = { closed = true },
                        onOpenSourceCandidate = { sourceId = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            onNodeWithText("AI Analysis · test-model").assertExists()
            onNodeWithText("Session: Gallery capture").assertExists()
            onNodeWithText("Evidence scope: function renderFrame").assertExists()
            onNodeWithText("Evidence: 1 hotspot(s) · 120 samples").assertExists()
            onNodeWithText("Open Source").performClick()
            assertEquals("candidate-1", sourceId)
            onNodeWithText("Close").performClick()
            assertTrue(closed)
        }

    private fun sampleInsight(): SimpleperfAiInsight =
        SimpleperfAiInsight(
            result =
                SimpleperfAiAnalysisReport(
                    model = "test-model",
                    summary = "Measured hot path",
                    findings =
                        listOf(
                            SimpleperfAiFinding(
                                title = "renderFrame hotspot",
                                explanation = "Observed in the selected samples",
                                recommendation = "Inspect the render path",
                                confidence = 0.82f,
                                sourceCandidateIds = listOf("candidate-1"),
                            ),
                        ),
                ),
            sessionName = "Gallery capture",
            evidenceScope = "function renderFrame",
            evidenceCount = 1,
            sampleCount = 120,
        )
}
