package com.androidperformancestudio.desktop

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirefoxProfilerLauncherTest {
    @Test
    fun `profiler route stays on the local site and loads a same-origin profile`() {
        val origin = URI.create("http://127.0.0.1:43210/")
        val url = firefoxProfilerUrl(origin, origin.resolve("perf_data.json.gz"))

        assertEquals("http", url.scheme)
        assertEquals("127.0.0.1", url.host)
        assertEquals(43210, url.port)
        assertTrue(url.rawPath.startsWith("/from-url/http%3A%2F%2F127.0.0.1%3A43210%2Fperf_data.json.gz/"))
        assertTrue(url.path.endsWith("/flame-graph/"))
    }

    @Test
    fun `local server hosts the profiler application and generated profile`() {
        val site = Files.createTempDirectory("firefox-profiler-site-")
        site.resolve("index.html").writeText("<html>local profiler</html>")
        site.resolve("index-test.js").writeText("window.localProfiler = true")
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3, 4)
        val profile = Files.createTempFile("firefox-profiler-profile-", ".json.gz")
        Files.write(profile, bytes)

        FirefoxProfilerLocalServer(site, profile).use { server ->
            val page = server.start()
            val origin = URI.create("${page.scheme}://${page.authority}/")

            page.withConnection { connection ->
                assertEquals(200, connection.responseCode)
                assertEquals("no-cache", connection.getHeaderField("Cache-Control"))
                assertEquals(
                    "<html>local profiler</html>",
                    connection.inputStream.bufferedReader().use { reader -> reader.readText() },
                )
            }
            origin.resolve("index-test.js").withConnection { connection ->
                assertEquals("text/javascript; charset=utf-8", connection.contentType)
                assertEquals(
                    "window.localProfiler = true",
                    connection.inputStream.bufferedReader().use { reader -> reader.readText() },
                )
            }
            origin.resolve("perf_data.json.gz").withConnection { connection ->
                assertEquals(200, connection.responseCode)
                assertEquals("application/gzip", connection.contentType)
                assertEquals("no-store", connection.getHeaderField("Cache-Control"))
                assertContentEquals(bytes, connection.inputStream.use { it.readBytes() })
            }
        }
    }

    @Test
    fun `site locator discovers the pinned profiler build from a nested working directory`() {
        val repository = Files.createTempDirectory("firefox-profiler-repository-")
        val site = repository.resolve("third_party/firefox-profiler/dist").createDirectories()
        site.resolve("index.html").writeText("local")
        val nested = repository.resolve("desktop-viewer/simpleperf-viewer").createDirectories()

        val located = FirefoxProfilerSiteLocator(workingDirectory = nested).locate()

        assertEquals(site, located)
    }

    @Test
    fun `site locator uses packaged Compose application resources`() {
        val site = Files.createTempDirectory("firefox-profiler-packaged-site-")
        site.resolve("index.html").writeText("local")

        val located =
            FirefoxProfilerSiteLocator(
                configuredPath = null,
                environmentPath = null,
                applicationResourcesPath = site.toString(),
                workingDirectory = Files.createTempDirectory("firefox-profiler-unrelated-"),
            ).locate()

        assertEquals(site, located)
    }
}

private inline fun URI.withConnection(block: (HttpURLConnection) -> Unit) {
    val connection = toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 2_000
    connection.readTimeout = 2_000
    try {
        block(connection)
    } finally {
        connection.disconnect()
    }
}
