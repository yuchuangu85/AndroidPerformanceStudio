package com.androidperformancestudio.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.window.FrameWindowScope
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WindowMenuBarInteractionTest {
    @Test
    fun `file shortcuts follow navigation without replacing the native menu bar`() =
        runDesktopComposeUiTest {
            withMenuWindow { scope ->
                val host = PageMenuHost()
                val isHome = mutableStateOf(true)
                val opened = mutableListOf<String>()
                val shortcuts =
                    listOf(
                        MenuCommand("Open Trace") { opened += "trace" },
                        MenuCommand("Open HPROF") { opened += "hprof" },
                    )
                val shortcutLabels = shortcuts.map { it.label }
                setContent {
                    scope.WindowMenuBar(
                        host,
                        WindowMenuChrome(
                            UiLanguage.ENGLISH,
                            globalOpenItems = if (isHome.value) shortcuts else emptyList(),
                        ),
                    )
                    CompositionLocalProvider(LocalPageMenuHost provides host) {
                        ActivePageMenu(if (isHome.value) PageMenu() else PageMenu(onOpen = { opened += "page" }))
                    }
                }
                waitForIdle()
                val bar = runOnUiThread { scope.window.jMenuBar }
                val file = runOnUiThread { bar.getMenu(0) }
                runOnUiThread {
                    assertFalse(file.getItem(0).isEnabled)
                    val commands = file.menuComponents.filterIsInstance<JMenuItem>()
                    commands.single { it.text == "Open Trace" }.doClick(0)
                    commands.single { it.text == "Open HPROF" }.doClick(0)
                    assertEquals(listOf("trace", "hprof"), opened)
                }

                runOnIdle { isHome.value = false }
                waitForIdle()
                runOnUiThread {
                    assertSame(bar, scope.window.jMenuBar)
                    assertSame(file, bar.getMenu(0))
                    assertTrue(
                        file.menuComponents.filterIsInstance<JMenuItem>().none { it.text in shortcutLabels },
                    )
                    assertTrue(file.getItem(0).isEnabled)
                    file.getItem(0).doClick(0)
                    assertEquals(listOf("trace", "hprof", "page"), opened)
                }

                runOnIdle { isHome.value = true }
                waitForIdle()
                runOnUiThread {
                    assertFalse(file.getItem(0).isEnabled)
                    assertEquals(
                        2,
                        file.menuComponents.filterIsInstance<JMenuItem>().count { it.text in shortcutLabels },
                    )
                }
            }
        }

    @Test
    fun `view menu follows the active page and keeps current checkbox actions`() =
        runDesktopComposeUiTest {
            withMenuWindow { scope ->
                val host = PageMenuHost()
                val active = mutableStateOf(false)
                val checked = mutableStateOf(false)
                setContent {
                    scope.WindowMenuBar(host, WindowMenuChrome(UiLanguage.ENGLISH))
                    CompositionLocalProvider(
                        LocalPageMenuHost provides host,
                        LocalWindowMenuBarActive provides active.value,
                    ) {
                        val isChecked = checked.value
                        ActivePageMenu(
                            PageMenu(
                                view = {
                                    CheckboxItem(
                                        "Hide hierarchy",
                                        checked = isChecked,
                                        onCheckedChange = { checked.value = it },
                                    )
                                },
                                tools = { Item("Inspect", onClick = {}) },
                            ),
                        )
                    }
                }
                waitForIdle()
                val bar = runOnUiThread { scope.window.jMenuBar }
                val view = runOnUiThread { bar.getMenu(1) }
                runOnUiThread { assertFalse(view.isEnabled) }

                runOnIdle { active.value = true }
                waitForIdle()
                runOnUiThread {
                    assertSame(bar, scope.window.jMenuBar)
                    assertTrue(view.isEnabled, "Publishing the inspector page must enable View")
                    assertTrue(bar.getMenu(2).isEnabled)
                    val toggle = view.getItem(0) as JCheckBoxMenuItem
                    assertFalse(toggle.isSelected)
                    toggle.doClick(0)
                }
                waitForIdle()
                runOnUiThread { assertTrue((view.getItem(0) as JCheckBoxMenuItem).isSelected) }

                runOnIdle { active.value = false }
                waitForIdle()
                runOnUiThread {
                    assertFalse(view.isEnabled)
                    assertEquals(0, view.itemCount)
                }

                runOnIdle { active.value = true }
                waitForIdle()
                runOnUiThread {
                    assertSame(view, bar.getMenu(1))
                    assertTrue(view.isEnabled)
                    assertTrue((view.getItem(0) as JCheckBoxMenuItem).isSelected)
                }
            }
        }

    private suspend fun DesktopComposeUiTest.withMenuWindow(block: suspend (FrameWindowScope) -> Unit) {
        val scope =
            object : FrameWindowScope {
                override val window = runOnUiThread { ComposeWindow() }
            }
        try {
            block(scope)
        } finally {
            setContent {}
            waitForIdle()
            runOnUiThread { scope.window.dispose() }
        }
    }
}
