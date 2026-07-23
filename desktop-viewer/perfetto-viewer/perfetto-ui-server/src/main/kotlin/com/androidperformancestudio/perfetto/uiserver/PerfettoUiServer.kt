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

class PerfettoUiServer(
    private val port: Int = DEFAULT_PORT,
) {
    private var server: HttpServer? = null
    private var currentTraceFile: Path? = null
    private var hasUiAssets: Boolean = false
    val isRunning: Boolean get() = server != null

    fun start(uiAssetsDir: Path?): StudioResult<Unit> {
        return try {
            server = HttpServer.create(InetSocketAddress(port), 0).apply {
                createContext("/trace") { exchange ->
                    val traceFile = currentTraceFile
                    if (traceFile != null && Files.exists(traceFile)) {
                        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                        exchange.sendResponseHeaders(200, Files.size(traceFile))
                        Files.copy(traceFile, exchange.responseBody)
                        exchange.responseBody.close()
                    } else {
                        exchange.sendResponseHeaders(404, -1)
                    }
                }
                if (uiAssetsDir != null && Files.isDirectory(uiAssetsDir) &&
                    uiAssetsDir.resolve("index.html").toFile().exists()
                ) {
                    createContext("/") { exchange ->
                        exchange.responseHeaders.add("Cross-Origin-Opener-Policy", "same-origin")
                        exchange.responseHeaders.add("Cross-Origin-Embedder-Policy", "require-corp")
                    }
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
                executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                start()
            }
            StudioResult.Success(Unit)
        } catch (e: Exception) {
            StudioResult.Failure(StudioError(
                category = ErrorCategory.IO, code = "SERVER_START_FAILED",
                message = e.message ?: "Unknown error", cause = e,
            ))
        }
    }

    fun openTrace(traceFile: Path) {
        currentTraceFile = traceFile
        val targetUrl = if (hasUiAssets) {
            "http://localhost:$port/#!/?url=http://localhost:$port/trace"
        } else {
            "http://localhost:$port/"
        }
        Desktop.getDesktop().browse(URI.create(targetUrl))
    }

    fun stop() {
        server?.stop(0)
        server = null
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
        fun tryFindUiAssetsDir(repoRoot: Path = Paths.get(".").toAbsolutePath()): Path? {
            val submoduleDist = repoRoot.resolve("third_party/perfetto/out/ui/dist")
            if (Files.isDirectory(submoduleDist) && submoduleDist.resolve("index.html").toFile().exists()) {
                return submoduleDist
            }
            val downloaded = Paths.get(
                System.getProperty("user.home"),
                ".android-performance-studio", "tools", "perfetto", "ui",
            )
            if (Files.isDirectory(downloaded) && downloaded.resolve("index.html").toFile().exists()) {
                return downloaded
            }
            return null
        }
    }
}
