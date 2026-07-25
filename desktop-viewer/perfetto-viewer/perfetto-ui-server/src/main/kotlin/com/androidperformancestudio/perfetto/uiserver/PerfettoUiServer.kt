package com.androidperformancestudio.perfetto.uiserver

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.SimpleFileServer
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PerfettoUiServer(
    private val port: Int = DEFAULT_PORT,
) {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var currentTraceFile: Path? = null
    private var hasUiAssets: Boolean = false
    val isRunning: Boolean get() = server != null

    fun start(uiAssetsDir: Path?): StudioResult<Unit> =
        try {
            if (server != null) stop()
            server =
                HttpServer.create(InetSocketAddress(port), 0).apply {
                    createContext("/trace") { exchange ->
                        val traceFile = currentTraceFile
                        if (traceFile != null && Files.exists(traceFile)) {
                            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                            exchange.sendResponseHeaders(200, Files.size(traceFile))
                            exchange.responseBody.use { output -> Files.copy(traceFile, output) }
                        } else {
                            exchange.sendResponseHeaders(404, -1)
                        }
                    }
                    if (uiAssetsDir != null &&
                        Files.isDirectory(uiAssetsDir) &&
                        uiAssetsDir.resolve("index.html").toFile().exists()
                    ) {
                        createContext("/", SimpleFileServer.createFileHandler(uiAssetsDir))
                        hasUiAssets = true
                    } else {
                        createContext("/") { exchange ->
                            val html = buildEmbedPage()
                            exchange.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                            exchange.sendResponseHeaders(200, html.length.toLong())
                            exchange.responseBody.use { it.write(html.toByteArray()) }
                        }
                        hasUiAssets = false
                    }
                    executor = Executors.newSingleThreadExecutor().also { this@PerfettoUiServer.executor = it }
                    start()
                }
            StudioResult.Success(Unit)
        } catch (e: Exception) {
            stop()
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "SERVER_START_FAILED",
                    message = e.message ?: "Unknown error",
                    cause = e,
                ),
            )
        }

    fun openTrace(traceFile: Path): StudioResult<Unit> =
        try {
            if (!Files.isRegularFile(traceFile)) {
                return StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.IO,
                        code = "TRACE_FILE_NOT_FOUND",
                        message = "Trace file does not exist: $traceFile",
                    ),
                )
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                return StudioResult.Failure(
                    StudioError(
                        category = ErrorCategory.CONFIGURATION,
                        code = "BROWSER_NOT_SUPPORTED",
                        message = "No system browser is available to open the Perfetto UI",
                    ),
                )
            }
            currentTraceFile = traceFile
            val targetUrl =
                if (hasUiAssets) {
                    "http://localhost:$port/#!/?url=http://localhost:$port/trace"
                } else {
                    "http://localhost:$port/"
                }
            Desktop.getDesktop().browse(URI.create(targetUrl))
            StudioResult.Success(Unit)
        } catch (exception: Exception) {
            StudioResult.Failure(
                StudioError(
                    category = ErrorCategory.IO,
                    code = "BROWSER_OPEN_FAILED",
                    message = exception.message ?: "Failed to open the Perfetto UI",
                    cause = exception,
                ),
            )
        }

    fun stop() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        currentTraceFile = null
        hasUiAssets = false
    }

    private fun buildEmbedPage(): String {
        val p = port
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Perfetto Trace</title>
<style>body{margin:0;background:#111}iframe{width:100vw;height:100vh;border:none}</style></head>
<body><iframe id="pf" src="https://ui.perfetto.dev/#!/?mode=embedded"></iframe>
<script>
var url="http://localhost:$p/trace";
document.getElementById("pf").onload=function(){
 fetch(url).then(function(r){return r.arrayBuffer()}).then(function(buf){
  document.getElementById("pf").contentWindow.postMessage({
   perfetto:{buffer:buf,title:"APS Trace"}
  },"*");
 }).catch(function(e){
  document.body.innerHTML='<div style="color:#fff;text-align:center;padding-top:40vh;font:18px sans-serif"><p>Failed: '+e.message+'</p><p><a href="https://ui.perfetto.dev" style="color:#5af">Open Perfetto UI</a></p></div>';
 });
};
</script></body></html>"""
    }

    companion object {
        const val DEFAULT_PORT = 8090

        fun tryFindUiAssetsDir(
            repoRoot: Path = Paths.get(".").toAbsolutePath(),
            configuredPath: String? = System.getProperty(PERFETTO_UI_DIST_PROPERTY),
            environmentPath: String? = System.getenv(PERFETTO_UI_DIST_ENVIRONMENT),
            applicationResourcesPath: String? = System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY),
        ): Path? {
            sequenceOf(
                configuredPath?.takeIf(String::isNotBlank)?.let(Path::of),
                environmentPath?.takeIf(String::isNotBlank)?.let(Path::of),
                applicationResourcesPath?.takeIf(String::isNotBlank)?.let { Path.of(it).resolve("perfetto-ui") },
            ).filterNotNull()
                .firstOrNull(::isUiAssetsDirectory)
                ?.let { return it }
            val roots = generateSequence(repoRoot.normalize()) { it.parent }.take(5).toList()
            roots
                .asSequence()
                .map { it.resolve("third_party/perfetto/out/ui/dist") }
                .firstOrNull(::isUiAssetsDirectory)
                ?.let { return it }
            val downloaded =
                Paths.get(
                    System.getProperty("user.home"),
                    ".android-performance-studio",
                    "tools",
                    "perfetto",
                    "ui",
                )
            if (isUiAssetsDirectory(downloaded)) {
                return downloaded
            }
            return null
        }

        private fun isUiAssetsDirectory(path: Path): Boolean = Files.isDirectory(path) && Files.isRegularFile(path.resolve("index.html"))

        private const val PERFETTO_UI_DIST_PROPERTY = "androidperformancestudio.perfettoUiDist"
        private const val PERFETTO_UI_DIST_ENVIRONMENT = "ANDROID_PERFORMANCE_STUDIO_PERFETTO_UI_DIST"
        private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
    }
}
