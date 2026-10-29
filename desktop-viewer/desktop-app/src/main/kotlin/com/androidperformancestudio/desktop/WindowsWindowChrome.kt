package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.ui.ViewerColors
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Insets
import java.awt.LayoutManager
import java.awt.Point
import java.awt.SystemColor
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.SwingConstants
import javax.swing.border.AbstractBorder
import javax.swing.plaf.basic.BasicMenuBarUI
import javax.swing.plaf.basic.BasicMenuUI
import kotlin.math.ceil

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FrameWindowScope.WindowsWindowChromeEffect(
    colors: ViewerColors,
    darkTheme: Boolean,
) {
    val chrome = remember(window) { mutableStateOf<WindowsWindowChrome?>(null) }
    DisposableEffect(window) {
        val installed = WindowsWindowChrome.install(window)
        chrome.value = installed
        onDispose { installed?.close() }
    }
    SideEffect {
        chrome.value?.updateTheme(
            Color(colors.toolbar.toArgb(), true),
            Color(colors.primaryText.toArgb(), true),
            Color(colors.border.toArgb(), true),
            darkTheme,
        )
    }
}

/** Extends the existing Swing menu row into JBR's title region without reparenting its menus. */
internal class WindowsWindowChrome private constructor(
    private val window: JFrame,
    private val decorations: WindowDecorations,
    private val titleBar: WindowDecorations.CustomTitleBar,
) : AutoCloseable {
    private var background: Color = SystemColor.control
    private var foreground: Color = SystemColor.controlText
    private var divider: Color = SystemColor.controlShadow
    private var menuBar: JMenuBar? = null
    private var closed = false
    private var restoreMenu: (() -> Unit)? = null
    private val restoreMenus = mutableMapOf<JMenu, () -> Unit>()
    private val title = JLabel("", SwingConstants.CENTER)
    private val rootListener =
        object : ContainerAdapter() {
            override fun componentAdded(event: ContainerEvent) {
                if (event.child === window.jMenuBar) updateSafely { attachMenuBar() }
            }

            override fun componentRemoved(event: ContainerEvent) {
                if (event.child === menuBar) updateSafely { detachMenuBar() }
            }
        }
    private val windowListener =
        PropertyChangeListener {
            menuBar?.revalidate()
            menuBar?.repaint()
        }
    private val mouseListener =
        object : MouseAdapter() {
            private fun hit(event: MouseEvent) {
                val bar = menuBar ?: return
                titleBar.forceHitTest(isMenuPoint(bar, event.point))
            }

            override fun mouseClicked(event: MouseEvent) = hit(event)

            override fun mousePressed(event: MouseEvent) = hit(event)

            override fun mouseReleased(event: MouseEvent) = hit(event)

            override fun mouseEntered(event: MouseEvent) = hit(event)

            override fun mouseMoved(event: MouseEvent) = hit(event)

            override fun mouseDragged(event: MouseEvent) = hit(event)
        }
    private val containerListener =
        object : ContainerAdapter() {
            override fun componentAdded(event: ContainerEvent) = styleMenus()

            override fun componentRemoved(event: ContainerEvent) {
                menuBar?.revalidate()
                menuBar?.repaint()
            }
        }

    private fun start() {
        window.layeredPane.addContainerListener(rootListener)
        window.addPropertyChangeListener("title", windowListener)
        window.addPropertyChangeListener("graphicsConfiguration", windowListener)
        attachMenuBar()
    }

    private fun attachMenuBar() {
        val next = window.jMenuBar
        if (next === menuBar) return
        detachMenuBar()
        if (next == null) return
        menuBar = next
        val oldBorder = next.border
        val oldLayout = next.layout
        val oldUi = next.ui
        val oldBackground = next.background
        val oldOpaque = next.isOpaque
        restoreMenu = {
            next.removeMouseListener(mouseListener)
            next.removeMouseMotionListener(mouseListener)
            next.removeContainerListener(containerListener)
            next.setUI(oldUi)
            next.border = oldBorder
            next.layout = oldLayout
            next.background = oldBackground
            next.isOpaque = oldOpaque
            restoreMenus.values.forEach { it() }
            restoreMenus.clear()
            next.revalidate()
            next.repaint()
        }
        next.setUI(BasicMenuBarUI())
        next.border = TitleBarBorder(next)
        next.layout =
            TitleBarMenuLayout { height ->
                if (titleBar.height != height.toFloat()) titleBar.height = height.toFloat()
            }
        next.isOpaque = true
        next.addMouseListener(mouseListener)
        next.addMouseMotionListener(mouseListener)
        next.addContainerListener(containerListener)
        styleMenus()
        titleBar.height = next.preferredSize.height.toFloat()
        decorations.setCustomTitleBar(window, titleBar)
        next.revalidate()
        next.repaint()
    }

    private fun styleMenus() {
        menuBar?.let { bar ->
            bar.background = background
            bar.components.filterIsInstance<JMenu>().forEach {
                if (it !in restoreMenus) {
                    val oldUi = it.ui
                    val oldForeground = it.foreground
                    val oldBackground = it.background
                    val oldOpaque = it.isOpaque
                    restoreMenus[it] = {
                        it.setUI(oldUi)
                        it.foreground = oldForeground
                        it.background = oldBackground
                        it.isOpaque = oldOpaque
                    }
                    it.setUI(BasicMenuUI())
                }
                it.foreground = foreground
                it.background = background
                it.isOpaque = false
            }
            bar.revalidate()
            bar.repaint()
        }
    }

    internal fun updateTheme(
        background: Color,
        foreground: Color,
        divider: Color,
        darkTheme: Boolean,
    ) {
        if (closed) return
        this.background = background
        this.foreground = foreground
        this.divider = divider
        updateSafely {
            titleBar.putProperty("controls.dark", darkTheme)
            titleBar.putProperty("controls.background.normal", background)
            titleBar.putProperty("controls.background.inactive", background)
            styleMenus()
        }
    }

    // Optional runtime failures must restore standard chrome instead of escaping the EDT.
    @Suppress("TooGenericExceptionCaught")
    private fun updateSafely(update: () -> Unit) {
        if (closed) return
        try {
            update()
        } catch (failure: RuntimeException) {
            fallBack(failure)
        } catch (failure: LinkageError) {
            fallBack(failure)
        }
    }

    private fun fallBack(failure: Throwable) {
        runCatching { close() }
        System.err.println("JBR title bar unavailable; using standard window: $failure")
    }

    private fun detachMenuBar() {
        if (menuBar == null) return
        try {
            decorations.setCustomTitleBar(window, null)
        } finally {
            restoreMenu?.invoke()
            restoreMenu = null
            menuBar = null
        }
    }

    internal val isInstalled: Boolean get() = !closed && titleBar.containingWindow === window

    override fun close() {
        if (closed) return
        closed = true
        window.layeredPane.removeContainerListener(rootListener)
        window.removePropertyChangeListener("title", windowListener)
        window.removePropertyChangeListener("graphicsConfiguration", windowListener)
        detachMenuBar()
    }

    private inner class TitleBarBorder(
        private val bar: JMenuBar,
    ) : AbstractBorder() {
        override fun getBorderInsets(component: Component): Insets =
            Insets(
                0,
                ceil(titleBar.leftInset.toDouble()).toInt().coerceAtLeast(0) + ICON_SPACE,
                1,
                ceil(titleBar.rightInset.toDouble()).toInt().coerceAtLeast(CONTROLS_WIDTH),
            )

        override fun paintBorder(
            component: Component,
            graphics: Graphics,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            graphics.color = divider
            graphics.drawLine(x, y + height - 1, x + width - 1, y + height - 1)
            val left = ceil(titleBar.leftInset.toDouble()).toInt().coerceAtLeast(0)
            window.iconImages.firstOrNull()?.let { image ->
                graphics.drawImage(
                    image,
                    x + left + CONTENT_PADDING,
                    y + (height - ICON_SIZE) / 2,
                    ICON_SIZE,
                    ICON_SIZE,
                    component,
                )
            }
            val titleStart = bar.components.maxOfOrNull { it.x + it.width } ?: bar.insets.left
            val titleEnd = width - bar.insets.right
            val titleWidth = (titleEnd - titleStart - CONTENT_PADDING * 2).coerceAtLeast(0)
            if (titleWidth == 0) return
            title.text = window.title
            title.foreground = foreground
            title.font = bar.font.deriveFont(Font.PLAIN)
            title.setSize(titleWidth, height - 1)
            val labelGraphics = graphics.create(x + titleStart + CONTENT_PADDING, y, titleWidth, height - 1)
            try {
                title.paint(labelGraphics)
            } finally {
                labelGraphics.dispose()
            }
        }
    }

    internal companion object {
        internal const val MIN_HEIGHT = 36
        internal const val CONTROLS_WIDTH = 138
        private const val ICON_SPACE = 34
        private const val ICON_SIZE = 18
        private const val CONTENT_PADDING = 8

        @Suppress("TooGenericExceptionCaught")
        internal fun install(
            window: JFrame,
            osName: String = System.getProperty("os.name").orEmpty(),
            service: () -> WindowDecorations? = { JBR.getWindowDecorations() },
        ): WindowsWindowChrome? {
            if (!osName.startsWith("Windows", ignoreCase = true)) return null
            var chrome: WindowsWindowChrome? = null
            try {
                val decorations = service()
                if (decorations != null) {
                    val titleBar =
                        decorations.createCustomTitleBar().apply {
                            height = MIN_HEIGHT.toFloat()
                            putProperty("controls.width", CONTROLS_WIDTH.toFloat())
                        }
                    chrome = WindowsWindowChrome(window, decorations, titleBar)
                    chrome.start()
                }
            } catch (failure: RuntimeException) {
                runCatching { chrome?.close() }
                chrome = null
                System.err.println("JBR title bar unavailable; using standard window: $failure")
            } catch (failure: LinkageError) {
                runCatching { chrome?.close() }
                chrome = null
                System.err.println("JBR title bar unavailable; using standard window: $failure")
            }
            return chrome
        }

        internal fun isMenuPoint(
            bar: JMenuBar,
            point: Point,
        ): Boolean = bar.components.any { it.isVisible && it.bounds.contains(point) }
    }
}

