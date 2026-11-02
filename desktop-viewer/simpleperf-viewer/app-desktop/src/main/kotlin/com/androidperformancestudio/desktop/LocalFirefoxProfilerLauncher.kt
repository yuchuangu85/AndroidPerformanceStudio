package com.androidperformancestudio.desktop

import com.androidperformancestudio.export.GeckoProfileExportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.Semaphore
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

internal class LocalFirefoxProfilerLauncher(
    private val exporter: GeckoProfileExportService = GeckoProfileExportService(),
    private val browse: (URI) -> Unit = { uri -> Desktop.getDesktop().browse(uri) },
    private val siteDirectory: () -> Path = { FirefoxProfilerSiteLocator().locate() },
) : Closeable {
    private val serverLock = Any()
    private var activeServer: FirefoxProfilerLocalServer? = null

    @Suppress("TooGenericExceptionCaught")
    suspend fun open(sessionDirectory: Path, bridge: SimpleperfWebBridgeSession? = null): Path =
        withContext(Dispatchers.IO) {
            var nextServer: FirefoxProfilerLocalServer? = null
            try {
                val profile = sessionDirectory.resolve(FIREFOX_PROFILE_FILE)
                exporter.export(sessionDirectory, profile)
                nextServer = FirefoxProfilerLocalServer(siteDirectory(), profile, bridge)
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
    private val bridge: SimpleperfWebBridgeSession? = null,
) : Closeable {
    private val siteDirectory = siteDirectory.toAbsolutePath().normalize()
    private val server = ServerSocket(0, SERVER_BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
    private val requestSlots = Semaphore(16)
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val origin: String get() = "http://$LOOPBACK_ADDRESS:${server.localPort}"


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
        bridge?.close()
        activeSockets.forEach { runCatching(it::close) }
        runCatching(server::close)
    }

    private fun serve() {
        while (!closed) {
            try {
                val socket = server.accept()
                if (!requestSlots.tryAcquire()) { socket.close(); continue }
                activeSockets.add(socket)
                thread(name = "firefox-profiler-local-request", isDaemon = true) {
                    try { socket.use { runCatching { handle(it) } } } finally { activeSockets.remove(socket); requestSlots.release() }
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
        val input = socket.getInputStream().buffered()
        val request = input.readHttpLine(8192)?.split(' ') ?: return
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = input.readHttpLine(8192) ?: return
            headerBytes += line.length
            if (headerBytes > 16384) return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) return
            val key = line.substring(0, separator).lowercase()
            if (key in headers) return
            headers[key] = line.substring(separator + 1).trim()
        }
        val method = request.getOrNull(0)
        val requestPath = request.getOrNull(1)?.let(::requestPath)
        if (!socket.inetAddress.isLoopbackAddress || headers["host"] != "$LOOPBACK_ADDRESS:${server.localPort}") {
            writeJson(socket, "403 Forbidden", "{\"code\":\"E_BRIDGE_HOST\"}")
            return
        }
        if (requestPath?.startsWith("/aps-ai/") == true) {
            handleBridge(socket, input, method.orEmpty(), requestPath, headers)
            return
        }
        when {
            method == "OPTIONS" -> writeResponse(socket, "204 No Content", "text/plain", 0, false)
            method !in setOf("GET", "HEAD") || requestPath == null -> writeNotFound(socket, method == "HEAD")
            requestPath == "/$FIREFOX_PROFILE_FILE" -> serveFile(socket, profile, PROFILE_CONTENT_TYPE, method == "HEAD", true)
            else -> serveSiteFile(socket, requestPath, headers["accept"].orEmpty(), method == "HEAD")
        }
    }

    private fun handleBridge(socket: Socket, input: InputStream, method: String, path: String, headers: Map<String, String>) {
        val active = bridge
        if (active == null) { writeNotFound(socket, false); return }
        val sourceOrigin = headers["origin"]
        if (!active.acceptsToken(headers["x-aps-bridge-token"]) ||
            (sourceOrigin != null && sourceOrigin != origin) ||
            headers["sec-fetch-site"]?.let { it != "same-origin" && it != "none" } == true ||
            (method == "POST" && sourceOrigin != origin)) {
            writeJson(socket, "403 Forbidden", "{\"code\":\"E_BRIDGE_AUTH\"}")
            return
        }
        when {
            path == "/aps-ai/context" && method == "GET" -> {
                val context = runCatching(active::attach).getOrNull()
                if (context == null) writeJson(socket, "410 Gone", active.result().json()) else writeJson(socket, "200 OK", context)
            }
            path == "/aps-ai/result" && method == "GET" -> writeJson(socket, "200 OK", active.result().json())
            path == "/aps-ai/event" && method == "POST" -> {
                val length = headers["content-length"]?.toIntOrNull()
                if (headers["content-type"]?.substringBefore(';') != "application/json" || headers.containsKey("transfer-encoding") || length == null || length !in 1..MAX_BRIDGE_BYTES) {
                    writeJson(socket, "413 Payload Too Large", "{\"code\":\"E_BRIDGE_LIMIT\"}")
                    return
                }
                val body = input.readNBytes(length)
                if (body.size != length) { writeJson(socket, "400 Bad Request", "{\"code\":\"E_BRIDGE_PROTOCOL\"}"); return }
                writeJson(socket, "200 OK", active.submit(body).json())
            }
            else -> writeNotFound(socket, false)
        }
    }

    private fun writeJson(socket: Socket, status: String, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeResponse(socket, status, "application/json; charset=utf-8", bytes.size.toLong(), true)
        socket.getOutputStream().write(bytes)
    }

    private fun serveSiteFile(
        socket: Socket,
        requestPath: String,
        accept: String,
        headOnly: Boolean,
    ) {
        if (requestPath == "/" || requestPath.startsWith("/from-url/")) {
            serveIndex(socket, headOnly)
            return
        }
        val requested = resolveSiteFile(requestPath)
        val file =
            when {
                requested != null && Files.isRegularFile(requested) -> requested
                "text/html" in accept -> siteDirectory.resolve(INDEX_FILE)
                else -> null
            }
        if (file == null) {
            writeNotFound(socket, headOnly)
        } else {
            serveFile(socket, file, firefoxProfilerContentType(file), headOnly, false)
        }
    }

    private fun serveIndex(socket: Socket, headOnly: Boolean) {
        val active = bridge
        if (active == null) { serveFile(socket, siteDirectory.resolve(INDEX_FILE), "text/html; charset=utf-8", headOnly, false); return }
        val html = Files.readString(siteDirectory.resolve(INDEX_FILE))
        val adapter = "<script>" + simpleperfWebBridgeAdapter(active.token) + "</script>"
        val injected = if (html.contains("</body>")) html.replace("</body>", adapter + "</body>") else html + adapter
        val bytes = injected.toByteArray(StandardCharsets.UTF_8)
        writeResponse(socket, "200 OK", "text/html; charset=utf-8", bytes.size.toLong(), true)
        if (!headOnly) socket.getOutputStream().write(bytes)
    }

    private fun resolveSiteFile(requestPath: String): Path? =
        runCatching {
            val relative = URLDecoder.decode(requestPath.removePrefix("/"), StandardCharsets.UTF_8)
            val requested = siteDirectory.resolve(relative).normalize()
            requested.takeIf { path ->
                path.startsWith(siteDirectory) && !Files.isSymbolicLink(path) &&
                    (!Files.exists(path) || path.toRealPath().startsWith(siteDirectory.toRealPath()))
            }
        }.getOrNull()

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
                append("X-Content-Type-Options: nosniff\r\n")
                append("Referrer-Policy: no-referrer\r\n")
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

private fun requestPath(target: String): String? = runCatching { URI.create(target).rawPath }.getOrNull()

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

private fun InputStream.readHttpLine(limit: Int): String? {
    val bytes = java.io.ByteArrayOutputStream()
    while (bytes.size() <= limit) {
        val value = read()
        if (value < 0) return null
        if (value == 10) return bytes.toByteArray().toString(StandardCharsets.US_ASCII).removeSuffix("\r")
        bytes.write(value)
    }
    return null
}
