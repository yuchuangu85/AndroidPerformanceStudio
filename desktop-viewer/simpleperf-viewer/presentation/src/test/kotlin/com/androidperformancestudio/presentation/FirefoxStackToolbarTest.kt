package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import com.androidperformancestudio.ui.viewerColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirefoxStackToolbarTest {
    @Test
    fun `inline implementation radios and stack chart checkboxes dispatch real settings`() =
        runDesktopComposeUiTest(width = 1400, height = 100) {
            val base = sampleReportState(ReportTab.STACK_CHART)
            var state by mutableStateOf(base.copy(workspace = base.workspace.copy(stackChartShowUserTimings = false)))
            val events = mutableListOf<String>()
            setContent {
                MaterialTheme {
                    FirefoxStackToolbar(
                        state,
                        goldenActions().copy(
                            onFlameImplementation = {
                                events += "implementation:$it"
                                state = state.copy(callStackQuery = state.callStackQuery.copy(implementation = it))
                            },
                            onStackChartShowUserTimings = {
                                events += "timings:$it"
                                state = state.copy(workspace = state.workspace.copy(stackChartShowUserTimings = it))
                            },
                            onStackChartSameWidths = {
                                events += "widths:$it"
                                state = state.copy(workspace = state.workspace.copy(stackChartSameWidths = it))
                            },
                        ),
                        viewerColors(darkTheme = false),
                    )
                }
            }

            onNodeWithText("All Frames").assertIsSelected()
            onNodeWithText("Native")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .performClick()
            onNodeWithText("Native").assertIsSelected()
            onNodeWithText("Show user timing").performClick().assertIsOn()
            onNodeWithText("Use the same width for each stack").performClick().assertIsOn()
            onNodeWithText("Invert Call Stack").assertDoesNotExist()
            assertEquals(listOf("implementation:NATIVE", "timings:true", "widths:true"), events)
            assertEquals(ImplementationFilter.NATIVE, state.callStackQuery.implementation)
        }

    @Test
    fun `filter follows settings at the right edge in a compact toolbar`() =
        runDesktopComposeUiTest(width = 1400, height = 100) {
            setContent {
                MaterialTheme {
                    FirefoxStackToolbar(sampleReportState(ReportTab.STACK_CHART), goldenActions(), viewerColors(false))
                }
            }

            val toolbar = onNodeWithTag("stack-toolbar").fetchSemanticsNode().boundsInRoot
            val widths = onNodeWithText("Use the same width for each stack").fetchSemanticsNode().boundsInRoot
            val search = onNodeWithTag("stack-filter-input").fetchSemanticsNode().boundsInRoot
            assertEquals(25f, toolbar.height, absoluteTolerance = 0.1f)
            assertTrue(search.left > widths.right)
            assertEquals(toolbar.right - 4f, search.right, absoluteTolerance = 1f)
        }

    @Test
    fun `filter draft is debounced and acknowledged without duplicate search events`() =
        runDesktopComposeUiTest(width = 1200, height = 100) {
            val queries = mutableListOf<String>()
            var state by mutableStateOf(sampleReportState(ReportTab.FLAME_GRAPH))
            setContent {
                MaterialTheme {
                    FirefoxStackToolbar(
                        state,
                        goldenActions().copy(onFlameSearch = {
                            queries += it
                            state = state.copy(callStackQuery = state.callStackQuery.copy(searchText = it))
                        }),
                        viewerColors(false),
                    )
                }
            }

            onNodeWithText("Show user timing").assertDoesNotExist()
            onNodeWithText("Invert Call Stack").assertExists()
            mainClock.autoAdvance = false
            onNodeWithTag("stack-filter-input").performTextReplacement("render")
            mainClock.advanceTimeBy(100)
            assertTrue(queries.isEmpty())
            mainClock.advanceTimeBy(200)
            waitForIdle()
            mainClock.advanceTimeBy(500)
            assertEquals(listOf("render"), queries)
        }

    @Test
    fun `clear filter dispatches immediately and does not redispatch while controller is pending`() =
        runDesktopComposeUiTest(width = 1200, height = 100) {
            val base = sampleReportState(ReportTab.FLAME_GRAPH)
            val state = base.copy(callStackQuery = base.callStackQuery.copy(searchText = "render"))
            val queries = mutableListOf<String>()
            setContent {
                MaterialTheme {
                    FirefoxStackToolbar(
                        state,
                        goldenActions().copy(onFlameSearch = { queries += it }),
                        viewerColors(false),
                    )
                }
            }
            mainClock.autoAdvance = false
            onNodeWithTag("stack-filter-clear").performClick()
            assertEquals(listOf(""), queries)
            mainClock.advanceTimeBy(500)
            assertEquals(listOf(""), queries)
        }
}
