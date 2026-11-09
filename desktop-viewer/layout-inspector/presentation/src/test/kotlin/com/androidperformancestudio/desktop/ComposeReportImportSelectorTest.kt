package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.compose_reports_importing
import com.androidperformancestudio.presentation.generated.resources.compose_reports_select_directory
import com.androidperformancestudio.presentation.generated.resources.compose_reports_select_file
import com.androidperformancestudio.presentation.generated.resources.import_compose_compiler_reports
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ComposeReportImportSelectorTest {
    @Test
    fun `mouse clicks choose each source from the status bar's selectable text`() {
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            for (source in ComposeReportImportSource.entries) {
                runDesktopComposeUiTest(width = 480, height = 320) {
                    val requests = mutableListOf<ComposeReportImportSource>()
                    setContent {
                        ViewerTheme(darkTheme = false) {
                            // Mirrors LayoutInspectorStatusBar, whose values are selectable text.
                            SelectionContainer {
                                Box(Modifier.padding(16.dp)) {
                                    ComposeReportImportSelector(false, language) { requests.add(it) }
                                }
                            }
                        }
                    }
                    val title = localizedStringResource(Res.string.import_compose_compiler_reports, language)
                    onNodeWithContentDescription(title).assertIsDisplayed().performMouseInput { click() }
                    val label = localizedStringResource(
                        if (source == ComposeReportImportSource.FILE) {
                            Res.string.compose_reports_select_file
                        } else {
                            Res.string.compose_reports_select_directory
                        },
                        language,
                    )
                    onNodeWithText(label).assertIsDisplayed().performMouseInput { click() }
                    waitForIdle()

                    assertEquals(listOf(source), requests)
                    onNodeWithText(label).assertDoesNotExist()
                }
            }
        }
    }

    @Test
    fun `import in progress shows progress and cannot reopen the menu`() =
        runDesktopComposeUiTest(width = 480, height = 320) {
            val language = UiLanguage.SIMPLIFIED_CHINESE
            var requests = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    ComposeReportImportSelector(true, language) { requests++ }
                }
            }
            val selector = onNodeWithContentDescription(
                localizedStringResource(Res.string.import_compose_compiler_reports, language),
            ).assertIsNotEnabled()
            onNodeWithText(localizedStringResource(Res.string.compose_reports_importing, language)).assertIsDisplayed()
            selector.performMouseInput { click() }
            onNodeWithText(localizedStringResource(Res.string.compose_reports_select_file, language)).assertDoesNotExist()
            assertEquals(0, requests)
        }

    @Test
    fun `status bar import uses the same shared dropdown as the capture target`() {
        val page = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val importer = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/ComposeCompilerReportImport.kt"))
        val statusBar = page.substringAfter("private fun LayoutInspectorStatusBar(").substringBefore("private fun StatusBarValue(")
        val targetSelector = page.substringAfter("private fun CaptureTargetSelector(").substringBefore("private fun DeviceSelector(")

        assertTrue(targetSelector.contains("DropdownSelector("))
        assertTrue(importer.substringAfter("internal fun ComposeReportImportSelector(").contains("DropdownSelector("))
        assertTrue(statusBar.contains("ComposeReportImportSelector("))
        assertTrue(!statusBar.contains("DropdownMenu("), "The status bar must not host a separate import menu")
    }
}
