package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AiConversationWindowActivationTest {
    @Test
    fun `repeated visible requests reactivate without replacing window state runtime or active jobs`() {
        val root = Files.createTempDirectory("aps-ai-activation-").toRealPath()
        val runtimes = mutableListOf<AiPlatformRuntime>()
        val record =
            ConversationRecord(
                id = "activation-history",
                analysisSessionId = "fixture-session",
                contextHash = "a".repeat(64),
            )
        var closes = 0
        var jobStarts = 0
        var jobStops = 0
        try {
            runDesktopComposeUiTest {
                var openRequestId by mutableStateOf(0L)
                var windowKey by mutableStateOf(Any())
                var unrelatedRevision by mutableStateOf(0)
                var composedRevision = -1
                var activations = 0
                var activatedRevision = -1
                var activeJob: Job? = null
                var observedState: WindowState? = null
                setContent {
                    val runtime =
                        remember {
                            AiPlatformRuntime
                                .open(
                                    root.resolve("profile"),
                                    openAiAvailable = { false },
                                    openAiGateway = { error("Window activation must not call a provider") },
                                ).also {
                                    it.history.save(record)
                                    runtimes += it
                                }
                        }
                    DisposableEffect(runtime) {
                        onDispose {
                            runtime.close()
                            closes++
                        }
                    }
                    LaunchedEffect(runtime) {
                        activeJob = coroutineContext.job
                        jobStarts++
                        try {
                            awaitCancellation()
                        } finally {
                            jobStops++
                        }
                    }
                    val windowState = rememberWindowState()
                    val revision = unrelatedRevision
                    SideEffect {
                        composedRevision = revision
                        observedState = windowState
                    }
                    AiConversationWindowActivationEffect(openRequestId, windowKey) {
                        activations++
                        activatedRevision = revision
                    }
                }
                waitForIdle()
                val runtime = runtimes.single()
                val windowState = checkNotNull(observedState)
                val job = checkNotNull(activeJob)
                assertEquals(1, activations)

                repeat(3) { index ->
                    runOnIdle { openRequestId++ }
                    waitForIdle()
                    assertEquals(index + 2, activations)
                    assertSame(runtime, runtimes.single())
                    assertSame(windowState, observedState)
                    assertSame(job, activeJob)
                    assertTrue(job.isActive)
                    assertEquals(0, closes)
                    assertEquals(0, jobStops)
                    assertEquals(listOf(record), runBlocking { runtime.service.history() })
                }

                runOnIdle { unrelatedRevision++ }
                waitForIdle()
                assertEquals(1, composedRevision)
                assertEquals(4, activations, "Equal request and window keys must not repeat activation")
                assertEquals(0, activatedRevision)

                runOnIdle { windowKey = Any() }
                waitForIdle()
                assertEquals(5, activations, "A replacement window must activate even with the same request ID")
                assertEquals(1, activatedRevision)
                assertSame(windowState, observedState)
                assertSame(job, activeJob)
                assertTrue(job.isActive)
                assertEquals(1, jobStarts)
                assertEquals(0, jobStops)
                assertEquals(0, closes)
            }
            assertEquals(1, closes)
            assertEquals(1, jobStops)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native activation restores minimization before front and focus on the EDT`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "An undisplayed AWT frame still requires a desktop")
        EventQueue.invokeAndWait {
            val state = WindowState(isMinimized = true)
            val events = mutableListOf<String>()
            val frame =
                object : Frame() {
                    override fun toFront() {
                        assertTrue(EventQueue.isDispatchThread())
                        assertFalse(state.isMinimized)
                        events += "front"
                    }

                    override fun requestFocus() {
                        assertTrue(EventQueue.isDispatchThread())
                        assertFalse(state.isMinimized)
                        events += "focus"
                    }
                }
            try {
                val size = state.size
                val position = state.position
                val placement = state.placement
                repeat(2) {
                    state.isMinimized = true
                    activateAiConversationWindow(frame, state)
                    assertFalse(state.isMinimized)
                }
                assertEquals(listOf("front", "focus", "front", "focus"), events)
                assertEquals(size, state.size)
                assertEquals(position, state.position)
                assertEquals(placement, state.placement)
                assertFalse(frame.isDisplayable)
            } finally {
                frame.dispose()
            }
        }
    }

    @Test
    fun `desktop open entries increment and forward a request without rekeying runtime startup`() {
        val root =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("desktop-viewer/desktop-app")) }
        val desktop =
            Files.readString(
                root.resolve("desktop-viewer/desktop-app/src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"),
            )
        assertTrue(desktop.contains("var aiWindowOpenRequestId by remember { mutableStateOf(0L) }"))
        val coordinatorOpen = desktop.substringAfter("aiCoordinator.onOpen = {").substringBefore("\n    val aiContextHost")
        val globalOpen = desktop.substringAfter("val openGlobalAi: () -> Unit = {").substringBefore("\n    LaunchedEffect")
        for (entry in listOf(coordinatorOpen, globalOpen)) {
            assertTrue(entry.contains("showAiWindow = true"))
            assertTrue(entry.contains("aiWindowOpenRequestId++"))
            assertFalse(entry.contains("showAiWindow = false"))
            assertFalse(entry.contains(".cancel("))
            assertFalse(entry.contains(".close("))
        }
        val host = desktop.substringAfter("presentation.AiConversationWindowHost(").substringBefore("\n    if (")
        assertTrue(host.contains("visible = showAiWindow"))
        assertTrue(host.contains("openRequestId = aiWindowOpenRequestId"))
        assertTrue(desktop.contains("LaunchedEffect(sourceWorkspaceRuntime) {"))
        val startup = desktop.substringAfter("LaunchedEffect(sourceWorkspaceRuntime) {").substringBefore("var applicationSettings by")
        assertFalse(startup.contains("aiWindowOpenRequestId"))
    }
}
