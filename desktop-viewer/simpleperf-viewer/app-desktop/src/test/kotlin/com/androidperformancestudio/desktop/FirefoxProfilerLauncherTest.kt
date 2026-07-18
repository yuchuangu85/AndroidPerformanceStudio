package com.androidperformancestudio.desktop

import java.net.HttpURLConnection
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirefoxProfilerLauncherTest {
    @Test
    fun `profiler url uses the official from-url flame graph route`() {
        val url = firefoxProfilerUrl(java.net.URI.create("http://127.0.0.1:43210/perf_data.json.gz"))

        assertEquals("https", url.scheme)
        assertEquals("profiler.firefox.com", url.host)
        assertTrue(url.rawPath.startsWith("/from-url/http%3A%2F%2F127.0.0.1%3A43210%2Fperf_data.json.gz/"))
        assertTrue(url.path.endsWith("/flame-graph/"))
    }

    @Test
    fun `loopback server transfers the generated gzip privately with cors`() {
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3, 4)
        val profile = Files.createTempFile("firefox-profiler-launcher-", ".json.gz")
        Files.write(profile, bytes)

        val connection = FirefoxProfileLoopbackServer(profile).start().toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000

        assertEquals(200, connection.responseCode)
        assertEquals("*", connection.getHeaderField("Access-Control-Allow-Origin"))
        assertEquals("no-store", connection.getHeaderField("Cache-Control"))
        assertContentEquals(bytes, connection.inputStream.use { it.readBytes() })
    }
}
