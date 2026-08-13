package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.winscope.model.WinscopeSession
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpstreamWinscopeServerTest {
    @Test
    fun `serves declared assets and tokenized evidence without caching`() {
        val assets = Files.createTempDirectory("upstream-winscope-assets")
        Files.writeString(assets.resolve("index.html"), "<h1>Winscope</h1>")
        Files.createDirectories(assets.resolve("js"))
        Files.writeString(assets.resolve("js/app.js"), "window.winscope = true")
        val evidence = Files.createTempFile("winscope-evidence", ".zip")
        val expected = "evidence".toByteArray()
        Files.write(evidence, expected)
        val opened = AtomicReference<URI>()
        val server = UpstreamWinscopeServer(browserOpen = opened::set)
        try {
            assertIs<StudioResult.Success<Unit>>(server.start(assets))
            val url = assertIs<StudioResult.Success<URI>>(server.openEvidence(evidence)).value
            assertEquals(url, opened.get())

            val index = get(url)
            assertEquals(200, index.statusCode())
            assertTrue(index.body().decodeToString().contains("Winscope"))

            val asset = get(url.resolve("/js/app.js"))
            assertEquals(200, asset.statusCode())
            assertEquals("nosniff", asset.headers().firstValue("X-Content-Type-Options").orElse(null))

            val token = checkNotNull(url.query).substringAfter("session=")
            assertEquals(404, get(url.resolve("/evidence?session=wrong")).statusCode())
            val response = get(url.resolve("/evidence?session=$token"))
            assertEquals(200, response.statusCode())
            assertContentEquals(expected, response.body())
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
            assertEquals(404, get(url.resolve("/%2e%2e/outside.txt")).statusCode())
        } finally {
            server.close()
        }
    }

    @Test
    fun `expires an evidence token`() {
        val assets = Files.createTempDirectory("upstream-winscope-assets")
        Files.writeString(assets.resolve("index.html"), "Winscope")
        val evidence = Files.createTempFile("winscope-evidence", ".zip")
        Files.writeString(evidence, "evidence")
        val server = UpstreamWinscopeServer(evidenceLifetime = Duration.ofMillis(30), browserOpen = {})
        try {
            assertIs<StudioResult.Success<Unit>>(server.start(assets))
            val url = assertIs<StudioResult.Success<URI>>(server.openEvidence(evidence)).value
            val evidenceUrl = url.resolve("/evidence?${url.query}")
            assertEquals(200, get(evidenceUrl).statusCode())

            val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
            while (get(evidenceUrl).statusCode() != 404 && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(404, get(evidenceUrl).statusCode())
        } finally {
            server.close()
        }
    }

    @Test
    fun `finds viewer assets in packaged Compose resources`() {
        val resources = Files.createTempDirectory("packaged-resources")
        val viewer = Files.createDirectories(resources.resolve("winscope-ui"))
        Files.writeString(viewer.resolve("index.html"), "Winscope")

        val found =
            UpstreamWinscopeServer.tryFindAssetsDirectory(
                repoRoot = Files.createTempDirectory("unrelated-repository"),
                configuredPath = null,
                environmentPath = null,
                applicationResourcesPath = resources.toString(),
            )

        assertEquals(viewer, found)
    }

    @Test
    fun `launcher exports the current session before opening the browser`() {
        val assets = Files.createTempDirectory("upstream-winscope-assets")
        Files.writeString(assets.resolve("index.html"), "Winscope")
        val trace = Files.createTempFile("winscope", ".pftrace")
        Files.write(trace, byteArrayOf(0x0a, 0x00))
        val opened = AtomicReference<URI>()
        val server = UpstreamWinscopeServer(browserOpen = opened::set)
        val launcher = UpstreamWinscopeLauncher(server = server, assetsDirectory = assets)
        try {
            assertIs<StudioResult.Success<Unit>>(
                launcher.open(
                    WinscopeSession("session", trace, capturedAt = Instant.EPOCH),
                    emptyList(),
                ),
            )
            val uri = checkNotNull(opened.get())
            val response = get(uri.resolve("/evidence?${uri.query}"))
            assertEquals(200, response.statusCode())
            assertContentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()), response.body().copyOf(2))
            launcher.invalidate()
            assertEquals(404, get(uri.resolve("/evidence?${uri.query}")).statusCode())
        } finally {
            launcher.close()
        }
    }

    private fun get(uri: URI): HttpResponse<ByteArray> =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
}
