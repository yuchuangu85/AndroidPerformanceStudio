package com.androidperformancestudio.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.app.AiPlatformSettingsStore
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.presentation.AiConversationWorkspace
import com.androidperformancestudio.desktop.shell.CommercialAppShell
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences

@OptIn(ExperimentalTestApi::class)
class DesktopAiRuntimeLifecycleTest {
    @Test
    fun `async startup owns history and the profile until cancellation finishes`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-lifecycle-").toRealPath()
            val ready = CompletableDeferred<AiPlatformRuntime>()
            val owner = launch { runDesktopAiRuntime({ open(root) }) { ready.complete(it) } }
            try {
                val runtime = withTimeout(10_000) { ready.await() }
                assertTrue(runtime.service.history().isEmpty())
                assertThrows<IllegalStateException> { open(root) }
                withTimeout(10_000) { owner.cancelAndJoin() }
                open(root).use { assertTrue(it.history.list().isEmpty()) }
            } finally {
                owner.cancelAndJoin()
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `publication failure releases the acquired runtime before propagating`() =
        runBlocking {
            val root = Files.createTempDirectory("aps-ai-publication-failure-").toRealPath()
            val failure = IllegalStateException("fixture")
            try {
                val actual =
                    assertThrows<IllegalStateException> {
                        runDesktopAiRuntime({ open(root) }) { throw failure }
                    }
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === failure })
                open(root).use { assertTrue(it.history.list().isEmpty()) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `disposing in the same UI turn as runtime publication releases the profile`() {
        val root = Files.createTempDirectory("aps-ai-immediate-disposal-").toRealPath()
        val stopped = CompletableDeferred<Unit>()
        try {
            runDesktopComposeUiTest {
                var mounted by mutableStateOf(true)
                var current by mutableStateOf<AiPlatformRuntime?>(null)
                setContent {
                    if (mounted) {
                        LaunchedEffect(Unit) {
                            try {
                                runDesktopAiRuntime({ open(root) }) {
                                    current = it
                                    mounted = false
                                }
                            } finally {
                                stopped.complete(Unit)
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { stopped.isCompleted }
                assertTrue(current != null)
                open(root).use { assertTrue(it.history.list().isEmpty()) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replacing the startup owner closes only the old runtime`() {
        val root = Files.createTempDirectory("aps-ai-replacement-").toRealPath()
        val firstRoot = Files.createDirectory(root.resolve("first"))
        val secondRoot = Files.createDirectory(root.resolve("second"))
        val stopped = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        try {
            runDesktopComposeUiTest {
                var selected by mutableStateOf(0)
                var mounted by mutableStateOf(true)
                var current by mutableStateOf<AiPlatformRuntime?>(null)
                setContent {
                    if (mounted) {
                        val index = selected
                        LaunchedEffect(index) {
                            try {
                                runDesktopAiRuntime({ open(if (index == 0) firstRoot else secondRoot) }) { current = it }
                            } finally {
                                stopped[index].complete(Unit)
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { current != null }
                val first = checkNotNull(current)
                assertThrows<IllegalStateException> { open(firstRoot) }

                runOnIdle { selected = 1 }
                waitUntil(timeoutMillis = 10_000) { current !== first && stopped[0].isCompleted }
                open(firstRoot).use { assertTrue(it.history.list().isEmpty()) }
                assertTrue(runBlocking { checkNotNull(current).service.history() }.isEmpty())
                assertThrows<IllegalStateException> { open(secondRoot) }

                runOnIdle { mounted = false }
                waitUntil(timeoutMillis = 10_000) { stopped[1].isCompleted }
                open(secondRoot).use { assertTrue(it.history.list().isEmpty()) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `AI rail opens history repeatedly without closing the app owned runtime`() {
        val root = Files.createTempDirectory("aps-ai-rail-history-").toRealPath()
        val preferences = Preferences.userRoot().node("aps-ai-lifecycle-test/${UUID.randomUUID()}")
        val settingsStore = AiPlatformSettingsStore(preferences)
        val stopped = CompletableDeferred<Unit>()
        val record =
            ConversationRecord(
                id = "fixture-history",
                analysisSessionId = "fixture-session",
                contextHash = "a".repeat(64),
            )
        try {
            runDesktopComposeUiTest(width = 1280, height = 720) {
                var current by mutableStateOf<AiPlatformRuntime?>(null)
                var mounted by mutableStateOf(true)
                var visible by mutableStateOf(false)
                val navigator = AppNavigator()
                setContent {
                    if (mounted) {
                        LaunchedEffect(Unit) {
                            try {
                                runDesktopAiRuntime({ open(root) }) { runtime ->
                                    runtime.history.save(record)
                                    current = runtime
                                }
                            } finally {
                                stopped.complete(Unit)
                            }
                        }
                        ViewerTheme(darkTheme = false) {
                            CommercialAppShell(
                                navigator = navigator,
                                language = UiLanguage.ENGLISH,
                                navigationRailCollapsed = false,
                                onToggleNavigationRailCollapsed = {},
                                moduleOrder = emptyList(),
                                screencastWindowOpen = false,
                                onOpenScreencastWindow = {},
                                onOpenAi = { visible = true },
                            ) {
                                if (visible) {
                                    current?.let { runtime ->
                                        AiConversationWorkspace(
                                            runtime,
                                            context = null,
                                            language = UiLanguage.ENGLISH,
                                            defaultModel = "fixture-model",
                                            settingsStoreOverride = settingsStore,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) { current != null }
                val runtime = checkNotNull(current)
                repeat(3) {
                    onNodeWithContentDescription("AI Analysis").performClick()
                    onNodeWithTag("ai-workspace").assertIsDisplayed()
                    waitUntil(timeoutMillis = 10_000) {
                        onAllNodesWithTag("ai-session-${record.id}").fetchSemanticsNodes().size == 1
                    }
                    onNodeWithTag("ai-session-${record.id}").assertIsDisplayed()

                    runOnIdle { visible = false }
                    waitForIdle()
                    onNodeWithTag("ai-workspace").assertDoesNotExist()
                    assertEquals(listOf(record), runBlocking { runtime.service.history() })
                    assertThrows<IllegalStateException> { open(root) }
                }
                runOnIdle { mounted = false }
                waitUntil(timeoutMillis = 10_000) { stopped.isCompleted }
            }
            open(root).use { assertEquals(listOf(record), it.history.list()) }
        } finally {
            preferences.removeNode()
            root.toFile().deleteRecursively()
        }
    }

    private fun open(root: Path) =
        AiPlatformRuntime.open(
            root.resolve("profile"),
            openAiAvailable = { false },
            openAiGateway = { error("Opening AI must not call a provider") },
        )
}
