package com.androidperformancestudio.desktop

import com.jetbrains.WindowDecorations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.SwingUtilities

class WindowsWindowChromeTest {
    @Test
    fun `layout keeps menus inside caption insets and stabilizes height`() =
        onEdt {
            val bar = JMenuBar()
            bar.border = javax.swing.BorderFactory.createEmptyBorder(0, 34, 1, 138)
            bar.layout = TitleBarMenuLayout()
            val menus = listOf("File", "View", "Tools", "Help").map { JMenu(it).also(bar::add) }
            bar.setSize(800, bar.preferredSize.height)
            bar.doLayout()
            assertTrue(bar.height >= 36)
            assertEquals(34, menus.first().x)
            menus.zipWithNext().forEach { (left, right) -> assertEquals(left.x + left.width, right.x) }
            assertTrue(menus.last().x + menus.last().width <= 800 - 138)
            assertTrue(WindowsWindowChrome.isMenuPoint(bar, Point(menus.first().x + 1, 18)))
            assertFalse(WindowsWindowChrome.isMenuPoint(bar, Point(500, 18)))
            assertFalse(WindowsWindowChrome.isMenuPoint(bar, Point(799, 18)))
            bar.setSize(160, 36)
            bar.doLayout()
            assertTrue(menus.all { it.width == 0 })
            assertTrue(menus.all { it.height <= bar.height })
            assertFalse(WindowsWindowChrome.isMenuPoint(bar, Point(80, 18)))
        }

    @Test
    fun `large fonts grow the title row without clipping menu text`() =
        onEdt {
            val bar = JMenuBar()
            bar.layout = TitleBarMenuLayout()
            bar.add(JMenu("File").apply { preferredSize = Dimension(80, 40) })
            assertEquals(52, bar.preferredSize.height)
            bar.setSize(800, 52)
            bar.doLayout()
            assertTrue(bar.getMenu(0).height >= 40)
        }

    @Test
    fun `unsupported systems and missing services preserve standard chrome`() =
        withFrame { frame ->
            val bar = frame.jMenuBar
            val layout = bar.layout
            var requested = false
            assertNull(
                WindowsWindowChrome.install(frame, "Mac OS X") {
                    requested = true
                    FakeWindowDecorations()
                },
            )
            assertNull(
                WindowsWindowChrome.install(frame, "Linux") {
                    requested = true
                    FakeWindowDecorations()
                },
            )
            assertFalse(requested)
            assertNull(WindowsWindowChrome.install(frame, "Windows 11") { null })
            assertNull(WindowsWindowChrome.install(frame, "Windows 11") { throw NoClassDefFoundError("optional runtime") })
            assertSame(bar, frame.jMenuBar)
            assertSame(layout, bar.layout)
            assertFalse(frame.isUndecorated)
        }

    @Test
    fun `integration preserves menu identity and restores listeners colors and layout`() =
        withFrame { frame ->
            val service = FakeWindowDecorations()
            val bar = frame.jMenuBar
            val menu = bar.getMenu(0)
            val originalLayout = bar.layout
            val originalBorder = bar.border
            val originalForeground = menu.foreground
            val listeners = bar.mouseListeners.toList()
            val rootListeners = frame.layeredPane.containerListeners.toList()
            val chrome = WindowsWindowChrome.install(frame, "Windows 11") { service }!!
            assertTrue(chrome.isInstalled)
            assertSame(bar, frame.jMenuBar)
            assertSame(menu, bar.getMenu(0))
            assertEquals(138f, service.titleBar.properties["controls.width"])
            assertFalse(frame.isUndecorated)
            chrome.updateTheme(Color.BLACK, Color.WHITE, Color.GRAY, true)
            assertEquals(Color.BLACK, bar.background)
            assertEquals(Color.WHITE, menu.foreground)
            assertEquals(true, service.titleBar.properties["controls.dark"])
            bar.setSize(800, bar.preferredSize.height)
            bar.doLayout()
            val image = BufferedImage(800, bar.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                bar.paint(graphics)
            } finally {
                graphics.dispose()
            }
            assertEquals(Color.BLACK.rgb, image.getRGB(500, 2))
            assertEquals(Color.GRAY.rgb, image.getRGB(500, bar.height - 1))
            assertTrue((bar.insets.right) >= 138)

            val enter = MouseEvent(bar, MouseEvent.MOUSE_MOVED, 0, 0, 500, 18, 0, false)
            bar.dispatchEvent(enter)
            assertEquals(false, service.titleBar.hitTests.last())
            bar.dispatchEvent(MouseEvent(bar, MouseEvent.MOUSE_PRESSED, 0, 0, menu.x + 1, 18, 1, false))
            assertEquals(true, service.titleBar.hitTests.last())
            chrome.close()
            assertNull(service.titleBar.window)
            assertSame(originalLayout, bar.layout)
            assertSame(originalBorder, bar.border)
            assertEquals(originalForeground, menu.foreground)
            assertEquals(listeners, bar.mouseListeners.toList())
            assertEquals(rootListeners, frame.layeredPane.containerListeners.toList())
            chrome.close()
        }

