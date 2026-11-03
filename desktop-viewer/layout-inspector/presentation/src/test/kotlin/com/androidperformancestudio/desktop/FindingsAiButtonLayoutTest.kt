package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.application.InspectorStore
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class FindingsAiButtonLayoutTest {
    @Test
    fun `AI button stays fixed at the right edge across status changes and actions`() {
        for (width in listOf(720, 1280)) {
            for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
                for (darkTheme in listOf(false, true)) {
                    runDesktopComposeUiTest(width = width, height = 300) {
                        val initial = InspectorStore().apply { load(SampleSnapshots.dashboard) }.state
                        var state by mutableStateOf(initial)
                        var status by mutableStateOf<AiAnalysisUiState>(AiAnalysisUiState.Idle)
                        var starts = 0
                        var cancellations = 0
                        setContent {
                            ViewerTheme(darkTheme = darkTheme) {
                                CompositionLocalProvider(LocalLayoutInspectorLanguage provides language) {
                                    FindingsPane(
                                        state = state,
                                        model = InspectorPresenter.present(state, language),
                                        panelHeightDp = 300f,
                                        viewDisplayOptions = ViewDisplayOptions(),
                                        aiAnalysisUiState = status,
                                        onRunAiAnalysis = {
                                            starts++
                                            status = AiAnalysisUiState.Working
                                        },
                                        onCancelAiAnalysis = {
                                            cancellations++
                                            status = AiAnalysisUiState.Idle
                                        },
                                        onOpenSourceCandidate = null,
                                        onCanOpenSourceCandidate = null,
                                        onCanOpenSourceCandidateDirectly = null,
                                        onSelectNode = {},
                                        onSelectTimelineFrame = {},
                                        onCloseTimelineFrame = {},
                                        modifier = Modifier.fillMaxSize().testTag("findings-pane"),
                                    )
                                }
                            }
                        }
                        val button = onNodeWithTag("layout-inspector-run-ai-analysis")
                        val original = button.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        val pane = onNodeWithTag("findings-pane").fetchSemanticsNode().boundsInRoot
                        assertEquals(16f, pane.right - original.right, 1f)

                        button.performClick()
                        assertEquals(1, starts)
                        val running = button.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        assertEquals(original.right, running.right, 1f, "Starting AI must not move the trailing edge")
                        assertEquals(original, running, "The action slot must retain its bounds when changing to Cancel")
                        button.performClick()
                        assertEquals(1, cancellations)
                        assertEquals(original, button.fetchSemanticsNode().boundsInRoot)

                        val longText = if (language == UiLanguage.ENGLISH) "Long AI status " else "\u5206\u6790\u72b6\u6001"
                        val scenarios = listOf(
                            AiAnalysisUiState.Failure("E_AI_UNAVAILABLE") to null,
                            AiAnalysisUiState.Failure(longText.repeat(100)) to null,
                            AiAnalysisUiState.Idle to "Analysis complete",
                            AiAnalysisUiState.Idle to longText.repeat(100),
                            AiAnalysisUiState.Working to longText.repeat(100),
                            AiAnalysisUiState.Idle to null,
                        )
                        for ((nextStatus, summary) in scenarios) {
                            runOnIdle {
                                status = nextStatus
                                state = initial.copy(aiAnalysis = summary?.let { AiAnalysisReport("fixture-model", it) })
                            }
                            assertEquals(original, button.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
                        }
                        assertEquals(72f, original.width, 1f)
                        assertEquals(24f, original.height, 1f)
                    }
                }
            }
        }
    }
}
