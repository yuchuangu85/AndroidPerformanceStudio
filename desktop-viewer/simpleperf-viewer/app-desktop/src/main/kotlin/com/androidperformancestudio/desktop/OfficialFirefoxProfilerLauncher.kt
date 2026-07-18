package com.androidperformancestudio.desktop

import com.androidperformancestudio.export.GeckoProfileExportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

internal class OfficialFirefoxProfilerLauncher(
    private val exporter: GeckoProfileExportService = GeckoProfileExportService(),
    private val browse: (URI) -> Unit = { uri -> Desktop.getDesktop().browse(uri) },
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun open(sessionDirectory: Path): Path =
        withContext(Dispatchers.IO) {
            var server: FirefoxProfileTransferServer? = null
            try {
                val profile = sessionDirectory.resolve(OFFICIAL_FIREFOX_PROFILE_FILE)
                exporter.export(sessionDirectory, profile)
                server = FirefoxProfileTransferServer(profile)
                val profileUri = server.start()
                browse(officialFirefoxProfilerUrl(profileUri))
                profile
            } catch (exception: CancellationException) {
                server?.close()
                throw exception
            } catch (exception: Exception) {
                server?.close()
                throw FirefoxProfilerLaunchException("Failed to open the official Firefox Profiler website", exception)
            }
        }
}

internal fun officialFirefoxProfilerUrl(profileUri: URI): URI {
    val encoded = URLEncoder.encode(profileUri.toASCIIString(), StandardCharsets.UTF_8).replace("+", "%20")
    return URI.create("$OFFICIAL_FIREFOX_PROFILER_ORIGIN/from-url/$encoded/flame-graph/")
}

internal class FirefoxProfileTransferServer(
    private val profile: Path,
) : Closeable {
    private val server =
        ServerSocket(0, 1, InetAddress.getByName(OFFICIAL_LOOPBACK_ADDRESS)).apply {
            soTimeout = OFFICIAL_ACCEPT_POLL_MILLIS
        }

    @Volatile
    private var closed = false

    fun start(): URI {
        require(Files.isRegularFile(profile)) { "Firefox profile does not exist: $profile" }
        thread(name = "firefox-profiler-profile-transfer", isDaemon = true) {
            server.use { serveUntilFetched(it) }
        }
        return URI.create("http://$OFFICIAL_LOOPBACK_ADDRESS:${server.localPort}/$OFFICIAL_FIREFOX_PROFILE_FILE")
    }

    override fun close() {
        closed = true
        runCatching(server::close)
    }

    private fun serveUntilFetched(server: ServerSocket) {
        val deadline = System.nanoTime() + OFFICIAL_SERVER_LIFETIME.inWholeNanoseconds
        var fetched = false
        while (!closed && !fetched && System.nanoTime() < deadline) {
            try {
                server.accept().use { socket -> fetched = handle(socket) }
            } catch (_: SocketTimeoutException) {
                // Poll the deadline so an abandoned browser launch does not keep the socket open forever.
            } catch (_: SocketException) {
                return
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
            method == "GET" && path == "/$OFFICIAL_FIREFOX_PROFILE_FILE" -> {
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
        val headers =
            buildString {
                append("HTTP/1.1 $status\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: *\r\n")
                append("Access-Control-Allow-Private-Network: true\r\n")
                append("Content-Type: application/gzip\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
        socket.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }
}

private const val OFFICIAL_FIREFOX_PROFILER_ORIGIN = "https://profiler.firefox.com"
private const val OFFICIAL_LOOPBACK_ADDRESS = "127.0.0.1"
private const val OFFICIAL_FIREFOX_PROFILE_FILE = "perf_data.json.gz"
private const val OFFICIAL_ACCEPT_POLL_MILLIS = 1_000
private val OFFICIAL_SERVER_LIFETIME = 5.minutes
