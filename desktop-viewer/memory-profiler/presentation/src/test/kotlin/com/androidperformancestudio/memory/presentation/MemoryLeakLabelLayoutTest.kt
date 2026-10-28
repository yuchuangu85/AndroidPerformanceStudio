package com.androidperformancestudio.memory.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.memory.model.HeapSnapshotSummary
import com.androidperformancestudio.memory.model.LeakCanaryLeak
import com.androidperformancestudio.memory.model.LeakCanaryTraceElement
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.presentation.generated.resources.Res
import com.androidperformancestudio.memory.presentation.generated.resources.leak_suspects
import com.androidperformancestudio.memory.presentation.generated.resources.lifecycle_leaks
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MemoryLeakLabelLayoutTest {
    @Test
    fun `summary warnings hug both localized leak labels in light and dark narrow workspaces`() {
        forEachAppearance { language, dark ->
            runDesktopComposeUiTest(width = 1280, height = 720) {
                var paneWidth by mutableStateOf(1280.dp)
                var fontScale by mutableStateOf(1f)
                var state by mutableStateOf(summaryFixture())
                setContent {
                    ViewerTheme(darkTheme = dark) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            Box(Modifier.width(paneWidth).fillMaxHeight()) {
                                MemoryInvestigationWorkspace(
                                    state,
                                    MemoryProfilerActions(),
                                    language,
                                    Modifier.fillMaxSize(),
                                ) {
                                    Box(it)
                                }
                            }
                        }
                    }
                }
                listOf(MemoryProfilerViewMode.ClassList, MemoryProfilerViewMode.Dominators).forEach { mode ->
                    listOf(1280.dp, 320.dp).forEach { width ->
                        runOnIdle {
                            paneWidth = width
                            state = state.copy(viewMode = mode)
                        }
                        val index = if (mode == MemoryProfilerViewMode.ClassList) 0 else 3
                        val label =
                            if (mode == MemoryProfilerViewMode.ClassList) {
                                Res.string.lifecycle_leaks
                            } else {
                                Res.string.leak_suspects
                            }
                        assertSummaryWarning(index, localizedStringResource(label, language))
                    }
                }
                runOnIdle { fontScale = 3f }
                assertSummaryWarning(3)
                runOnIdle { state = state.copy(leakSuspects = emptyList()) }
                onNodeWithTag("memory-leak-summary-warning", useUnmergedTree = true).assertDoesNotExist()
                runOnIdle {
                    fontScale = 1f
                    state =
                        state.copy(
                            viewMode = MemoryProfilerViewMode.ClassList,
                            classListSummary = MemoryClassListSummary(),
                        )
                }
                onNodeWithTag("memory-leak-summary-warning", useUnmergedTree = true).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `findings warnings stay at the visible row end for short and long names without changing selection`() {
        forEachAppearance { language, dark ->
            runDesktopComposeUiTest(width = 480, height = 720) {
                var paneWidth by mutableStateOf(480.dp)
                var selectedIndex by mutableStateOf<Int?>(null)
                var selected: MemoryLeakFinding? = null
                val description = localizedStringResource(Res.string.leak_suspects, language)
                val findings =
                    listOf(
                        MemoryLeakFinding(4, leakFixture("sample.Screen"), library = false),
                        MemoryLeakFinding(9, leakFixture(longClassName), library = true),
                    )
                setContent {
                    ViewerTheme(darkTheme = dark) {
                        MemoryLeakFindingsPane(
                            findings = findings,
                            selectedIndex = selectedIndex,
                            search = "",
                            onSearch = {},
                            scope = MemoryLeakScope.ALL,
                            onScope = {},
                            onSelect = {
                                selected = it
                                selectedIndex = it.index
                            },
                            language = language,
                            modifier = Modifier.width(paneWidth).fillMaxHeight(),
                        )
                    }
                }
                assertEquals(null, selected)
                listOf(480.dp, 180.dp).forEach { width ->
                    runOnIdle { paneWidth = width }
                    findings.forEach { finding ->
                        val index = finding.index
                        onNodeWithTag("memory-leak-finding-$index").performScrollTo().performClick().assertIsSelected()
                        assertSame(finding, selected)
                        val name = onNodeWithTag("memory-leak-finding-name-$index", useUnmergedTree = true)
                        name.assertTextEquals(finding.leak.leakingClassName)
                        if (finding.leak.leakingClassName == longClassName) name.assertEllipsized()
                        onNodeWithTag("memory-leak-finding-warning-$index", useUnmergedTree = true)
                            .assertContentDescriptionEquals(description)
                        assertWarningLayout(
                            "memory-leak-finding-name-$index",
                            "memory-leak-finding-warning-$index",
                            "memory-leak-finding-title-$index",
                            "memory-leak-findings",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `retained and path warnings remain trailing and preserve object and suspect evidence in narrow panes`() {
        forEachAppearance { language, dark ->
            runDesktopComposeUiTest(width = 480, height = 900) {
                var paneWidth by mutableStateOf(480.dp)
                var leak by mutableStateOf(leakFixture("sample.Screen"))
                val selected = mutableListOf<Long>()
                val classLookups = mutableListOf<String>()
                setContent {
                    ViewerTheme(darkTheme = dark) {
                        Box(Modifier.width(paneWidth).fillMaxHeight()) {
                            MemoryLeakPathPane(
                                leak,
                                MemoryProfilerState(),
                                MemoryProfilerActions(
                                    onSelectInstance = { selected += it },
                                    onSelectClass = { classLookups += it },
                                ),
                                language,
                            )
                        }
                    }
                }
                listOf(480.dp, 180.dp).forEach { width ->
                    listOf("sample.Screen", longClassName).forEach { className ->
                        val previousSelections = selected.toList()
                        runOnIdle {
                            paneWidth = width
                            leak = leakFixture(className)
                        }
                        onNodeWithTag("memory-leak-retained-class").performScrollTo()
                        assertEquals(previousSelections, selected)
                        assertPathWarnings(className)
                        onNodeWithTag("memory-leak-retained-object").performScrollTo().performClick()
                        assertEquals(501L, selected.last())
                        onNodeWithTag("memory-leak-path-object-1").performScrollTo().performClick()
                        assertEquals(501L, selected.last())
                        onNodeWithTag("memory-leak-suspect-edge-0", useUnmergedTree = true)
                            .performScrollTo()
                            .assertIsDisplayed()
                        onNodeWithTag("memory-leak-path-warning-0", useUnmergedTree = true).assertDoesNotExist()
                        onNodeWithTag("memory-leak-path-edge-warning-1", useUnmergedTree = true).assertDoesNotExist()
                        onNodeWithTag("memory-leak-path-warning-2", useUnmergedTree = true).assertDoesNotExist()
                        onNodeWithTag("memory-leak-path-object-0").performScrollTo().performClick()
                        assertEquals(100L, selected.last())
                        onNodeWithTag("memory-leak-path-object-2").performScrollTo().assertIsNotEnabled()
                        assertTrue(classLookups.isEmpty())
                        assertEquals(previousSelections + listOf(501L, 501L, 100L), selected)
                    }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertSummaryWarning(
        index: Int,
        label: String? = null,
    ) {
        onNodeWithTag("memory-metric-card-$index").performScrollTo()
        val name = onNodeWithTag("memory-metric-label-$index", useUnmergedTree = true)
        if (label == null) name.assertEllipsized() else name.assertTextEquals(label).assertHugsText()
        assertWarningLayout(
            "memory-metric-label-$index",
            "memory-leak-summary-warning",
            "memory-metric-label-row-$index",
            "memory-snapshot-summary",
            inlineGap = 4.dp,
        )
    }

    private fun DesktopComposeUiTest.assertPathWarnings(className: String) {
        val retained = onNodeWithTag("memory-leak-retained-class-name", useUnmergedTree = true)
        retained.assertTextEquals(className)
        if (className == longClassName) retained.assertEllipsized() else retained.assertHugsText()
        assertWarningLayout(
            "memory-leak-retained-class-name",
            "memory-leak-retained-class-warning",
            "memory-leak-retained-class",
            "memory-leak-path",
            inlineGap = 6.dp,
        )
        onNodeWithTag("memory-leak-path-object-1").performScrollTo()
        val pathName = onNodeWithTag("memory-leak-path-name-1", useUnmergedTree = true)
        pathName.assertTextEquals(className)
        if (className == longClassName) pathName.assertEllipsized()
        assertWarningLayout(
            "memory-leak-path-name-1",
            "memory-leak-path-warning-1",
            "memory-leak-path-title-1",
            "memory-leak-path",
        )
        onNodeWithTag("memory-leak-path-edge-0").performScrollTo()
        if (className == longClassName) {
            onNodeWithTag("memory-leak-path-edge-name-0", useUnmergedTree = true).assertEllipsized()
        }
        assertWarningLayout(
            "memory-leak-path-edge-name-0",
            "memory-leak-path-edge-warning-0",
            "memory-leak-path-edge-0",
            "memory-leak-path",
        )
    }

    private fun DesktopComposeUiTest.assertWarningLayout(
        textTag: String,
        warningTag: String,
        rowTag: String,
        viewportTag: String,
        inlineGap: Dp? = null,
    ) {
        val text = onNodeWithTag(textTag, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        val warning =
            onNodeWithTag(warningTag, useUnmergedTree = true)
                .assertIsDisplayed()
                .assertWidthIsEqualTo(14.dp)
                .assertHeightIsEqualTo(14.dp)
                .getUnclippedBoundsInRoot()
        val row = onNodeWithTag(rowTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        if (inlineGap == null) {
            assertTrue((warning.left - text.right).value >= 5.5f, "$warningTag overlaps $textTag")
            assertNear(row.right.value, warning.right.value)
        } else {
            assertNear(inlineGap.value, (warning.left - text.right).value)
        }
        assertNear((row.top.value + row.bottom.value) / 2f, (warning.top.value + warning.bottom.value) / 2f)
        val viewport = onNodeWithTag(viewportTag).getUnclippedBoundsInRoot()
        listOf(row, viewport, onRoot().getUnclippedBoundsInRoot()).forEach {
            assertTrue(
                warning.left >= it.left && warning.right <= it.right,
                "$warningTag clipped horizontally: $warning in $it",
            )
            assertTrue(
                warning.top >= it.top && warning.bottom <= it.bottom,
                "$warningTag clipped vertically: $warning in $it",
            )
        }
    }

    private fun SemanticsNodeInteraction.assertHugsText() {
        val layout = textLayout()
        assertEquals(1, layout.lineCount)
        assertEquals(0, layout.layoutInput.constraints.minWidth)
        assertTrue(
            layout.size.width < layout.layoutInput.constraints.maxWidth,
            "Short inline text must not fill unused row space: size=${layout.size}, " +
                "constraints=${layout.layoutInput.constraints}",
        )
    }

    private fun SemanticsNodeInteraction.assertEllipsized() {
        val layout = textLayout()
        assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        assertEquals(layout.layoutInput.maxLines, layout.lineCount)
        assertTrue(
            layout.multiParagraph.didExceedMaxLines,
            "Long text must ellipsize: lines=${layout.lineCount}, size=${layout.size}, " +
                "length=${layout.layoutInput.text.length}, overflow=${layout.layoutInput.overflow}, " +
                "constraints=${layout.layoutInput.constraints}",
        )
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun summaryFixture(): MemoryProfilerState =
        MemoryProfilerState(
            snapshotSummary = HeapSnapshotSummary("leak-label-layout"),
            classListSummary = MemoryClassListSummary(leakCount = 1),
            leakSuspects = listOf(LeakSuspect("sample.Screen", "Retained lifecycle candidate")),
        )

    private fun leakFixture(className: String): LeakCanaryLeak =
        LeakCanaryLeak(
            signature = className,
            shortDescription = "Retained lifecycle candidate",
            leakingClassName = className,
            leakingObjectId = 501L,
            trace =
                listOf(
                    LeakCanaryTraceElement(
                        className = "sample.Owner",
                        objectId = 100L,
                        leakingStatus = "NOT_LEAKING",
                        referenceName = if (className == longClassName) "retainingField".repeat(40) else "owner",
                        referenceType = "FIELD",
                        isSuspect = true,
                    ),
                    LeakCanaryTraceElement(className, objectId = 501L, leakingStatus = "leaking"),
                    LeakCanaryTraceElement("sample.Unknown", leakingStatus = "UNKNOWN"),
                ),
        )

    private val longClassName = "sample." + "ExtremelyLongRetainedActivity".repeat(40)
}

private fun assertNear(
    expected: Float,
    actual: Float,
) {
    assertTrue(abs(expected - actual) <= 0.5f, "Expected $expected, got $actual")
}

private fun forEachAppearance(block: (UiLanguage, Boolean) -> Unit) {
    listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
        listOf(false, true).forEach { dark -> block(language, dark) }
    }
}
