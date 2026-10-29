package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Resolution is what makes a development run find the bundle produced by
 * :layout-inspector:compose-agent-build without any launch argument, so the candidate order and the
 * explicit override both have to stay pinned:
 */
class ComposeAgentBundleResolutionTest {
    @Test
    fun `an explicit configuration resolves against the working directory`() {
        val workingDirectory = tempDirectory()

        val resolved = resolveComposeAgentBundle(
            configured = "custom-bundle",
            workingDirectory = workingDirectory,
            applicationResourcesPath = null,
            isDirectory = { false },
        )

        assertEquals(workingDirectory.resolve("custom-bundle"), resolved)
    }

    @Test
    fun `an absolute configuration is used as it is`() {
        val absolute = tempDirectory().resolve("bundle")

        val resolved = resolveComposeAgentBundle(
            configured = absolute.toString(),
            workingDirectory = tempDirectory(),
            applicationResourcesPath = null,
            isDirectory = { false },
        )

        assertEquals(absolute, resolved)
    }

    @Test
    fun `a blank configuration falls through to the candidates`() {
        val workingDirectory = tempDirectory()
        Files.createDirectories(workingDirectory.resolve("compose-agent-bundle"))

        assertEquals(
            workingDirectory.resolve("compose-agent-bundle"),
            resolveComposeAgentBundle(
                configured = "   ",
                workingDirectory = workingDirectory,
                applicationResourcesPath = null,
            ),
        )
    }

    @Test
    fun `a bundle next to the application wins`() {
        val workingDirectory = tempDirectory()
        Files.createDirectories(workingDirectory.resolve("compose-agent-bundle"))
        Files.createDirectories(developmentBundle(workingDirectory))

        assertEquals(
            workingDirectory.resolve("compose-agent-bundle"),
            resolveComposeAgentBundle(
                configured = null,
                workingDirectory = workingDirectory,
                applicationResourcesPath = null,
            ),
        )
    }

    @Test
    fun `the development layout is found when nothing is shipped`() {
        val workingDirectory = tempDirectory()
        val development = developmentBundle(workingDirectory)
        Files.createDirectories(development)

        assertEquals(
            development,
            resolveComposeAgentBundle(
                configured = null,
                workingDirectory = workingDirectory,
                applicationResourcesPath = null,
            ),
        )
    }

    @Test
    fun `a missing bundle still resolves to the default location`() {
        val workingDirectory = tempDirectory()

        assertEquals(
            workingDirectory.resolve("compose-agent-bundle"),
            resolveComposeAgentBundle(
                configured = null,
                workingDirectory = workingDirectory,
                applicationResourcesPath = null,
            ),
        )
    }

    @Test
    fun `packaged resources win over working directory and development bundles`() {
        val workingDirectory = tempDirectory()
        val resources = Files.createTempDirectory("aps-app-resources")
        val packaged = Files.createDirectories(resources.resolve("compose-agent-bundle"))
        Files.createDirectories(workingDirectory.resolve("compose-agent-bundle"))
        Files.createDirectories(developmentBundle(workingDirectory))

        assertEquals(
            packaged,
            resolveComposeAgentBundle(
                configured = null,
                workingDirectory = workingDirectory,
                applicationResourcesPath = resources,
            ),
        )
    }

    @Test
    fun `packaged resources are found when the application starts from the filesystem root`() {
        val resources = Files.createTempDirectory("aps-app-resources")
        val packaged = Files.createDirectories(resources.resolve("compose-agent-bundle"))

        assertEquals(
            packaged,
            resolveComposeAgentBundle(
                configured = null,
                workingDirectory = resources.root,
                applicationResourcesPath = resources,
            ),
        )
    }

    @Test
    fun `an explicit missing bundle does not silently use packaged resources`() {
        val workingDirectory = tempDirectory()
        val resources = Files.createTempDirectory("aps-app-resources")
        Files.createDirectories(resources.resolve("compose-agent-bundle"))

        assertEquals(
            workingDirectory.resolve("configured-but-missing"),
            resolveComposeAgentBundle(
                configured = "configured-but-missing",
                workingDirectory = workingDirectory,
                applicationResourcesPath = resources,
            ),
        )
    }

    @Test
    fun `a missing packaged bundle reports the expected application resource path`() {
        val workingDirectory = tempDirectory()
        val resources = Files.createTempDirectory("aps-app-resources")

        assertEquals(
            resources.resolve("compose-agent-bundle"),
            resolveComposeAgentBundle(
                configured = null,
                workingDirectory = workingDirectory,
                applicationResourcesPath = resources,
            ),
        )
    }

    @Test
    fun `the development bundle remains available when packaged resources have no bundle`() {
        val workingDirectory = tempDirectory()
        val resources = Files.createTempDirectory("aps-app-resources")
        val development = Files.createDirectories(developmentBundle(workingDirectory))

        assertEquals(
            development,
            resolveComposeAgentBundle(
                configured = null,
                workingDirectory = workingDirectory,
                applicationResourcesPath = resources,
            ),
        )
    }

    @Test
    fun `default packaged resources are read from the Compose Desktop launcher property`() {
        val resources = Files.createTempDirectory("aps-app-resources")
        val packaged = Files.createDirectories(resources.resolve("compose-agent-bundle"))
        val property = "compose.application.resources.dir"
        val previous = System.getProperty(property)
        System.setProperty(property, resources.toString())
        try {
            assertEquals(
                packaged,
                resolveComposeAgentBundle(configured = null, workingDirectory = resources.root),
            )
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private fun developmentBundle(workingDirectory: Path): Path =
        workingDirectory
            .resolve("../layout-inspector/compose-agent-build/build/compose-agent-bundle")
            .normalize()

    private fun tempDirectory(): Path =
        Files.createTempDirectory("aps-bundle").resolve("desktop-app").also(Files::createDirectories)
}
