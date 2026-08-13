package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.winscope.model.WinscopeCapabilities
import com.androidperformancestudio.winscope.model.WinscopeTimeline
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal fun canOpenInUpstreamWinscope(timeline: WinscopeTimeline?): Boolean =
    timeline?.entries?.any { (source, entries) ->
        source in WinscopeCapabilities.CORE_SOURCES && entries.isNotEmpty()
    } == true

class UpstreamWinscopeServer(
    private val port: Int = 0,
    private val evidenceLifetime: Duration = Duration.ofMinutes(10),
    private val browserOpen: (URI) -> Unit = ::openSystemBrowser,
) : AutoCloseable {
    private val lock = Any()
    private var server: HttpServer? = null
    private var executor = Executors.newSingleThreadExecutor()
    private var expiryExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var expiry: ScheduledFuture<*>? = null
    private var assets: Path? = null

    @Volatile
    private var evidence: Evidence? = null

    fun start(assetsDirectory: Path): StudioResult<Unit> =
        synchronized(lock) {
            if (server != null) return StudioResult.Success(Unit)
            try {
                val root = assetsDirectory.toRealPath()
                require(Files.isRegularFile(root.resolve("index.html"))) {
                    "Upstream Winscope assets are missing index.html: $root"
                }
                assets = root
                server =
                    HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0).apply {
                        createContext("/evidence", ::serveEvidence)
                        createContext("/", ::serveAsset)
                        this.executor = this@UpstreamWinscopeServer.executor
                        start()
                    }
                StudioResult.Success(Unit)
            } catch (exception: Exception) {
                server?.stop(0)
                server = null
                assets = null
                failure(
                    "UPSTREAM_WINSCOPE_SERVER_START_FAILED",
                    exception.message ?: "Unable to start the upstream Winscope server",
                    exception,
                )
            }
        }

    fun openEvidence(path: Path): StudioResult<URI> =
        synchronized(lock) {
            try {
                require(Files.isRegularFile(path) && Files.size(path) > 0) { "Winscope evidence package does not exist or is empty: $path" }
                val running = checkNotNull(server) { "Upstream Winscope server is not running" }
                val token = ByteArray(32).also(SECURE_RANDOM::nextBytes).let(URL_ENCODER::encodeToString)
                evidence = Evidence(path.toRealPath(), token)
                expiry?.cancel(false)
                expiry =
                    expiryExecutor.schedule(
                        { evidence = evidence?.takeUnless { it.token == token } },
                        evidenceLifetime.toMillis().coerceAtLeast(1),
                        TimeUnit.MILLISECONDS,
                    )
                val uri = URI.create("http://127.0.0.1:${running.address.port}/?session=$token")
                browserOpen(uri)
                StudioResult.Success(uri)
            } catch (exception: Exception) {
                evidence = null
                failure("UPSTREAM_WINSCOPE_OPEN_FAILED", exception.message ?: "Unable to open upstream Winscope", exception)
            }
        }

    fun invalidateEvidence() {
        synchronized(lock) {
            expiry?.cancel(false)
            expiry = null
            evidence = null
        }
    }

    override fun close() {
        synchronized(lock) {
            invalidateEvidence()
            server?.stop(0)
            server = null
            assets = null
            executor.shutdownNow()
            expiryExecutor.shutdownNow()
        }
    }

    private fun serveEvidence(exchange: HttpExchange) {
        exchange.use {
            if (!exchange.isGet()) return@use exchange.emptyResponse(405)
            val requestedToken =
                exchange.requestURI.rawQuery
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("session=") }
                    ?.substringAfter('=')
            val current = evidence
            if (current == null || requestedToken != current.token || !Files.isRegularFile(current.path)) {
                return@use exchange.emptyResponse(404)
            }
            exchange.secureHeaders()
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Content-Type", "application/zip")
            exchange.responseHeaders.set("Content-Disposition", "attachment; filename=aps-winscope-evidence.zip")
            exchange.responseHeaders.set("X-Winscope-Filename", "aps-winscope-evidence.zip")
            exchange.sendResponseHeaders(200, Files.size(current.path))
            exchange.responseBody.use { output -> Files.copy(current.path, output) }
        }
    }

    private fun serveAsset(exchange: HttpExchange) {
        exchange.use {
            if (!exchange.isGet()) return@use exchange.emptyResponse(405)
            val root = assets ?: return@use exchange.emptyResponse(503)
            val requestPath = exchange.requestURI.path
            if ('\\' in requestPath || '\u0000' in requestPath) return@use exchange.emptyResponse(404)
            val relative = requestPath.removePrefix("/").ifBlank { "index.html" }
            val candidate = root.resolve(relative).normalize()
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) return@use exchange.emptyResponse(404)
            val real = runCatching(candidate::toRealPath).getOrNull()
            if (real == null || !real.startsWith(root)) return@use exchange.emptyResponse(404)
            exchange.secureHeaders()
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Content-Type", contentType(real))
            exchange.sendResponseHeaders(200, Files.size(real))
            exchange.responseBody.use { output -> Files.copy(real, output) }
        }
    }

    companion object {
        fun tryFindAssetsDirectory(
            repoRoot: Path = Paths.get(".").toAbsolutePath(),
            configuredPath: String? = System.getProperty(ASSETS_PROPERTY),
            environmentPath: String? = System.getenv(ASSETS_ENVIRONMENT),
            applicationResourcesPath: String? = System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY),
        ): Path? {
            sequenceOf(
                configuredPath?.takeIf(String::isNotBlank)?.let(Path::of),
                environmentPath?.takeIf(String::isNotBlank)?.let(Path::of),
                applicationResourcesPath?.takeIf(String::isNotBlank)?.let { Path.of(it).resolve("winscope-ui") },
            ).filterNotNull().firstOrNull(::isAssetsDirectory)?.let { return it }
            return generateSequence(repoRoot.normalize()) { it.parent }
                .take(6)
                .map { it.resolve("third_party/aosp-winscope/dist") }
                .firstOrNull(::isAssetsDirectory)
        }

        private fun isAssetsDirectory(path: Path): Boolean = Files.isDirectory(path) && Files.isRegularFile(path.resolve("index.html"))

        private const val ASSETS_PROPERTY = "androidperformancestudio.winscopeUiDist"
        private const val ASSETS_ENVIRONMENT = "ANDROID_PERFORMANCE_STUDIO_WINSCOPE_UI_DIST"
        private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
        private val SECURE_RANDOM = SecureRandom()
        private val URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
    }

    private data class Evidence(
        val path: Path,
        val token: String,
    )
}