    @Test
    fun `menu replacement and late menus are attached and cleaned up`() =
        withFrame { frame ->
            val service = FakeWindowDecorations()
            val first = frame.jMenuBar
            val firstLayout = first.layout
            val chrome = WindowsWindowChrome.install(frame, "Windows 11") { service }!!
            chrome.updateTheme(Color.BLACK, Color.WHITE, Color.GRAY, true)
            val second = JMenuBar().apply { layout = java.awt.FlowLayout() }
            val secondLayout = second.layout
            frame.jMenuBar = second
            assertSame(firstLayout, first.layout)
            assertTrue(chrome.isInstalled)
            val menu = JMenu("Late menu")
            val originalForeground = menu.foreground
            second.add(menu)
            assertEquals(Color.WHITE, menu.foreground)
            frame.jMenuBar = null
            assertNull(service.titleBar.window)
            assertSame(secondLayout, second.layout)
            assertEquals(originalForeground, menu.foreground)
            chrome.close()
            assertNotNull(first)
        }

    @Test
    fun `failed native installation rolls back menu mutations`() =
        withFrame { frame ->
            val bar = frame.jMenuBar
            val layout = bar.layout
            val border = bar.border
            val listeners = bar.mouseListeners.toList()
            val service =
                object : FakeWindowDecorations() {
                    override fun setCustomTitleBar(
                        frame: Frame,
                        customTitleBar: WindowDecorations.CustomTitleBar?,
                    ) {
                        if (customTitleBar != null) throw UnsupportedOperationException("not supported")
                        super.setCustomTitleBar(frame, customTitleBar)
                    }
                }
            assertNull(WindowsWindowChrome.install(frame, "Windows 11") { service })
            assertSame(layout, bar.layout)
            assertSame(border, bar.border)
            assertEquals(listeners, bar.mouseListeners.toList())
        }

    @Test
    fun `failed deferred attachment restores standard menu and disables the adapter`() =
        withFrame { frame ->
            frame.jMenuBar = null
            val service =
                object : FakeWindowDecorations() {
                    override fun setCustomTitleBar(
                        frame: Frame,
                        customTitleBar: WindowDecorations.CustomTitleBar?,
                    ) {
                        if (customTitleBar != null) throw UnsupportedOperationException("late native failure")
                        super.setCustomTitleBar(frame, customTitleBar)
                    }
                }
            val originalListeners = frame.layeredPane.containerListeners.toList()
            val chrome = WindowsWindowChrome.install(frame, "Windows 11") { service }!!
            val bar =
                JMenuBar().apply {
                    layout = java.awt.FlowLayout()
                    add(JMenu("File"))
                }
            val layout = bar.layout
            frame.jMenuBar = bar
            assertFalse(chrome.isInstalled)
            assertSame(layout, bar.layout)
            assertEquals(originalListeners, frame.layeredPane.containerListeners.toList())
            val color = bar.background
            chrome.updateTheme(Color.BLACK, Color.WHITE, Color.GRAY, true)
            assertEquals(color, bar.background)
            chrome.close()
        }

    private fun withFrame(block: (JFrame) -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        onEdt {
            val frame = JFrame("Android Performance Studio").apply { jMenuBar = JMenuBar().apply { add(JMenu("File")) } }
            try {
                block(frame)
            } finally {
                frame.dispose()
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
}

internal open class FakeWindowDecorations : WindowDecorations {
    val titleBar = FakeCustomTitleBar()

    override fun createCustomTitleBar(): WindowDecorations.CustomTitleBar = titleBar

    override fun setCustomTitleBar(
        frame: Frame,
        customTitleBar: WindowDecorations.CustomTitleBar?,
    ) {
        titleBar.window = frame.takeIf { customTitleBar != null }
    }

    override fun setCustomTitleBar(
        dialog: Dialog,
        customTitleBar: WindowDecorations.CustomTitleBar?,
    ) {
        titleBar.window = dialog.takeIf { customTitleBar != null }
    }
}

internal class FakeCustomTitleBar : WindowDecorations.CustomTitleBar {
    private var titleHeight = 0f
    private val values = mutableMapOf<String, Any>()
    var window: Window? = null
    val hitTests = mutableListOf<Boolean>()

    override fun getHeight(): Float = titleHeight

    override fun setHeight(height: Float) {
        titleHeight = height
    }

    override fun getProperties(): MutableMap<String, Any> = values

    override fun putProperties(properties: Map<String, *>) {
        properties.forEach { (key, value) -> putProperty(key, value) }
    }

    override fun putProperty(
        key: String,
        value: Any?,
    ) {
        if (value != null) values[key] = value else values.remove(key)
    }

    override fun getLeftInset(): Float = 0f

    override fun getRightInset(): Float = 138f

    override fun forceHitTest(client: Boolean) {
        hitTests += client
    }

    override fun getContainingWindow(): Window? = window
}
