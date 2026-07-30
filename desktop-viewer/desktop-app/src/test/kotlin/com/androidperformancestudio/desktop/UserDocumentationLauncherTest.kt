package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserDocumentationLauncherTest {
    @Test
    fun `launcher serves and opens both documentation languages on one local origin`() {
        val root = documentationRoot()
        val opened = mutableListOf<URI>()

        UserDocumentationLauncher(opened::add) { root }.use { launcher ->
            launcher.open(UiLanguage.ENGLISH)
            launcher.open(UiLanguage.SIMPLIFIED_CHINESE)

            assertEquals("/docs-user/", opened[0].path)
            assertEquals("/docs-user-zh/", opened[1].path)
            assertEquals(opened[0].authority, opened[1].authority)
            assertEquals("English guide", get(opened[0]))
            assertEquals("中文指南", get(opened[1]))
            assertEquals("Docsify runtime", get(opened[0].resolve("js/docsify_v4.13.1+.min.js")))
        }
    }

    @Test
    fun `locator accepts packaged resources and repository discovery`() {
        val root = documentationRoot()
        val nestedWorkingDirectory = root.resolve("workspace/nested").createDirectories()

        assertEquals(
            root,
            UserDocumentationSiteLocator(
                applicationResourcesPath = root.toString(),
                workingDirectory = nestedWorkingDirectory,
            ).locate(),
        )

        val repository = Files.createTempDirectory("user-docs-repository-")
        createDocumentationSites(repository)
        assertEquals(
            repository,
            UserDocumentationSiteLocator(
                applicationResourcesPath = null,
                workingDirectory = repository.resolve("desktop-viewer/desktop-app").createDirectories(),
            ).locate(),
        )
    }

    private fun documentationRoot() =
        Files.createTempDirectory("user-documentation-").also(::createDocumentationSites)

    private fun createDocumentationSites(root: java.nio.file.Path) {
        root.resolve("docs-user").createDirectories().resolve("index.html").writeText("English guide")
        root.resolve("docs-user-zh").createDirectories().resolve("index.html").writeText("中文指南")
        root
            .resolve("docs-user/js")
            .createDirectories()
            .resolve("docsify_v4.13.1+.min.js")
            .writeText("Docsify runtime")
    }

    private fun get(uri: URI): String {
        val response =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
        assertTrue(response.statusCode() in 200..299)
        return response.body()
    }
}