private fun HttpExchange.isGet(): Boolean = requestMethod == "GET"

private fun HttpExchange.secureHeaders() {
    responseHeaders.set("X-Content-Type-Options", "nosniff")
    responseHeaders.set("Referrer-Policy", "no-referrer")
    responseHeaders.set(
        "Content-Security-Policy",
        "default-src 'self' blob: data:; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' blob:; worker-src 'self' blob:",
    )
}

private fun HttpExchange.emptyResponse(status: Int) {
    secureHeaders()
    sendResponseHeaders(status, -1)
}

private fun contentType(path: Path): String =
    when (
        path.fileName
            .toString()
            .substringAfterLast('.', "")
            .lowercase()
    ) {
        "html" -> "text/html; charset=UTF-8"
        "js" -> "application/javascript; charset=UTF-8"
        "css" -> "text/css; charset=UTF-8"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ttf" -> "font/ttf"
        "woff2" -> "font/woff2"
        "json" -> "application/json; charset=UTF-8"
        else -> "application/octet-stream"
    }

private fun openSystemBrowser(uri: URI) {
    require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        "No system browser is available to open upstream Winscope"
    }
    Desktop.getDesktop().browse(uri)
}

private fun <T> failure(
    code: String,
    message: String,
    cause: Throwable? = null,
): StudioResult<T> =
    StudioResult.Failure(
        StudioError(
            category = ErrorCategory.IO,
            code = code,
            message = message,
            cause = cause,
        ),
    )
