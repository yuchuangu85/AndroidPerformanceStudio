package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import java.awt.Desktop
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

internal class UserDocumentationLauncher(
    private val browse: (URI) -> Unit = { uri -> Desktop.getDesktop().browse(uri) },
    private val documentationRoot: () -> Path = { UserDocumentationSiteLocator().locate() },
) : Closeable {
    private val serverLock = Any()
    private var activeServer: UserDocumentationServer? = null

    fun open(language: UiLanguage) {
        val origin =
            synchronized(serverLock) {
                activeServer?.origin ?: UserDocumentationServer(documentationRoot()).also { server ->
                    server.start()
                    activeServer = server
                }.origin
            }
        browse(origin.resolve("${language.documentationDirectoryName}/"))
    }

    override fun close() {
        synchronized(serverLock) {
            activeServer?.close()
            activeServer = null
        }
    }
}

internal class UserDocumentationSiteLocator(
    private val applicationResourcesPath: String? = System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY),
    private val workingDirectory: Path = Path.of(System.getProperty("user.dir")),
) {
    fun locate(): Path {
        val packaged =
            sequenceOf(applicationResourcesPath)
                .filterNotNull()
                .filter(String::isNotBlank)
                .map(Path::of)
        val discovered =
            generateSequence(workingDirectory.toAbsolutePath().normalize()) { current -> current.parent }
        return (packaged + discovered)
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .firstOrNull(Path::isUserDocumentationRoot)
            ?: error("Bundled Firefox Profiler user documentation was not found.")
    }
}

internal class UserDocumentationServer(
    root: Path,
) : Closeable {
    private val root = root.toAbsolutePath().normalize()
    private val server = ServerSocket(0, SERVER_BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))

    @Volatile
    private var closed = false

    val origin: URI = URI.create("http://$LOOPBACK_ADDRESS:${server.localPort}/")

    fun start() {
        require(root.isUserDocumentationRoot()) { "Invalid user documentation root: $root" }
        thread(name = "user-documentation-site", isDaemon = true) { serve() }
    }

    override fun close() {
        closed = true
        runCatching(server::close)
    }

    private fun serve() {
        while (!closed) {
            try {
                val socket = server.accept()
                thread(name = "user-documentation-request", isDaemon = true) {
                    socket.use(::handle)
                }
            } catch (_: SocketException) {
                if (!closed) close()
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val request = input.readLine().orEmpty().split(' ')
        generateSequence(input::readLine).takeWhile(String::isNotEmpty).forEach { _ -> }
        val method = request.getOrNull(0)
        val requestPath = request.getOrNull(1)?.let(::requestPath)
        val file = requestPath?.let(::resolveFile)
        when {
            method !in setOf("GET", "HEAD") || file == null || !Files.isRegularFile(file) ->
                writeNotFound(socket, method == "HEAD")
            else -> serveFile(socket, file, method == "HEAD")
        }
    }

    private fun resolveFile(requestPath: String): Path? {
        val relative = requestPath.removePrefix("/")
        var requested = root.resolve(relative).normalize()
        if (!requested.startsWith(root)) return null
        if (Files.isDirectory(requested)) requested = requested.resolve(INDEX_FILE)
        return requested
    }

    private fun serveFile(
        socket: Socket,
        file: Path,
        headOnly: Boolean,
    ) {
        val size = Files.size(file)
        writeHeaders(socket, "200 OK", documentationContentType(file), size)
        if (!headOnly) Files.newInputStream(file).use { source -> source.copyTo(socket.getOutputStream()) }
    }

    private fun writeNotFound(
        socket: Socket,
        headOnly: Boolean,
    ) {
        val body = "Not found".toByteArray(StandardCharsets.UTF_8)
        writeHeaders(socket, "404 Not Found", "text/plain; charset=utf-8", body.size.toLong())
        if (!headOnly) socket.getOutputStream().write(body)
    }

    private fun writeHeaders(
        socket: Socket,
        status: String,
        contentType: String,
        contentLength: Long,
    ) {
        val headers =
            "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: $contentLength\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }
}

private fun requestPath(target: String): String? = runCatching { URI.create(target).path }.getOrNull()

private fun Path.isUserDocumentationRoot(): Boolean =
    UiLanguage.entries.all { language ->
        Files.isRegularFile(resolve(language.documentationDirectoryName).resolve(INDEX_FILE))
    }

private val UiLanguage.documentationDirectoryName: String
    get() =
        when (this) {
            UiLanguage.ENGLISH -> "docs-user"
            UiLanguage.SIMPLIFIED_CHINESE -> "docs-user-zh"
        }

private fun documentationContentType(file: Path): String =
    when (file.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "md" -> "text/markdown; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val SERVER_BACKLOG = 16
private const val SOCKET_TIMEOUT_MILLIS = 5_000
private const val INDEX_FILE = "index.html"
