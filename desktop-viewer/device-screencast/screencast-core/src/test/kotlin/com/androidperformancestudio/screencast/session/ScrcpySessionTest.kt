package com.androidperformancestudio.screencast.session

import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbTextResult
import com.androidperformancestudio.screencast.server.ScrcpyServerLauncher
import com.androidperformancestudio.screencast.server.ScreencastQuality
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class ScrcpySessionTest {
    private val recordedCommands = mutableListOf<List<String>>()

    private val adb =
        object : AdbClient {
            override suspend fun listDevices() = emptyList<com.androidperformancestudio.platform.adb.AdbDevice>()

            override suspend fun shell(
                serial: String,
                arguments: List<String>,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ): AdbTextResult {
                recordedCommands += arguments
                // Simulate the server shell command running until killed: the
                // real `adb shell` blocks; a fast empty result is fine here.
                return AdbTextResult(0, "", "", Duration.ZERO, false, false, 0)
            }

            override suspend fun execOut(
                serial: String,
                arguments: List<String>,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ) = error("Not used")

            override suspend fun push(
                serial: String,
                localPath: Path,
                remotePath: String,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ): AdbTextResult {
                recordedCommands += listOf("push", remotePath)
                return AdbTextResult(0, "", "", Duration.ZERO, false, false, 0)
            }

            override suspend fun pull(
                serial: String,
                remotePath: String,
                localPath: Path,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ) = error("Not used")

            override suspend fun forward(
                serial: String,
                local: String,
                remote: String,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ) = error("Not used")

            override suspend fun removeForward(
                serial: String,
                local: String,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ) = error("Not used")

            override suspend fun reverse(
                serial: String,
                remote: String,
                local: String,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ): AdbTextResult {
                recordedCommands += listOf("reverse", remote, local)
                return AdbTextResult(0, "", "", Duration.ZERO, false, false, 0)
            }

            override suspend fun removeReverse(
                serial: String,
                remote: String,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ): AdbTextResult {
                recordedCommands += listOf("reverse", "--remove", remote)
                return AdbTextResult(0, "", "", Duration.ZERO, false, false, 0)
            }

            override suspend fun bugreport(
                serial: String,
                outputPath: Path,
                timeout: Duration,
                maxOutputBytesPerStream: Int,
                isCancellationRequested: () -> Boolean,
            ) = error("Not used")
        }

    @AfterTest
    fun tearDown() {
        recordedCommands.clear()
    }

    @Test
    fun `start is idempotent while a session is active`() =
        runBlocking {
            val launchCalls = java.util.concurrent.atomic.AtomicInteger(0)
            val factory =
                { client: AdbClient, serial: String, _: ScreencastQuality ->
                    object : ScrcpyServerLauncher(client, serial, ScreencastQuality.FLUENT) {
                        override suspend fun launch(localPort: Int): LaunchedServer {
                            launchCalls.incrementAndGet()
                            // Never returns: keeps the session in Connecting.
                            awaitCancellation()
                        }
                    }
                }
            val session = ScrcpySession(adb, "serial-1", launcherFactory = factory)

            session.start()
            // Wait until the launcher was actually invoked, not just Connecting:
            // the state is set before the launcher coroutine runs.
            withTimeout(5_000) {
                while (launchCalls.get() == 0) {
                    delay(10)
                }
            }
            session.start()
            session.start()

            assertEquals(1, launchCalls.get())
            session.stop()
        }

    @Test
    fun `control writes before running are dropped without error`() {
        val session = ScrcpySession(adb, "serial-1")
        session.back()
        session.home()
        session.recents()
        session.pasteText("hi")
        session.sendTouch(0, 1, 2, 3, 4)
        session.sendScroll(1, 2, 3, 4, 0f, 0f)
        session.rotateDevice()
        assertEquals(ScrcpySessionState.Idle, session.state.value)
    }

    @Test
    fun `failed startup surfaces a Failed state and removes the reverse tunnel`() = runBlocking {
        val factory =
            { client: AdbClient, serial: String, _: ScreencastQuality ->
                object : ScrcpyServerLauncher(client, serial, ScreencastQuality.FLUENT) {
                    override suspend fun launch(localPort: Int): LaunchedServer {
                        // Register the tunnel, then fail and roll it back like
                        // the real launcher does when the server errors out.
                        adb.reverse(serial, "localabstract:scrcpy_test", "tcp:$localPort")
                        try {
                            throw java.io.IOException("server failed to start")
                        } catch (error: java.io.IOException) {
                            adb.removeReverse(serial, "localabstract:scrcpy_test")
                            throw error
                        }
                    }
                }
            }
        val session = ScrcpySession(adb, "serial-1", launcherFactory = factory)

        session.start()
        withTimeout(5_000) {
            while (session.state.value !is ScrcpySessionState.Failed) {
                delay(20)
            }
        }

        val failure = session.state.value as ScrcpySessionState.Failed
        assertEquals("server failed to start", failure.message)
        assertFalse(session.isActive)

        // Cleanup removes the reverse tunnel registered before the failure.
        withTimeout(5_000) {
            while (recordedCommands.none { it.take(2) == listOf("reverse", "--remove") }) {
                delay(20)
            }
        }
        assertTrue(recordedCommands.any { it.take(2) == listOf("reverse", "--remove") })
    }
}
