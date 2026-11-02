package com.androidperformancestudio.aiplatform.providers

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalProviderConfigResolverTest {
    @Test
    fun `metadata selects only allowlisted fields and never opens secret auth files`() =
        withHome { home ->
            Files.createDirectories(home.resolve(".codex"))
            Files.writeString(
                home.resolve(".codex/config.toml"),
                "model = \"test-model\"\nprofile = \"analysis\"\napi_key = \"secret-not-metadata\"\n",
            )
            // A directory in place of auth.json proves resolver does not read/parse this secret path.
            Files.createDirectory(home.resolve(".codex/auth.json"))
            val metadata = LocalProviderConfigResolver(home, setOf("OPENAI_API_KEY")).resolve("codex")
            assertEquals(LocalConfigState.AVAILABLE, metadata.configState)
            assertEquals("test-model", metadata.model)
            assertEquals("analysis", metadata.profile)
            assertTrue(metadata.credentialReferences.first().resolvable)
            assertFalse(metadata.toString().contains("secret-not-metadata"))
            assertFalse(metadata.credentialReferences.last().resolvable)
        }

    @Test
    fun `Claude configuration exposes model but not env or helpers`() =
        withHome { home ->
            Files.createDirectories(home.resolve(".claude"))
            Files.writeString(
                home.resolve(".claude/settings.json"),
                """{"model":"test-model","apiKeyHelper":"run-secret-helper","env":{"ANTHROPIC_API_KEY":"secret"}}""",
            )
            val metadata = LocalProviderConfigResolver(home, setOf("ANTHROPIC_API_KEY")).resolve("claude")
            assertEquals("test-model", metadata.model)
            assertEquals(LocalConfigState.AVAILABLE, metadata.configState)
            assertFalse(metadata.toString().contains("secret"))
            assertFalse(metadata.toString().contains("helper"))
        }

    @Test
    fun `absent config preserves unresolved credential reference without assuming login`() =
        withHome { home ->
            val metadata = LocalProviderConfigResolver(home, emptySet()).resolve("claude")
            assertEquals(LocalConfigState.ABSENT, metadata.configState)
            assertTrue(metadata.credentialReferences.none { it.resolvable })
        }

    @Test
    fun `Claude token availability remains a reference without reading its value`() =
        withHome { home ->
            val metadata = LocalProviderConfigResolver(home, setOf("ANTHROPIC_AUTH_TOKEN")).resolve("claude")
            assertEquals(listOf("anthropic-auth-token"), metadata.credentialReferences.filter { it.resolvable }.map { it.key })
        }

    @Test
    fun `malformed oversized and symlinked configurations fail closed`() =
        withHome { home ->
            Files.createDirectories(home.resolve(".claude"))
            val config = home.resolve(".claude/settings.json")
            Files.writeString(config, "{broken")
            val resolver = LocalProviderConfigResolver(home, emptySet())
            assertEquals(LocalConfigState.MALFORMED, resolver.resolve("claude").configState)
            Files.writeString(config, " ".repeat(65_537))
            assertEquals(LocalConfigState.MALFORMED, resolver.resolve("claude").configState)
            Files.delete(config)
            val outside = Files.writeString(home.resolve("outside.json"), "{}")
            Files.createSymbolicLink(config, outside)
            assertEquals(LocalConfigState.PERMISSION_DENIED, resolver.resolve("claude").configState)
        }

    @Test
    fun `unknown providers are explicit unsupported state`() =
        withHome { home ->
            assertEquals(LocalConfigState.UNSUPPORTED, LocalProviderConfigResolver(home, emptySet()).resolve("unknown").configState)
        }

    private fun withHome(block: (java.nio.file.Path) -> Unit) {
        val home = Files.createTempDirectory("aps-config-test-").toRealPath()
        try {
            block(home)
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
