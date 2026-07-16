package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.ImplementationFilter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FlameGraphSearchMountedTest {
    @Test
    fun `older publication cannot erase debouncing draft and session switch cancels it`() =
        runDesktopComposeUiTest(width = 700, height = 220) {
            val dispatched = mutableListOf<String>()
            var sessionIdentity by mutableStateOf(Path.of("profile-a"))
            var authoritativeQuery by mutableStateOf("")

            setContent {
                MaterialTheme {
                    FlameGraphToolbar(
                        sessionIdentity = sessionIdentity,
                        authoritativeSearch = authoritativeQuery,
                        implementation = ImplementationFilter.ALL,
                        direction = CallStackDirection.FORWARD,
                        hasTransforms = false,
                        onSearch = dispatched::add,
                        onImplementation = {},
                        onDirection = {},
                        onUndo = {},
                        onClear = {},
                    )
                }
            }

            val search = onNodeWithContentDescription("Flame graph search")
            search.performTextInput("a")
            waitUntil(timeoutMillis = 1_000) { dispatched == listOf("a") }
            assertEquals(listOf("a"), dispatched)

            mainClock.autoAdvance = false
            search.performTextInput("b")
            runOnUiThread { authoritativeQuery = "a" }
            waitForIdle()
            assertEquals(listOf("a"), dispatched)
            mainClock.autoAdvance = true
            waitUntil(timeoutMillis = 1_000) { dispatched == listOf("a", "ab") }
            assertEquals(listOf("a", "ab"), dispatched)

            mainClock.autoAdvance = false
            search.performTextInput("c")
            runOnUiThread {
                sessionIdentity = Path.of("profile-b")
                authoritativeQuery = "external"
            }
            mainClock.autoAdvance = true
            waitForIdle()
            search.assertTextContains("external")
            assertEquals(listOf("a", "ab"), dispatched)
        }
}
