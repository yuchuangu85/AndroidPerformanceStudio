package com.androidperformancestudio.screencast.server

import com.androidperformancestudio.platform.adb.AdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Prepares and launches the scrcpy server on a device:
 * extracts the bundled server jar, pushes it over adb, creates the reverse
 * tunnel, and starts the server process via `app_process`.
 *
 * Failure at any step rolls back previously established resources.
 */
open class ScrcpyServerLauncher(
    private val adb: AdbClient,
    private val serial: String,
    private val quality: ScreencastQuality = ScreencastQuality.DEFAULT,
    private val serverJarProvider: ServerJarProvider = BundledServerJarProvider(),
) {
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class LaunchedServer(
        val listenerPort: Int,
        val remoteServerPath: String,
        val scid: Int,
        val socketName: String,
    )

    interface ServerJarProvider {
        fun extractTo(target: Path)
    }

    /** Extracts the bundled scrcpy-server resource to a temporary file and returns its path. */
    class BundledServerJarProvider : ServerJarProvider {
        override fun extractTo(target: Path) {
            val resource = this::class.java.classLoader.getResourceAsStream(SERVER_RESOURCE)
                ?: throw IOException("Bundled scrcpy server resource is missing: $SERVER_RESOURCE")
            resource.use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * Pushes the server, creates the reverse tunnel to [localPort], and starts
     * the server shell command. On any failure, established resources are rolled
     * back before the error is rethrown.
     */
    open suspend fun launch(localPort: Int): LaunchedServer {
        val scid = Random.nextInt(1, Int.MAX_VALUE)
        val socketName = "scrcpy_%08x".format(scid)
        val remoteServerPath = "$REMOTE_DIR/$socketName.jar"

        val tempJar = Files.createTempFile("scrcpy-server", ".jar")
        try {
            serverJarProvider.extractTo(tempJar)
            adb.push(serial, tempJar, remoteServerPath)
        } finally {
            Files.deleteIfExists(tempJar)
        }

        try {
            adb.reverse(serial, "localabstract:$socketName", "tcp:$localPort")
        } catch (error: Exception) {
            rollbackPush(remoteServerPath)
            throw error
        }

        try {
            // The server runs for the whole session; start it without waiting
            // for the shell command to exit. The shell must NOT time out — the
            // default 30s adb timeout kills the device-side server mid-session.
            // The process runner rejects infinite durations, so use a day-long
            // cap; cancellation (session stop) terminates it via serverScope.
            serverScope.launch {
                runCatching {
                    adb.shell(serial, serverCommand(remoteServerPath, scid), timeout = SERVER_SHELL_TIMEOUT)
                }
                    .onSuccess { result ->
                        val output = (result.stdout + "\n" + result.stderr).trim()
                        if (output.isNotEmpty()) {
                            println("[screencast-server] exited (${result.exitCode}): $output")
                        }
                    }
                    .onFailure { error ->
                        println("[screencast-server] shell failed: ${error.message}")
                    }
            }
            return LaunchedServer(
                listenerPort = localPort,
                remoteServerPath = remoteServerPath,
                scid = scid,
                socketName = socketName,
            )
        } catch (error: Exception) {
            rollbackReverse(socketName)
            rollbackPush(remoteServerPath)
            throw error
        }
    }

    /**
     * Builds the device-side server command. `audio=false` keeps the stream
     * set to video + control only; the size/frame-rate/bit-rate caps come from
     * the selected [ScreencastQuality]. `stay_awake=true` makes the server hold a
     * wake lock for the whole session so the device does not doze/sleep while
     * screencasting — the lock is released when the server stops (session end /
     * window close).
     */
    private fun serverCommand(
        remoteServerPath: String,
        scid: Int,
    ): List<String> {
        val arguments =
            listOf(
                "log_level=info",
                "scid=${scid.toString(HEX_RADIX)}",
                "video=true",
                "audio=false",
                "control=true",
                "tunnel_forward=false",
                "stay_awake=true",
            ) + quality.toServerOptions()
        return listOf(
            "env",
            "CLASSPATH=$remoteServerPath",
            "app_process",
            "/system/bin",
            SERVER_CLASS,
            SCRCPY_VERSION,
        ) + arguments
    }

    /** Removes the reverse tunnel and the pushed server jar, and stops tracking the shell. */
    suspend fun cleanup(launched: LaunchedServer) {
        serverScope.cancel()
        // Give the cancelled shell a beat to tear down the device-side process
        // tree before removing the tunnel and the pushed jar.
        delay(CLEANUP_SETTLE_DELAY_MS)
        rollbackReverse(launched.socketName)
        rollbackPush(launched.remoteServerPath)
    }

    private suspend fun rollbackPush(remoteServerPath: String) {
        runCatching { adb.shell(serial, listOf("rm", "-f", remoteServerPath)) }
            .onFailure { error ->
                println("[screencast] cleanup: failed to remove server jar: ${error.message}")
            }
    }

    private suspend fun rollbackReverse(socketName: String) {
        // A leftover tunnel blocks the next session with the same name only if
        // the scid repeats (it does not), but it leaks; retry once on failure.
        runCatching { adb.removeReverse(serial, "localabstract:$socketName") }
            .recoverCatching { runCatching { adb.removeReverse(serial, "localabstract:$socketName") }.getOrNull() }
            .onSuccess { println("[screencast] cleanup: reverse $socketName removed") }
            .onFailure { error ->
                println("[screencast] cleanup: failed to remove reverse tunnel: ${error.message}")
            }
    }

    companion object {
        private const val HEX_RADIX = 16

        /** The server shell outlives any real session; see [launch]. */
        val SERVER_SHELL_TIMEOUT: Duration = Duration.parse("24h")

        /** Grace period between cancelling the shell and removing its resources. */
        const val CLEANUP_SETTLE_DELAY_MS = 500L

        const val SERVER_RESOURCE = "scrcpy/scrcpy-server"
        const val SERVER_CLASS = "com.genymobile.scrcpy.Server"
        const val SCRCPY_VERSION = "3.1"
        const val REMOTE_DIR = "/data/local/tmp/scrcpy_aps"
    }
}
