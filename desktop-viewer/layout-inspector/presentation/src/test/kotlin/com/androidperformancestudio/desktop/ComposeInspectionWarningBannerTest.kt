package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_hide_details
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_project_hint
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_show_details
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_unavailable
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ComposeInspectionWarningBannerTest {
    @Test
    fun `project hint is visible while raw reason stays hidden until requested`() {
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            runComposeUiTest {
                val reason = mutableStateOf<String?>("Compose tree unavailable")
                setContent {
                    ViewerTheme(darkTheme = false) {
                        reason.value?.let { ComposeInspectionWarningBanner(it, language) }
                    }
                }

                onNodeWithTag("layout-compose-fallback-warning").assertIsDisplayed()
                val summary = localizedStringResource(Res.string.compose_inspection_unavailable, language) + "\n" +
                    localizedStringResource(Res.string.compose_inspection_project_hint, language)
                onNodeWithText(summary).assertIsDisplayed()
                onNodeWithText(requireNotNull(reason.value), substring = true).assertDoesNotExist()
                val copyLabel = if (language == UiLanguage.ENGLISH) "Copy full message" else "复制完整提示"
                val copyActions =
                    onNodeWithText(summary).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                assertTrue(copyActions.any { it.label == copyLabel })
                onNodeWithContentDescription(
                    localizedStringResource(Res.string.compose_inspection_show_details, language),
                ).assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .performClick()
                onNodeWithTag("layout-compose-fallback-warning-details").assertIsDisplayed()
                val detailActions =
                    onNodeWithText(requireNotNull(reason.value))
                        .fetchSemanticsNode()
                        .config[SemanticsActions.CustomActions]
                assertTrue(detailActions.any { it.label == copyLabel })

                runOnIdle { reason.value = null }
                onNodeWithTag("layout-compose-fallback-warning").assertDoesNotExist()
                onNodeWithTag("layout-compose-fallback-warning-details").assertDoesNotExist()

                runOnIdle { reason.value = "Compose tree unavailable" }
                onNodeWithTag("layout-compose-fallback-warning").assertIsDisplayed()
                onNodeWithTag("layout-compose-fallback-warning-details").assertDoesNotExist()
                onNodeWithContentDescription(
                    localizedStringResource(Res.string.compose_inspection_show_details, language),
                ).assertIsDisplayed()
            }
        }
    }

    @Test
    fun `details can be expanded and collapsed in both languages without changing the reason`() {
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            runComposeUiTest {
                val reason = longReason()
                setContent {
                    ViewerTheme(darkTheme = false) {
                        ComposeInspectionWarningBanner(reason, language)
                    }
                }

                onNodeWithTag("layout-compose-fallback-warning-details").assertDoesNotExist()
                onNodeWithContentDescription(
                    localizedStringResource(Res.string.compose_inspection_show_details, language),
                ).performClick()
                onNodeWithTag("layout-compose-fallback-warning-details").assertIsDisplayed()
                onNodeWithText(reason).assertTextEquals(reason)

                onNodeWithContentDescription(
                    localizedStringResource(Res.string.compose_inspection_hide_details, language),
                ).performClick()
                onNodeWithTag("layout-compose-fallback-warning-details").assertDoesNotExist()
                onNodeWithTag("layout-compose-fallback-warning").assertIsDisplayed()
            }
        }
    }

    @Test
    fun `changing the reason collapses details and replaces the complete error`() =
        runComposeUiTest {
            val reason = mutableStateOf(longReason())
            setContent {
                ViewerTheme(darkTheme = false) {
                    ComposeInspectionWarningBanner(reason.value, UiLanguage.ENGLISH)
                }
            }

            onNodeWithContentDescription("Details").performClick()
            onNodeWithTag("layout-compose-fallback-warning-details").assertIsDisplayed()

            val replacement = "Compose inspection disconnected\nOriginal failure from the next capture"
            runOnIdle { reason.value = replacement }
            onNodeWithTag("layout-compose-fallback-warning-details").assertDoesNotExist()
            onNodeWithText(longReason()).assertDoesNotExist()
            onNodeWithContentDescription("Details").performClick()
            onNodeWithText(replacement).assertTextEquals(replacement)
        }

    @Test
    fun `long multiline error tail is laid out completely and scrollable within a narrow banner`() =
        runComposeUiTest {
            val reason = longReason()
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column(Modifier.width(220.dp)) {
                        ComposeInspectionWarningBanner(reason, UiLanguage.ENGLISH)
                        Text("Inspector content", Modifier.testTag("inspector-content"))
                    }
                }
            }

            val summary = onNodeWithTag("layout-compose-fallback-warning-summary")
            val summaryLayouts = mutableListOf<TextLayoutResult>()
            summary.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(summaryLayouts) }
            assertFalse(summaryLayouts.single().hasVisualOverflow)
            onNodeWithText(reason, substring = true).assertDoesNotExist()

            onNodeWithContentDescription("Details").assertIsDisplayed().performClick()
            val details = onNodeWithTag("layout-compose-fallback-warning-details")
            details.assertIsDisplayed()
            val detailBounds = details.getUnclippedBoundsInRoot()
            val detailHeight = detailBounds.bottom - detailBounds.top
            assertTrue(detailHeight > 0.dp && detailHeight <= 200.dp)
            assertTrue(detailBounds.right - detailBounds.left <= 220.dp)

            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(reason)
                .assertTextEquals(reason)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.hasVisualOverflow)
            assertEquals(reason.length, layout.getLineEnd(layout.lineCount - 1))

            val range = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(range.maxValue() > 0f)
            details.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                assertTrue(scroll(0f, range.maxValue()))
            }
            waitForIdle()
            val scrolledRange = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(scrolledRange.maxValue(), scrolledRange.value(), 1f)
            val textPosition = onNodeWithText(reason).fetchSemanticsNode().positionInRoot
            val tailBounds = layout.getBoundingBox(reason.lastIndex)
            val viewport = details.fetchSemanticsNode().boundsInRoot
            assertTrue(textPosition.y + tailBounds.top >= viewport.top - 1f)
            assertTrue(textPosition.y + tailBounds.bottom <= viewport.bottom + 1f)
            onNodeWithTag("inspector-content").assertIsDisplayed()
        }

    @Test
    fun `attach result path and device logcat are only shown inside details`() {
        val reason = "Full Compose inspection failed: Failed to attach Compose inspector agent (PID 8908): " +
            "Agent attach failed (result=1): Unable to dlopen /data/user/0/com.tcl.android.launcher/lib_ui_inspector_agent.so\n" +
            "Device logcat:\n10-31 14:42:30.562 8908 8908 E ActivityThread: dlopen failed: library not found"
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            runComposeUiTest {
                setContent {
                    ViewerTheme(darkTheme = false) { ComposeInspectionWarningBanner(reason, language) }
                }
                val hint = localizedStringResource(Res.string.compose_inspection_project_hint, language)
                onNodeWithText(hint, substring = true).assertIsDisplayed()
                for (fragment in listOf("result=1", "/data/user/0/", "Device logcat", "ActivityThread")) {
                    onNodeWithText(fragment, substring = true).assertDoesNotExist()
                }
                onNodeWithText(localizedStringResource(Res.string.compose_inspection_show_details, language)).performClick()
                onNodeWithText(reason).assertIsDisplayed().assertTextEquals(reason)
                onNodeWithContentDescription(localizedStringResource(Res.string.compose_inspection_hide_details, language)).performClick()
                onNodeWithText("result=1", substring = true).assertDoesNotExist()
            }
        }
    }

    private fun longReason(): String =
        buildString {
            appendLine("Compose inspection failed: java.lang.RuntimeException")
            appendLine("at android.app.ActivityThread.main(ActivityThread.java:8000)")
            repeat(40) { index ->
                appendLine("at dalvik.system.PathClassLoader.loadClass(PathClassLoader.java:${100 + index})")
            }
            append("Caused by: java.lang.ClassNotFoundException: sample.inspection.MissingInspector")
        }
}
