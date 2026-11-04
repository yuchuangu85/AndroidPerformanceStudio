package com.androidperformancestudio.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.LocalPageMenuHost
import com.androidperformancestudio.ui.MenuShortcuts
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.WindowMenuBar
import com.androidperformancestudio.ui.WindowMenuChrome
import com.androidperformancestudio.ui.rememberPageMenuHost
import com.jetbrains.JBR
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

@OptIn(ExperimentalTestApi::class)
class WindowsWindowChromeIntegrationTest {
    @BeforeEach
    fun requireDesktopEnvironment() {
        if (System.getenv("APS_REQUIRE_JBR_CHROME").toBoolean()) {
            assertFalse(GraphicsEnvironment.isHeadless(), "Required JBR smoke test needs a desktop")
        }
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native window integration tests need a desktop")
    }

    @Test
    fun `hosted menus retain live actions checkboxes language and accelerators`() =
        runDesktopComposeUiTest {
            val frame =
                runOnUiThread {
                    ComposeWindow().apply {
                        title = "Android Performance Studio"
                        iconImage = ImageIO.read(javaClass.getResource("/icons/app-icon.png"))
                    }
                }
            val scope =
                object : FrameWindowScope {
                    override val window = frame
                }
            val language = mutableStateOf(UiLanguage.ENGLISH)
            val page = mutableStateOf("first")
            val checked = mutableStateOf(false)
            val opened = mutableListOf<String>()
            var chrome: WindowsWindowChrome? = null
            try {
                setContent {
                    val host = rememberPageMenuHost()
                    scope.WindowMenuBar(host, WindowMenuChrome(language.value))
                    val currentPage = page.value
                    val currentChecked = checked.value
                    CompositionLocalProvider(LocalPageMenuHost provides host) {
                        ActivePageMenu(
                            PageMenu(
                                onOpen = { opened += currentPage },
                                view = {
                                    CheckboxItem("Visible", checked = currentChecked, onCheckedChange = { checked.value = it })
                                },
                            ),
                        )
                    }
                }
                waitForIdle()
                val bar = runOnUiThread { frame.jMenuBar }
                val file = runOnUiThread { bar.getMenu(0) }
                runOnUiThread {
                    chrome = WindowsWindowChrome.install(frame, "Windows 11") { FakeWindowDecorations() }
                    assertNotNull(chrome)
                    chrome!!.updateTheme(Color(0x242424), Color.WHITE, Color.GRAY, true)
                    assertSame(bar, frame.jMenuBar)
                    val expectedModifiers = if (MenuShortcuts.isMacOs) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
                    assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_O, expectedModifiers), file.getItem(0).accelerator)
                    file.getItem(0).doClick(0)
                    (bar.getMenu(1).getItem(0) as JCheckBoxMenuItem).doClick(0)
                }
                waitForIdle()
                runOnUiThread {
                    assertTrue((bar.getMenu(1).getItem(0) as JCheckBoxMenuItem).isSelected)
                    assertEquals(listOf("first"), opened)
                }
                runOnIdle {
                    page.value = "second"
                    language.value = UiLanguage.SIMPLIFIED_CHINESE
                }
                waitForIdle()
                runOnUiThread {
                    assertSame(bar, frame.jMenuBar)
                    assertSame(file, bar.getMenu(0))
                    assertEquals("文件", file.text)
                    file.getItem(0).doClick(0)
                    assertEquals(listOf("first", "second"), opened)
                    assertEquals(4, bar.menuCount)
                    bar.setSize(1000, bar.preferredSize.height)
                    bar.doLayout()
                    val image = BufferedImage(bar.width, bar.height, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    try {
                        bar.paint(graphics)
                    } finally {
                        graphics.dispose()
                    }
                    val output = Path.of("build/test-artifacts/windows-titlebar-menus-dark-zh.png")
                    Files.createDirectories(output.parent)
                    ImageIO.write(image, "png", output.toFile())
                    assertEquals(Color(0x242424).rgb, image.getRGB(700, 2))
                    assertEquals(Color(0x242424).rgb, image.getRGB(file.x + 1, file.y + 1))
                    chrome!!.updateTheme(Color(0xf3f3f3), Color.BLACK, Color.LIGHT_GRAY, false)
                    frame.title = "Android Performance Studio - " + "Long workspace title ".repeat(8)
                    bar.setSize(600, bar.preferredSize.height)
                    bar.doLayout()
                    val lightImage = BufferedImage(bar.width, bar.height, BufferedImage.TYPE_INT_ARGB)
                    val lightGraphics = lightImage.createGraphics()
                    try {
                        bar.paint(lightGraphics)
                    } finally {
                        lightGraphics.dispose()
                    }
                    ImageIO.write(lightImage, "png", output.resolveSibling("windows-titlebar-menus-light-long-title.png").toFile())
                    assertEquals(Color(0xf3f3f3).rgb, lightImage.getRGB(file.x + 1, file.y + 1))
                    assertTrue(bar.components.all { it.x + it.width <= bar.width - bar.insets.right })
                }
            } finally {
                runOnUiThread { chrome?.close() }
                setContent {}
                waitForIdle()
                runOnUiThread { frame.dispose() }
            }
        }

    @Test
    fun `Windows JBR provides a live custom title bar without undecorating the window`() {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val required = System.getenv("APS_REQUIRE_JBR_CHROME").toBoolean()
        if (required) {
            assertTrue(windows, "Required JBR smoke test must run on Windows")
            assertFalse(GraphicsEnvironment.isHeadless(), "Required JBR smoke test needs a desktop")
        }
        assumeTrue(windows)
        assumeFalse(GraphicsEnvironment.isHeadless())
        val service = JBR.getWindowDecorations()
        if (required) {
            assertNotNull(service, "The packaged Windows runtime must support JBR WindowDecorations")
        } else {
            assumeTrue(service != null, "Native smoke test requires JBR")
        }
        SwingUtilities.invokeAndWait {
            val window = ComposeWindow()
            var chrome: WindowsWindowChrome? = null
            try {
                var invoked = false
                val bar =
                    JMenuBar().apply {
                        add(JMenu("File").apply { add(JMenuItem("Open").apply { addActionListener { invoked = true } }) })
                    }
                window.jMenuBar = bar
                window.setSize(1000, 700)
                chrome = WindowsWindowChrome.install(window)
                assertNotNull(chrome)
                window.isVisible = true
                window.validate()
                assertTrue(chrome!!.isInstalled)
                assertFalse(window.isUndecorated)
                assertSame(bar, window.jMenuBar)
                assertTrue(bar.height >= WindowsWindowChrome.MIN_HEIGHT)
                assertTrue(bar.insets.right >= WindowsWindowChrome.CONTROLS_WIDTH)
                bar.getMenu(0).getItem(0).doClick(0)
                assertTrue(invoked)
            } finally {
                chrome?.close()
                window.dispose()
            }
        }
    }
}
