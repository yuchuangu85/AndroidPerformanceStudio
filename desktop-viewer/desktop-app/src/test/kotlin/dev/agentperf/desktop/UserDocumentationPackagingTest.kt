package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserDocumentationPackagingTest {
    private val repositoryRoot = Path.of("../..")
    private val english = repositoryRoot.resolve("docs-user")
    private val chinese = repositoryRoot.resolve("docs-user-zh")

    @Test
    fun `English and Chinese documentation trees are complete`() {
        val englishMarkdown = markdownNames(english)
        val chineseMarkdown = markdownNames(chinese)

        assertEquals(englishMarkdown, chineseMarkdown)
        assertTrue(englishMarkdown.isNotEmpty())
        assertTrue(Files.isRegularFile(chinese.resolve("index.html")))
        assertTrue(Files.isRegularFile(chinese.resolve("images/screenshot-2022-04-25.png")))
        assertFalse(Files.exists(chinese.resolve(".DS_Store")))
        chineseMarkdown.forEach { name ->
            val content = Files.readString(chinese.resolve(name))
            assertFalse(content.contains("ZXQPROTECTED"), "Unresolved translation placeholder in $name")
            assertTrue(content.any { it.code in HAN_CHARACTER_RANGE }, "Missing Chinese translation in $name")
        }
    }

    @Test
    fun `desktop package copies both documentation languages`() {
        val buildScript = Files.readString(Path.of("build.gradle.kts"))

        assertTrue(buildScript.contains("userDocumentationEnglish"))
        assertTrue(buildScript.contains("userDocumentationChinese"))
        assertTrue(buildScript.contains("into(\"docs-user\")"))
        assertTrue(buildScript.contains("into(\"docs-user-zh\")"))
    }

    @Test
    fun `Docsify loads each guide from its mounted directory`() {
        val englishConfig = Files.readString(english.resolve("js/init.js"))
        val chineseConfig = Files.readString(chinese.resolve("js/init.js"))
        val englishSidebar = Files.readString(english.resolve("_sidebar.md"))
        val chineseSidebar = Files.readString(chinese.resolve("_sidebar.md"))
        val englishNavbar = Files.readString(english.resolve("_navbar.md"))
        val chineseNavbar = Files.readString(chinese.resolve("_navbar.md"))

        assertTrue(englishConfig.contains("basePath: '/docs-user/'"))
        assertTrue(chineseConfig.contains("basePath: '/docs-user-zh/'"))
        assertTrue(englishSidebar.startsWith("- [User Guide](/)"))
        assertTrue(chineseSidebar.startsWith("- [用户指南](/)"))
        assertTrue(englishNavbar.contains("href=\"/docs-user-zh/\" target=\"_self\""))
        assertTrue(chineseNavbar.contains("href=\"/docs-user/\" target=\"_self\""))
    }

    private fun markdownNames(root: Path): Set<String> =
        Files
            .list(root)
            .use { paths ->
                paths
                    .filter { path -> path.extension == "md" }
                    .map(Path::getFileName)
                    .map(Path::name)
                    .toList()
                    .toSet()
            }

    private companion object {
        val HAN_CHARACTER_RANGE = '\u4E00'.code..'\u9FFF'.code
    }
}
