package com.androidperformancestudio.desktop

import com.androidperformancestudio.export.GeckoProfileExportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

internal class FirefoxProfilerLauncher(
    private val exporter: GeckoProfileExportService = GeckoProfileExportService(),
    private val browse: (URI) -> Unit = { uri -> Desktop.getDesktop().browse(uri) },
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun open(sessionDirectory: Path): Path =
        withContext(Dispatchers.IO) {
            try {
                val profile = sessionDirectory.resolve(FIREFOX_PROFILE_FILE)
                exporter.export(sessionDirectory, profile)
                val server = FirefoxProfileLoopbackServer(profile)
                val profileUri = server.start()
                browse(firefoxProfilerUrl(profileUri))
                profile
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw FirefoxProfilerLaunchException("Failed to open Firefox Profiler", exception)
            }
        }
}

internal class FirefoxProfilerLaunchException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

internal fun firefoxProfilerUrl(profileUri: URI): URI {
    val encoded = URLEncoder.encode(profileUri.toASCIIString(), StandardCharsets.UTF_8).replace("+", "%20")
    return URI.create("$FIREFOX_PROFILER_ORIGIN/from-url/$encoded/flame-graph/")
}

internal class FirefoxProfileLoopbackServer(
    private val profile: Path,
) {
    fun start(): URI {
        require(Files.isRegularFile(profile)) { "Firefox profile does not exist: $profile" }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = ACCEPT_POLL_MILLIS }
        thread(name = "firefox-profiler-profile-server", isDaemon = true) {
            server.use { serveUntilFetched(it) }
        }
        return URI.create("http://127.0.0.1:${server.localPort}/$FIREFOX_PROFILE_FILE")
    }

    private fun serveUntilFetched(server: ServerSocket) {
        val deadline = System.nanoTime() + SERVER_LIFETIME.inWholeNanoseconds
        var fetched = false
        while (!fetched && System.nanoTime() < deadline) {
            try {
                server.accept().use { socket -> fetched = handle(socket) }
            } catch (_: SocketTimeoutException) {
                // Poll the deadline so an abandoned browser launch does not keep a socket open forever.
            }
        }
    }

    private fun handle(socket: Socket): Boolean {
        val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val request = input.readLine().orEmpty().split(' ')
        generateSequence(input::readLine).takeWhile(String::isNotEmpty).forEach { _ -> }
        val method = request.getOrNull(0)
        val path = request.getOrNull(1)
        return when {
            method == "OPTIONS" -> {
                writeHeaders(socket, "204 No Content", 0)
                false
            }
            method == "GET" && path == "/$FIREFOX_PROFILE_FILE" -> {
                val size = Files.size(profile)
                writeHeaders(socket, "200 OK", size)
                Files.newInputStream(profile).use { source -> source.copyTo(socket.getOutputStream()) }
                true
            }
            else -> {
                writeHeaders(socket, "404 Not Found", 0)
                false
            }
        }
    }

    private fun writeHeaders(
        socket: Socket,
        status: String,
        contentLength: Long,
    ) {
        BufferedWriter(socket.getOutputStream().writer(StandardCharsets.US_ASCII)).apply {
            append("HTTP/1.1 $status\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
            flush()
        }
    }
}

private const val FIREFOX_PROFILER_ORIGIN = "https://profiler.firefox.com"
private const val FIREFOX_PROFILE_FILE = "perf_data.json.gz"
private const val ACCEPT_POLL_MILLIS = 1_000
private val SERVER_LIFETIME = 5.minutes
