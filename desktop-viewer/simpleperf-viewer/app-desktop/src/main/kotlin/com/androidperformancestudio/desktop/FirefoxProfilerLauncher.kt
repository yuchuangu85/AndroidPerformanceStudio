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
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

internal class FirefoxProfilerLauncher(
    private val exporter: GeckoProfileExportService = GeckoProfileExportService(),
    private val browse: (URI) -> Unit = { uri -> Desktop.getDesktop().browse(uri) },
    private val siteDirectory: () -> Path = { FirefoxProfilerSiteLocator().locate() },
) : Closeable {
    private val serverLock = Any()
    private var activeServer: FirefoxProfilerLocalServer? = null

    @Suppress("TooGenericExceptionCaught")
    suspend fun open(sessionDirectory: Path): Path =
        withContext(Dispatchers.IO) {
            var nextServer: FirefoxProfilerLocalServer? = null
            try {
                val profile = sessionDirectory.resolve(FIREFOX_PROFILE_FILE)
                exporter.export(sessionDirectory, profile)
                nextServer = FirefoxProfilerLocalServer(siteDirectory(), profile)
                val pageUri = nextServer.start()
                synchronized(serverLock) {
                    activeServer?.close()
                    activeServer = nextServer
                }
                browse(pageUri)
                profile
            } catch (exception: CancellationException) {
                nextServer?.close()
                throw exception
            } catch (exception: Exception) {
                nextServer?.close()
                synchronized(serverLock) {
                    if (activeServer === nextServer) {
                        activeServer = null
                    }
                }
                throw FirefoxProfilerLaunchException("Failed to open the local Firefox Profiler site", exception)
            }
        }

    override fun close() {
        synchronized(serverLock) {
            activeServer?.close()
            activeServer = null
        }
    }
}

internal class FirefoxProfilerLaunchException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

internal class FirefoxProfilerSiteLocator(
    private val configuredPath: String? = System.getProperty(FIREFOX_PROFILER_DIST_PROPERTY),
    private val environmentPath: String? = System.getenv(FIREFOX_PROFILER_DIST_ENVIRONMENT),
    private val applicationResourcesPath: String? = System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY),
    private val workingDirectory: Path = Path.of(System.getProperty("user.dir")),
) {
    fun locate(): Path {
        val explicit =
            sequenceOf(configuredPath, environmentPath, applicationResourcesPath)
                .filterNotNull()
                .filter(String::isNotBlank)
        val discovered =
            generateSequence(workingDirectory.toAbsolutePath().normalize()) { current -> current.parent }
                .map { current -> current.resolve(FIREFOX_PROFILER_DIST_RELATIVE_PATH) }
        return (explicit.map { value -> Path.of(value) } + discovered)
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .firstOrNull { path -> path.isFirefoxProfilerSite() }
            ?: throw IllegalStateException(
                "Local Firefox Profiler assets were not found. Run ./scripts/firefox-profiler.sh build " +
                    "or set $FIREFOX_PROFILER_DIST_PROPERTY.",
            )
    }
}

internal fun firefoxProfilerUrl(
    origin: URI,
    profileUri: URI,
): URI {
    val encoded = URLEncoder.encode(profileUri.toASCIIString(), StandardCharsets.UTF_8).replace("+", "%20")
    return URI.create("${origin.toASCIIString().trimEnd('/')}/from-url/$encoded/flame-graph/")
}

