package com.androidperformancestudio.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import com.androidperformancestudio.ui_components.generated.resources.Res
import com.androidperformancestudio.ui_components.generated.resources.menu_file
import com.androidperformancestudio.ui_components.generated.resources.menu_help
import com.androidperformancestudio.ui_components.generated.resources.menu_open
import com.androidperformancestudio.ui_components.generated.resources.menu_open_file
import com.androidperformancestudio.ui_components.generated.resources.menu_open_folder
import com.androidperformancestudio.ui_components.generated.resources.menu_tools
import com.androidperformancestudio.ui_components.generated.resources.menu_view
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowMenuTest {
    @Test
    fun `shared menu titles are localized`() {
        val titles = listOf(Res.string.menu_file, Res.string.menu_view, Res.string.menu_tools, Res.string.menu_help)
        assertEquals(listOf("File", "View", "Tools", "Help"), titles.map { localizedStringResource(it, UiLanguage.ENGLISH) })
        assertEquals(listOf("文件", "视图", "工具", "帮助"), titles.map { localizedStringResource(it, UiLanguage.SIMPLIFIED_CHINESE) })
        assertEquals("Open…", localizedStringResource(Res.string.menu_open, UiLanguage.ENGLISH))
        assertEquals("打开…", localizedStringResource(Res.string.menu_open, UiLanguage.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `primary shortcuts use cmd on macOS and ctrl elsewhere`() {
        assertEquals(KeyShortcut(Key.O, meta = true), MenuShortcuts.open(isMacOs = true))
        assertEquals(KeyShortcut(Key.O, ctrl = true), MenuShortcuts.open(isMacOs = false))
        assertEquals(KeyShortcut(Key.E, meta = true), MenuShortcuts.export(isMacOs = true))
        assertEquals(KeyShortcut(Key.Comma, ctrl = true), MenuShortcuts.settings(isMacOs = false))
    }

    @Test
    fun `pages that open folders get separate file and folder entries`() {
        assertEquals("Open File…", localizedStringResource(Res.string.menu_open_file, UiLanguage.ENGLISH))
        assertEquals("打开文件夹…", localizedStringResource(Res.string.menu_open_folder, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals(KeyShortcut(Key.O, meta = true, shift = true), MenuShortcuts.openFolder(isMacOs = true))
        assertEquals(KeyShortcut(Key.O, ctrl = true, shift = true), MenuShortcuts.openFolder(isMacOs = false))
    }

    @Test
    fun `export menu is enabled only when a page offers a format`() {
        assertFalse(PageMenu().hasExports())
        assertFalse(PageMenu(exportGroups = listOf(emptyList())).hasExports())
        assertTrue(PageMenu(exportGroups = listOf(listOf(MenuCommand("CSV") {}))).hasExports())
    }

    @Test
    fun `the top-level menus are fixed in the host and never declared by pages`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/ui/WindowMenu.kt"))
        val menuBar = source.substringAfter("MenuBar {").substringBefore("\n    }\n}")
        val topLevel = Regex("""^        Menu\(""", RegexOption.MULTILINE).findAll(menuBar).count()
        assertEquals(4, topLevel)
        // MenuBar and each top-level Menu capture their content once; content must read state.
        assertTrue(source.contains("rememberUpdatedState(chrome)"))
        assertTrue(source.contains("host.menu?.view?.invoke(this)"))
        assertTrue(source.contains("if (!LocalWindowMenuBarActive.current) return"))
    }
}
