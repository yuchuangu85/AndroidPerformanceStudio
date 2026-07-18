package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppNavigatorTest {
    @Test
    fun `application starts on the home page`() {
        assertEquals(AppDestination.HOME, AppNavigator().destination)
    }

    @Test
    fun `opening a feature replaces the home page`() {
        val navigator = AppNavigator()

        navigator.open(AppDestination.SIMPLEPERF)

        assertEquals(AppDestination.SIMPLEPERF, navigator.destination)
    }

    @Test
    fun `feature destinations request a maximized window but home does not`() {
        assertFalse(AppDestination.HOME.shouldMaximizeWindow())
        assertTrue(AppDestination.LAYOUT_INSPECTOR.shouldMaximizeWindow())
        assertTrue(AppDestination.SIMPLEPERF.shouldMaximizeWindow())
    }
}