/** Stable window-point height; menu geometry remains independent of Compose's display scale. */
internal class TitleBarMenuLayout(
    private val onHeight: (Int) -> Unit = {},
) : LayoutManager {
    override fun addLayoutComponent(
        name: String?,
        component: Component?,
    ) = Unit

    override fun removeLayoutComponent(component: Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension {
        val children = parent.components.filter { it.isVisible }
        val insets = parent.insets
        val menuHeight = children.maxOfOrNull { it.preferredSize.height } ?: 0
        return Dimension(
            insets.left + children.sumOf { it.preferredSize.width } + insets.right,
            maxOf(WindowsWindowChrome.MIN_HEIGHT, menuHeight + VERTICAL_PADDING),
        )
    }

    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

    override fun layoutContainer(parent: Container) {
        val height = preferredLayoutSize(parent).height
        onHeight(height)
        val insets = parent.insets
        val end = (parent.width - insets.right).coerceAtLeast(insets.left)
        var x = insets.left
        parent.components.filter { it.isVisible }.forEach { menu ->
            val preferred = menu.preferredSize
            val width = preferred.width.coerceAtMost((end - x).coerceAtLeast(0))
            val menuHeight = (parent.height - insets.bottom).coerceAtLeast(0)
            menu.setBounds(x, 0, width, menuHeight)
            x += width
        }
    }

    private companion object {
        private const val VERTICAL_PADDING = 12
    }
}
