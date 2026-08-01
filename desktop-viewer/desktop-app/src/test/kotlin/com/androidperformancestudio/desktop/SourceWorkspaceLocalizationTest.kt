package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceWorkspaceLocalizationTest {
    @Test
    fun `source workspace UI uses localized resources with Chinese parity`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/desktop/SourceWorkspacesPage.kt"),
            )
        val home = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AppHomePage.kt"))
        val english = Files.readString(Path.of("src/main/composeResources/values/strings.xml"))
        val chinese = Files.readString(Path.of("src/main/composeResources/values-zh/strings.xml"))
        val resourceName = Regex("""<string name="(source_[^"]+)">""")
        val englishNames = resourceName.findAll(english).map { it.groupValues[1] }.toSet()
        val chineseNames = resourceName.findAll(chinese).map { it.groupValues[1] }.toSet()

        assertEquals(englishNames, chineseNames)
        assertTrue(englishNames.size >= 50)
        assertTrue(english.contains("<string name=\"home\">Home</string>"))
        assertTrue(chinese.contains("<string name=\"home\">首页</string>"))
        assertTrue(source.contains("localizedStringResource(Res.string.source_workspaces, language)"))
        assertTrue(source.contains("workspace.phase.localizedLabel(language)"))
        assertTrue(source.contains("it.localizedResolutionReason(language)"))
        assertTrue(home.contains("localizedStringResource(Res.string.source_home_subtitle, language)"))
        assertTrue(home.contains("localizedStringResource(Res.string.source_home_description, language)"))
        assertFalse(home.contains("if (language == UiLanguage.SIMPLIFIED_CHINESE) \"源码工作区\""))
        assertFalse(source.contains("private fun label("))
        assertFalse(source.contains("""Text("Sync")"""))
        assertFalse(source.contains("""Text("Remove")"""))
    }
}
