package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceWorkspaceLayoutWiringTest {
    @Test
    fun `source workspace keeps all three panes scrollable and the two list widths draggable`() {
        val page = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SourceWorkspacesPage.kt"),
        )
        val codeViewer = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SourceCodeViewer.kt"),
        )
        val searchField = Files.readString(
            Path.of("../ui-components/src/main/kotlin/com/androidperformancestudio/ui/search/CompactSearchField.kt"),
        )
        val workspacePane = page
            .substringAfter("private fun WorkspaceListPane(")
            .substringBefore("private fun SourceFileListPane(")
        val filePane = page
            .substringAfter("private fun SourceFileListPane(")
            .substringBefore("private fun BoxScope.SourceWorkspacePaneScrollbars(")
        val browser = page
            .substringAfter("private fun SourceBrowser(")
            .substringBefore("private fun copyLocation(")

        val paneLayout = page.substringBefore("private fun SourceWorkspaceResizeSeparator")

        assertTrue(page.contains("BoxWithConstraints(Modifier.fillMaxSize())"))
        assertTrue(page.contains("SourceWorkspacePaneLayout.fit(paneWidths, availableWidthDp)"))
        assertEquals(1, paneLayout.split("SourceWorkspaceResizeSeparator").size - 1)
        assertTrue(page.contains("SourceWorkspacePaneLayout.dragWorkspaces("))
        assertTrue(page.contains("SourceWorkspacePaneLayout.dragFiles("))

        listOf(workspacePane, filePane).forEach { pane ->
            assertTrue(pane.contains("rememberLazyListState()"))
            assertTrue(pane.contains("rememberScrollState()"))
            assertTrue(pane.contains("horizontalScroll(horizontalScrollState)"))
            assertTrue(pane.contains("SourceWorkspacePaneScrollbars(listState, horizontalScrollState)"))
        }
        assertTrue(browser.contains("selectedFile = selectedFile"))
        assertTrue(browser.contains("modifier = Modifier.width(filesPaneWidth.dp).fillMaxHeight()"))
        assertTrue(browser.contains("SourceWorkspaceResizeSeparator(onResizeFilesPane)"))
        assertTrue(filePane.contains("fileSearchQuery"))
        assertTrue(filePane.contains("CompactSearchField("))
        assertTrue(filePane.contains(".height(28.dp)"))
        assertTrue(filePane.contains("SourceFileTree.matchingFiles(files, fileSearchQuery)"))
        assertTrue(filePane.contains("Res.string.source_search_files"))
        assertTrue(filePane.contains("SourceFileTree.defaultCollapsedDirectories(files)"))
        assertTrue(filePane.contains("LaunchedEffect(files, selectedFile)"))
        assertTrue(filePane.contains("SourceFileTree.rows(matchingFiles, effectiveCollapsedDirectories)"))
        assertTrue(filePane.contains("SourceFileTreeRow.Directory"))
        assertTrue(filePane.contains("SourceFileTreeRow.File"))
        assertTrue(filePane.contains("SourceFileTree.ancestorDirectories(selectedFile.orEmpty())"))
        assertTrue(filePane.contains("SourceFileTreeRowContainer("))
        assertTrue(filePane.contains("SourceFileTreeDisclosure("))
        assertTrue(filePane.contains("SourceFileTreeFolderIcon(expanded = row.expanded)"))
        assertTrue(filePane.contains("SourceFileTreeFileIcon(row.sourceFile.language.sourceFileTreeIconKind())"))
        assertTrue(filePane.contains(".height(SourceFileTreeRowLayout.HEIGHT_DP.dp)"))
        assertTrue(filePane.contains("depth * SourceFileTreeRowLayout.INDENT_DP"))
        assertTrue(filePane.contains("colors.selectedRow"))
        assertTrue(filePane.contains("ViewerTypography.secondary.fontSize"))
        assertTrue(filePane.contains("ViewerTypography.dense.lineHeight"))
        assertFalse(filePane.contains("items(files, key = { it.relativePath })"))
        assertFalse(filePane.contains("\"▾\""))
        assertFalse(filePane.contains("\"▸\""))

        assertTrue(codeViewer.contains("SourceCodeSearchBar("))
        assertTrue(codeViewer.contains("CompactSearchField("))
        assertTrue(codeViewer.contains("CompactSearchNavigationButton("))
        assertTrue(codeViewer.contains("sourceSearchMatches(highlightedLines, codeSearchQuery)"))
        assertTrue(codeViewer.contains("Res.string.source_search_code"))
        assertFalse(codeViewer.contains("OutlinedTextField("))
        assertTrue(searchField.contains("BasicTextField("))
        assertTrue(searchField.contains(".height(22.dp)"))
        assertTrue(searchField.contains("RoundedCornerShape(3.dp)"))
        assertTrue(searchField.contains("colors.sectionBackground.copy(alpha = 0.3f)"))
        assertTrue(searchField.contains("KeyboardOptions(imeAction = ImeAction.Search)"))
        assertTrue(codeViewer.contains("HorizontalScrollbar("))
        assertTrue(codeViewer.contains("VerticalScrollbar("))
        assertTrue(codeViewer.contains("rememberScrollbarAdapter(horizontalScrollState)"))
        assertTrue(codeViewer.contains("rememberScrollbarAdapter(listState)"))
    }
}
