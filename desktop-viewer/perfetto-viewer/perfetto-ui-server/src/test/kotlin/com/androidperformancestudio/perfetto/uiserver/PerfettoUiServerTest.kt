package com.androidperformancestudio.perfetto.uiserver

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PerfettoUiServerTest {
    @Test
    fun `starting for another trace reuses the running server`() {
        val uiServer = PerfettoUiServer(port = 0)
        try {
            uiServer.start(null)
            val firstServer = uiServer.runningServer()

            uiServer.start(null)

            assertSame(firstServer, uiServer.runningServer())
        } finally {
            uiServer.stop()
        }
    }

    @Test
    fun `finds Perfetto UI inside packaged Compose resources`() {
        val resources = Files.createTempDirectory("perfetto-packaged-resources")
        val ui = Files.createDirectories(resources.resolve("perfetto-ui"))
        Files.writeString(ui.resolve("index.html"), "<!doctype html>")

        val located =
            PerfettoUiServer.tryFindUiAssetsDir(
                repoRoot = Files.createTempDirectory("unrelated-working-directory"),
                configuredPath = null,
                environmentPath = null,
                applicationResourcesPath = resources.toString(),
            )

        assertEquals(ui, located)
    }

    @Test
    fun `configured UI path takes precedence over repository discovery`() {
        val configured = Files.createTempDirectory("perfetto-configured-ui")
        Files.writeString(configured.resolve("index.html"), "<!doctype html>")

        val located =
            PerfettoUiServer.tryFindUiAssetsDir(
                repoRoot = Files.createTempDirectory("unrelated-working-directory"),
                configuredPath = configured.toString(),
                environmentPath = null,
                applicationResourcesPath = null,
            )

        assertEquals(configured, located)
    }
}

private fun PerfettoUiServer.runningServer(): Any? =
    javaClass.getDeclaredField("server").run {
        isAccessible = true
        get(this@runningServer)
    }
