package com.androidperformancestudio.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirefoxChartViewportInputComposeTest {
    @Test
    fun `ordinary recomposition updates callbacks while session replacement and disposal clear keys`() =
        runDesktopComposeUiTest {
            var session by mutableStateOf(1)
            var callbackVersion by mutableStateOf(1)
            var mounted by mutableStateOf(true)
            lateinit var keyboard: FirefoxChartKeyboardState
            val versions = mutableListOf<Int>()
            setContent {
                if (mounted) {
                    val version = callbackVersion
                    keyboard =
                        rememberFirefoxChartKeyboard(horizontalEnabled = false, resetKey = session) {
                            versions.add(version)
                        }
                }
            }
            mainClock.autoAdvance = false
            val original = keyboard
            runOnIdle { keyboard.handle(Key.W, KeyEventType.KeyDown) }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(versions.isNotEmpty(), "A held key must deliver frame callbacks")
            runOnIdle { callbackVersion = 2 }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertSame(original, keyboard)
            assertEquals(2, versions.last())
            runOnIdle { session = 2 }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertFalse(original.hasPressedKeys)
            assertFalse(keyboard.hasPressedKeys)
            val stoppedCount = versions.size
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertEquals(stoppedCount, versions.size)
            val replacement = keyboard
            runOnIdle { replacement.handle(Key.W, KeyEventType.KeyDown) }
            repeat(3) { mainClock.advanceTimeByFrame() }
            runOnIdle { mounted = false }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertFalse(replacement.hasPressedKeys)
        }

    @Test
    fun `window blur clears keys and refocus never resumes a stale hold`() =
        runDesktopComposeUiTest {
            val window = TestChartWindowInfo()
            lateinit var keyboard: FirefoxChartKeyboardState
            var motionCount = 0
            setContent {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    keyboard = rememberFirefoxChartKeyboard(horizontalEnabled = true) { motionCount++ }
                }
            }
            mainClock.autoAdvance = false
            runOnIdle { keyboard.handle(Key.W, KeyEventType.KeyDown) }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(motionCount > 0)
            val stoppedCount = motionCount
            runOnIdle { window.isWindowFocused = false }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertFalse(keyboard.hasPressedKeys)
            assertEquals(stoppedCount, motionCount, "Blur must suppress even the next pending frame")
            repeat(3) { mainClock.advanceTimeByFrame() }
            runOnIdle { window.isWindowFocused = true }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertEquals(stoppedCount, motionCount)
        }
}

private class TestChartWindowInfo : WindowInfo {
    override var isWindowFocused: Boolean by mutableStateOf(true)
}