internal class FirefoxProfilerLocalServer(
    siteDirectory: Path,
    private val profile: Path,
) : Closeable {
    private val siteDirectory = siteDirectory.toAbsolutePath().normalize()
    private val server = ServerSocket(0, SERVER_BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))

    @Volatile
    private var closed = false

    fun start(): URI {
        require(Files.isRegularFile(this.siteDirectory.resolve(INDEX_FILE))) {
            "Firefox Profiler site does not contain $INDEX_FILE: ${this.siteDirectory}"
        }
        require(Files.isRegularFile(profile)) { "Firefox profile does not exist: $profile" }
        thread(name = "firefox-profiler-local-site", isDaemon = true) {
            serve()
        }
        val origin = URI.create("http://$LOOPBACK_ADDRESS:${server.localPort}/")
        return firefoxProfilerUrl(origin, origin.resolve(FIREFOX_PROFILE_FILE))
    }

    override fun close() {
        closed = true
        runCatching(server::close)
    }

    private fun serve() {
        while (!closed) {
            try {
                val socket = server.accept()
                thread(name = "firefox-profiler-local-request", isDaemon = true) {
                    socket.use(::handle)
                }
            } catch (_: SocketException) {
                if (!closed) {
                    close()
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val request = input.readLine().orEmpty().split(' ')
        val headers =
            generateSequence(input::readLine)
                .takeWhile(String::isNotEmpty)
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) {
                        null
                    } else {
                        line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
                    }
                }.toMap()
        val method = request.getOrNull(0)
        val requestPath = request.getOrNull(1)?.let(::requestPath)
        when {
            method == "OPTIONS" -> writeResponse(socket, "204 No Content", "text/plain", 0, false)
            method !in setOf("GET", "HEAD") || requestPath == null -> writeNotFound(socket, method == "HEAD")
            requestPath == "/$FIREFOX_PROFILE_FILE" ->
                serveFile(socket, profile, PROFILE_CONTENT_TYPE, method == "HEAD", true)
            else -> serveSiteFile(socket, requestPath, headers["accept"].orEmpty(), method == "HEAD")
        }
    }

    private fun serveSiteFile(
        socket: Socket,
        requestPath: String,
        accept: String,
        headOnly: Boolean,
    ) {
        val requested = resolveSiteFile(requestPath)
        val file =
            when {
                requested != null && Files.isRegularFile(requested) -> requested
                requestPath == "/" || "text/html" in accept || requestPath.startsWith("/from-url/") ->
                    siteDirectory.resolve(INDEX_FILE)
                else -> null
            }
        if (file == null) {
            writeNotFound(socket, headOnly)
        } else {
            serveFile(socket, file, firefoxProfilerContentType(file), headOnly, false)
        }
    }

    private fun resolveSiteFile(requestPath: String): Path? {
        val relative = URLDecoder.decode(requestPath.removePrefix("/"), StandardCharsets.UTF_8)
        val requested = siteDirectory.resolve(relative).normalize()
        return requested.takeIf { path -> path.startsWith(siteDirectory) }
    }

    private fun serveFile(
        socket: Socket,
        file: Path,
        contentType: String,
        headOnly: Boolean,
        noStore: Boolean,
    ) {
        val size = Files.size(file)
        writeResponse(socket, "200 OK", contentType, size, noStore)
        if (!headOnly) {
            Files.newInputStream(file).use { source -> source.copyTo(socket.getOutputStream()) }
        }
    }

    private fun writeNotFound(
        socket: Socket,
        headOnly: Boolean,
    ) {
        val body = "Not found".toByteArray(StandardCharsets.UTF_8)
        writeResponse(socket, "404 Not Found", "text/plain; charset=utf-8", body.size.toLong(), true)
        if (!headOnly) {
            socket.getOutputStream().write(body)
        }
    }

    private fun writeResponse(
        socket: Socket,
        status: String,
        contentType: String,
        contentLength: Long,
        noStore: Boolean,
    ) {
        val headers =
            buildString {
                append("HTTP/1.1 $status\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: *\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Cache-Control: ${if (noStore) "no-store" else "no-cache"}\r\n")
                append("Connection: close\r\n\r\n")
            }
        socket.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }
}

private fun requestPath(target: String): String? = runCatching { URI.create(target).path }.getOrNull()

private fun Path.isFirefoxProfilerSite(): Boolean = Files.isDirectory(this) && Files.isRegularFile(resolve(INDEX_FILE))

private fun firefoxProfilerContentType(file: Path): String =
    when (
        file.fileName
            .toString()
            .substringAfterLast('.', "")
            .lowercase()
    ) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "wasm" -> "application/wasm"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "ftl", "txt" -> "text/plain; charset=utf-8"
        else -> Files.probeContentType(file) ?: "application/octet-stream"
    }

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val INDEX_FILE = "index.html"
private const val FIREFOX_PROFILE_FILE = "perf_data.json.gz"
private const val PROFILE_CONTENT_TYPE = "application/gzip"
private const val SERVER_BACKLOG = 50
private const val SOCKET_TIMEOUT_MILLIS = 10_000
private const val FIREFOX_PROFILER_DIST_PROPERTY = "androidperformancestudio.firefoxProfilerDist"
private const val FIREFOX_PROFILER_DIST_ENVIRONMENT = "ANDROID_PERFORMANCE_STUDIO_FIREFOX_PROFILER_DIST"
private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
private const val FIREFOX_PROFILER_DIST_RELATIVE_PATH = "third_party/firefox-profiler/dist"
