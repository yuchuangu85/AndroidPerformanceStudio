package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DiagnosticMessageCopySourceTest {
    @Test
    fun `settings failures and source workspace reasons use copyable feedback`() {
        val settings = source("DesktopAppSettingsPage.kt")
        listOf("source_models_load_failed", "source_ai_save_failed").forEach { resource ->
            assertCopyableResource(settings, resource)
        }
        assertTrue(
            Regex(
                """CopyableText\(\s*localizedStringResource\(\s*if\s*\(connection\s*!=\s*AiProviderConnection\.CLAUDE\)\s*Res\.string\.source_ai_key_required\s*else\s*Res\.string\.source_ai_credential_required\b""",
            ).containsMatchIn(settings),
            "Both connection-specific missing-credential messages must retain the full-message copy entry",
        )
        val workspaces = source("SourceWorkspacesPage.kt")
        listOf("source_operation_failed", "source_content_stale", "source_select_indexed_workspace").forEach { resource ->
            assertCopyableResource(workspaces, resource)
        }
        assertTrue(Regex("""workspace\.localizedMessage\(language\)\?\.let\s*\{\s*CopyableText\(""").containsMatchIn(workspaces))
        assertTrue(Regex("""CopyableText\(\s*reasons\.joinToString""").containsMatchIn(workspaces))
    }

    @Test
    fun `home diagnostic notices are copyable without changing navigation labels`() {
        val home = source("AppHomePage.kt")
        assertCopyableResource(home, "some_recent_indexes_unavailable")
        assertCopyableResource(home, "no_connected_devices")
    }

    private fun assertCopyableResource(
        source: String,
        resource: String,
    ) {
        val pattern = Regex("""CopyableText\(\s*(?:text\s*=\s*)?localizedStringResource\(\s*Res\.string\.$resource\b""")
        assertTrue(pattern.containsMatchIn(source), "$resource must retain the full-message copy entry")
    }

    private fun source(name: String): String = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop", name))
}
