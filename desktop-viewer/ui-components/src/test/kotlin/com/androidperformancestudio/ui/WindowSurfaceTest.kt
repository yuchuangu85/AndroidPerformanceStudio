package com.androidperformancestudio.ui

import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindowSurfaceTest {
    @Test
    fun `theme changes update native layers immediate children and inherited canvas background`() =
        withFrame { frame ->
            frame.background = Color.RED
            frame.rootPane.background = Color.GREEN
            frame.layeredPane.background = Color.BLUE
            frame.contentPane.background = Color.MAGENTA
            val canvasHost = JPanel(BorderLayout()).apply { background = Color.YELLOW }
            val inheritedCanvas = Canvas().apply { background = null }
            canvasHost.add(inheritedCanvas, BorderLayout.CENTER)
            val directCanvas = Canvas().apply { background = Color.CYAN }
            frame.contentPane.add(canvasHost, BorderLayout.CENTER)
            frame.contentPane.add(directCanvas, BorderLayout.SOUTH)

            for (background in listOf(Color(30, 32, 34), Color(245, 246, 247))) {
                applyWindowSurfaceBackground(frame, background)

                assertEquals(background, frame.background)
                assertEquals(background, frame.rootPane.background)
                assertEquals(background, frame.layeredPane.background)
                assertEquals(background, frame.contentPane.background)
                assertEquals(background, canvasHost.background)
                assertEquals(background, directCanvas.background)
                assertEquals(background, inheritedCanvas.background)
                assertFalse(inheritedCanvas.isBackgroundSet, "The nested canvas must still inherit its background")
            }
        }

    @Test
    fun `resize validates layout before rendering synchronously on the EDT`() =
        withFrame { frame ->
            val canvas = Canvas()
            frame.contentPane.add(canvas, BorderLayout.CENTER)
            frame.isVisible = true
            val sidebar = JPanel().apply { preferredSize = Dimension(40, 1) }
            frame.contentPane.add(sidebar, BorderLayout.EAST)
            frame.invalidate()
            assertFalse(frame.contentPane.isValid)
            var renders = 0
            val listener =
                WindowResizeRedrawListener(frame) {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    assertTrue(frame.isValid)
                    assertTrue(frame.contentPane.isValid)
                    assertEquals(frame.contentPane.width - sidebar.preferredSize.width, canvas.width)
                    assertEquals(frame.contentPane.height, canvas.height)
                    renders++
                }

            listener.componentResized(resizeEvent(frame))

            assertEquals(1, renders, "Rendering must finish before componentResized returns")
        }

    @Test
    fun `hidden displayable window skips resize rendering until shown again`() =
        withFrame { frame ->
            frame.isVisible = true
            frame.isVisible = false
            assertTrue(frame.isDisplayable)
            assertFalse(frame.isShowing)
            var renders = 0
            val listener = WindowResizeRedrawListener(frame) { renders++ }

            listener.componentResized(resizeEvent(frame))
            assertEquals(0, renders)

            frame.isVisible = true
            listener.componentResized(resizeEvent(frame))
            assertEquals(1, renders)
        }

    @Test
    fun `disposed window skips resize rendering`() =
        withFrame { frame ->
            frame.isVisible = true
            var renders = 0
            val listener = WindowResizeRedrawListener(frame) { renders++ }
            listener.componentResized(resizeEvent(frame))
            assertEquals(1, renders)

            frame.dispose()
            assertFalse(frame.isDisplayable)
            listener.componentResized(resizeEvent(frame))

            assertEquals(1, renders)
        }

    @Test
    fun `zero width or height skips rendering without blocking a later valid resize`() =
        withFrame { frame ->
            frame.isVisible = true
            var renders = 0
            val listener = WindowResizeRedrawListener(frame) { renders++ }

            for (size in listOf(Dimension(0, 180), Dimension(240, 0))) {
                frame.size = size
                assertEquals(size, frame.size)
                assertTrue(frame.isShowing)
                assertTrue(frame.isDisplayable)
                listener.componentResized(resizeEvent(frame))
                assertEquals(0, renders)
            }

            frame.setSize(240, 180)
            listener.componentResized(resizeEvent(frame))
            assertEquals(1, renders)
        }

    @Test
    fun `reentrant resize is ignored while subsequent resize still renders`() =
        withFrame { frame ->
            frame.isVisible = true
            val event = resizeEvent(frame)
            var renders = 0
            lateinit var listener: WindowResizeRedrawListener
            listener =
                WindowResizeRedrawListener(frame) {
                    renders++
                    if (renders == 1) listener.componentResized(event)
                }

            listener.componentResized(event)
            assertEquals(1, renders)

            listener.componentResized(event)
            assertEquals(2, renders)
        }

    @Test
    fun `render failure restores the guard for a subsequent resize`() =
        withFrame { frame ->
            frame.isVisible = true
            val failure = IllegalStateException("Injected render failure")
            var renders = 0
            val listener =
                WindowResizeRedrawListener(frame) {
                    renders++
                    if (renders == 1) throw failure
                }

            assertSame(
                failure,
                assertFailsWith<IllegalStateException> { listener.componentResized(resizeEvent(frame)) },
            )
            assertEquals(1, renders)

            listener.componentResized(resizeEvent(frame))
            assertEquals(2, renders)
        }

    private fun resizeEvent(frame: JFrame) = ComponentEvent(frame, ComponentEvent.COMPONENT_RESIZED)

    private fun withFrame(block: (JFrame) -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Window surface tests require a desktop")
        val action = {
            val frame = JFrame()
            try {
                frame.isUndecorated = true
                frame.setSize(240, 180)
                block(frame)
            } finally {
                frame.dispose()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
    }
}
