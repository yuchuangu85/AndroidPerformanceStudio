package com.androidperformancestudio.ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAiConfigurationImporterTest {
    @Test
    fun `empty selected sources are not found`() = withHome { home ->
        for (source in LocalAiConfigSource.entries) {
            assertFailure(LocalAiImportFailure.NOT_FOUND, read(home, source))
        }
    }

    @Test
    fun `codex profile selects quoted provider and preserves gateway path prefix`() = withHome { home ->
        write(home, ".codex/config.toml", """
            model = "unselected-model"
            model_provider = "unselected"
            profile = 'work'
            [profiles."work"]
            model = "profile-model" # comment outside the string
            model_provider = 'gateway.with.dot'
            [model_providers.unselected]
            base_url = "https://unselected.test/v1"
            experimental_bearer_token = "unselected-secret"
            ["model_providers".'gateway.with.dot']
            "base_url" = "https://gateway.test/proxy/v1/"
            wire_api = "responses"
            env_key = "GATEWAY_KEY"
        """.trimIndent())
        val config = success(
            read(home, env = mapOf("GATEWAY_KEY" to "gateway-secret", "OPENAI_API_KEY" to "wrong-key")),
        )
        assertEquals("profile-model", config.model)
        assertEquals("https://gateway.test/proxy/v1/responses", config.endpoint)
        assertEquals("gateway-secret", config.apiKey)
        assertEquals(ImportedCredentialKind.API_KEY, config.credentialKind)
    }

    @Test
    fun `codex custom provider never falls back to unrelated official credentials`() = withHome { home ->
        write(home, ".codex/auth.json", """{"OPENAI_API_KEY":"wrong-file-key"}""")
        for (declaration in listOf("env_key = 'MISSING'", "")) {
            codexGateway(home, declaration)
            val config = success(read(home, env = mapOf("OPENAI_API_KEY" to "wrong-process-key")))
            assertNull(config.apiKey)
            assertEquals("https://gateway.test/proxy/v1/responses", config.endpoint)
        }
    }

    @Test
    fun `codex requires openai auth ignores env key and exports only official auth key`() = withHome { home ->
        codexGateway(home, "requires_openai_auth = true\nenv_key = 'GATEWAY_KEY'")
        write(
            home, ".codex/auth.json",
            """{"OPENAI_API_KEY":"official-file-key","tokens":{"access_token":"oauth-secret"}}""",
        )
        val config = success(read(home, env = mapOf("GATEWAY_KEY" to "ignored-key")))
        assertEquals("official-file-key", config.apiKey)
        assertEquals(ImportedCredentialKind.API_KEY, config.credentialKind)
        assertEquals(
            "official-process-key",
            success(
                read(home, env = mapOf("GATEWAY_KEY" to "ignored-key", "OPENAI_API_KEY" to "official-process-key")),
            ).apiKey,
        )
        write(home, ".codex/auth.json", """{"tokens":{"access_token":"oauth-secret","id_token":"id-secret"}}""")
        assertNull(success(read(home, env = mapOf("GATEWAY_KEY" to "ignored-key"))).apiKey)
    }

    @Test
    fun `codex configured bearer token remains importable with openai auth enabled`() = withHome { home ->
        codexGateway(
            home,
            "requires_openai_auth = true\nenv_key = 'IGNORED_KEY'\nexperimental_bearer_token = 'provider-token'",
            base = "http://127.0.0.1:12345/v1",
        )
        write(home, ".codex/auth.json", "malformed auth must not be inspected")
        val config = success(
            read(home, env = mapOf("OPENAI_API_KEY" to "wrong-official-key", "IGNORED_KEY" to "wrong-env-key")),
        )
        assertEquals("provider-token", config.apiKey)
        assertEquals(ImportedCredentialKind.BEARER_TOKEN, config.credentialKind)
        assertEquals("http://127.0.0.1:12345/v1/responses", config.endpoint)
        assertFalse(config.toString().contains("provider-token"))
    }

    @Test
    fun `invalid configured bearer tokens cannot fall back to official credentials`() = withHome { home ->
        for (token in listOf("", "token with spaces")) {
            codexGateway(home, "requires_openai_auth = true\nexperimental_bearer_token = '$token'")
            assertFailure(LocalAiImportFailure.MALFORMED, read(home, env = mapOf("OPENAI_API_KEY" to "wrong-fallback")))
        }
    }

    @Test
    fun `codex explicit credential declarations conflict even when environment is unresolved`() = withHome { home ->
        val declarations = listOf(
            "env_key = 'KEY'\nexperimental_bearer_token = 'bearer-secret'",
            "env_key = 'KEY'\nhttp_headers = { Authorization = 'Bearer header-secret' }",
            "env_http_headers = { Authorization = 'HEADER' }\n" +
                "http_headers = { Authorization = 'Bearer header-secret' }",
            "requires_openai_auth = true\nhttp_headers = { Authorization = 'Bearer header-secret' }",
            "requires_openai_auth = true\nenv_http_headers = { Authorization = 'AUTH_HEADER' }",
        )
        for (declaration in declarations) {
            codexGateway(home, declaration)
            assertFailure(LocalAiImportFailure.UNSUPPORTED_CONFIG, read(home))
        }
    }

    @Test
    fun `codex bearer headers strip prefix and ignore unknown headers`() = withHome { home ->
        for (declaration in listOf(
            "http_headers = { 'Authorization' = 'bEaReR header-secret', 'X-Unknown' = 'not-a-key' }",
            "[model_providers.gateway.http_headers]\nAuthorization = 'Bearer header-secret'",
            "http_headers.Authorization = 'Bearer header-secret'",
            "env_http_headers = { Authorization = 'AUTH_HEADER' }",
            "experimental_bearer_token = 'header-secret'",
        )) {
            codexGateway(home, declaration)
            val config = success(read(home, env = mapOf("AUTH_HEADER" to "Bearer header-secret")))
            assertEquals("header-secret", config.apiKey)
            assertEquals(ImportedCredentialKind.BEARER_TOKEN, config.credentialKind)
        }
        codexGateway(home, "http_headers = { 'X-Api-Key' = 'not-imported' }")
        assertNull(success(read(home)).apiKey)
        codexGateway(home, "http_headers = { Authorization = 'Basic not-supported' }")
        assertFailure(LocalAiImportFailure.UNSUPPORTED_AUTH, read(home))
    }

    @Test
    fun `codex default env only and auth json use full responses URL`() = withHome { home ->
        assertEquals(
            "https://api.openai.com/v1/responses",
            success(read(home, env = mapOf("OPENAI_API_KEY" to "process-key"))).endpoint,
        )
        write(home, ".codex/auth.json", """{"OPENAI_API_KEY":"file-key"}""")
        assertEquals("file-key", success(read(home)).apiKey)
        assertEquals("process-key", success(read(home, env = mapOf("OPENAI_API_KEY" to "process-key"))).apiKey)
        for (base in listOf("https://gateway.test/proxy/v1/responses", "https://gateway.test/proxy/v1/responses/")) {
            val config = success(read(home, env = mapOf("OPENAI_API_KEY" to "process-key", "OPENAI_BASE_URL" to base)))
            assertEquals("https://gateway.test/proxy/v1/responses", config.endpoint)
            assertEquals("process-key", config.apiKey)
        }
        assertNull(success(read(home, env = mapOf("OPENAI_BASE_URL" to "https://gateway.test/v1"))).apiKey)
    }

    @Test
    fun `codex OAuth only is unsupported unless safe connection metadata exists`() = withHome { home ->
        write(home, ".codex/auth.json", """{"tokens":{"access_token":"oauth-secret","id_token":"id-secret"}}""")
        assertFailure(LocalAiImportFailure.UNSUPPORTED_AUTH, read(home))
        write(home, ".codex/config.toml", "model = 'chosen-model'")
        val config = success(read(home))
        assertNull(config.apiKey)
        assertEquals("chosen-model", config.model)
        assertFalse(config.toString().contains("oauth-secret"))
    }

    @Test
    fun `codex rejects unsupported wire api profiles and missing custom bases`() = withHome { home ->
        for (text in listOf(
            "model_provider = 'missing'",
            "profile = 'missing'",
            "model_provider = 'gateway'\n[model_providers.gateway]\nname = 'gateway'",
            "[model_providers.openai]\nwire_api = 'chat'",
            "[model_providers.openai]\nwire_api = 'chat_completions'",
        )) {
            write(home, ".codex/config.toml", text)
            assertFailure(
                LocalAiImportFailure.UNSUPPORTED_CONFIG,
                read(home, env = mapOf("OPENAI_API_KEY" to "no-fallback")),
            )
        }
    }

    @Test
    fun `claude settings connection group overrides inherited environment without mixing`() = withHome { home ->
        write(home, ".claude/settings.json", """
            {"model":"top-model","env":{
                "ANTHROPIC_API_KEY":"settings-key",
                "ANTHROPIC_BASE_URL":"https://settings.test/proxy",
                "ANTHROPIC_MODEL":"settings-model"
            }}
        """.trimIndent())
        val inherited = mapOf(
            "ANTHROPIC_API_KEY" to "process-key", "ANTHROPIC_AUTH_TOKEN" to "process-token",
            "ANTHROPIC_BASE_URL" to "https://process.test", "ANTHROPIC_MODEL" to "process-model",
        )
        val config = success(read(home, LocalAiConfigSource.CLAUDE, inherited))
        assertEquals("settings-key", config.apiKey)
        assertEquals("https://settings.test/proxy", config.endpoint)
        assertEquals("settings-model", config.model)
        assertEquals(ImportedCredentialKind.API_KEY, config.credentialKind)
        write(home, ".claude/settings.json", """{"env":{"ANTHROPIC_BASE_URL":"https://settings.test"}}""")
        assertNull(success(read(home, LocalAiConfigSource.CLAUDE, inherited)).apiKey)
        write(home, ".claude/settings.json", """{"env":{"ANTHROPIC_API_KEY":"settings-key"}}""")
        assertEquals("https://api.anthropic.com", success(read(home, LocalAiConfigSource.CLAUDE, inherited)).endpoint)
    }

    @Test
    fun `claude token wins over api key and remains bearer in either connection group`() = withHome { home ->
        val env = mapOf("ANTHROPIC_API_KEY" to "process-key", "ANTHROPIC_AUTH_TOKEN" to "process-token")
        val process = success(read(home, LocalAiConfigSource.CLAUDE, env))
        assertEquals("process-token", process.apiKey)
        assertEquals(ImportedCredentialKind.BEARER_TOKEN, process.credentialKind)
        write(
            home, ".claude/settings.json",
            """{"env":{"ANTHROPIC_API_KEY":"settings-key","ANTHROPIC_AUTH_TOKEN":"settings-token"}}""",
        )
        val settings = success(read(home, LocalAiConfigSource.CLAUDE, env))
        assertEquals("settings-token", settings.apiKey)
        assertEquals(ImportedCredentialKind.BEARER_TOKEN, settings.credentialKind)
    }

    @Test
    fun `claude supports env only top model and connection metadata without key`() = withHome { home ->
        val config = success(read(home, LocalAiConfigSource.CLAUDE, mapOf("ANTHROPIC_API_KEY" to "process-key")))
        assertEquals("process-key", config.apiKey)
        assertEquals("https://api.anthropic.com", config.endpoint)
        write(home, ".claude/settings.json", """{"model":"top-model"}""")
        assertEquals("top-model", success(read(home, LocalAiConfigSource.CLAUDE)).model)
        assertEquals(
            "process-key",
            success(read(home, LocalAiConfigSource.CLAUDE, mapOf("ANTHROPIC_API_KEY" to "process-key"))).apiKey,
        )
        write(home, ".claude/settings.json", """{"env":{"ANTHROPIC_MODEL":"env-model"}}""")
        assertNull(success(read(home, LocalAiConfigSource.CLAUDE)).apiKey)
    }

    @Test
    fun `claude OAuth is never exported or replaced with another key`() = withHome { home ->
        write(
            home, ".claude/settings.json",
            """{"env":{"ANTHROPIC_AUTH_TOKEN":"sk-ant-oat-test-oauth","ANTHROPIC_API_KEY":"unused-key"}}""",
        )
        assertFailure(LocalAiImportFailure.UNSUPPORTED_AUTH, read(home, LocalAiConfigSource.CLAUDE))
        write(
            home, ".claude/settings.json",
            """{"model":"safe-model","env":{"ANTHROPIC_AUTH_TOKEN":"sk-ant-oat-test-oauth"}}""",
        )
        val config = success(read(home, LocalAiConfigSource.CLAUDE, mapOf("ANTHROPIC_API_KEY" to "wrong-old-key")))
        assertNull(config.apiKey)
        assertEquals(ImportedCredentialKind.BEARER_TOKEN, config.credentialKind)
        Files.delete(home.resolve(".claude/settings.json"))
        assertFailure(
            LocalAiImportFailure.UNSUPPORTED_AUTH,
            read(home, LocalAiConfigSource.CLAUDE, mapOf("ANTHROPIC_API_KEY" to "sk-ant-oat-test-oauth")),
        )
    }

    @Test
    fun `helpers and OAuth caches are not executed or imported`() = withHome { home ->
        val marker = home.resolve("must-not-exist")
        write(home, ".claude/settings.json", """{"apiKeyHelper":"touch $marker"}""")
        write(home, ".claude/.credentials.json", """{"accessToken":"cached-secret"}""")
        assertFailure(LocalAiImportFailure.UNSUPPORTED_AUTH, read(home, LocalAiConfigSource.CLAUDE))
        write(home, ".codex/config.toml", "api_key_helper = 'touch $marker'")
        assertFailure(LocalAiImportFailure.UNSUPPORTED_AUTH, read(home))
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `only selected source and explicitly configured directory are read`() = withHome { home ->
        write(home, ".claude/settings.json", "invalid-json")
        assertEquals("codex-key", success(read(home, env = mapOf("OPENAI_API_KEY" to "codex-key"))).apiKey)
        write(home, ".codex/config.toml", "not toml")
        write(home, "isolated-codex/auth.json", """{"OPENAI_API_KEY":"isolated-key"}""")
        assertEquals(
            "isolated-key",
            success(read(home, env = mapOf("CODEX_HOME" to home.resolve("isolated-codex").toString()))).apiKey,
        )
        write(home, "isolated-claude/settings.json", """{"env":{"ANTHROPIC_API_KEY":"isolated-claude-key"}}""")
        assertEquals(
            "isolated-claude-key",
            success(
                read(
                    home, LocalAiConfigSource.CLAUDE,
                    mapOf("CLAUDE_CONFIG_DIR" to home.resolve("isolated-claude").toString()),
                ),
            ).apiKey,
        )
        assertFailure(
            LocalAiImportFailure.UNSUPPORTED_CONFIG,
            read(home, env = mapOf("CODEX_HOME" to "relative-project")),
        )
    }

    @Test
    fun `TOML strings preserve hash and escapes rather than selecting comment content`() = withHome { home ->
        write(home, ".codex/config.toml", """
            "model" = "model\u002dname#literal" # model_provider = 'wrong'
            "model_provider" = 'gate#way'
            [model_providers.'gate#way']
            base_url = 'https://gateway.test/v1'
            http_headers = { "Authorization" = "Bearer fixture\u002dsecret" }
        """.trimIndent())
        val config = success(read(home))
        assertEquals("model-name#literal", config.model)
        assertEquals("fixture-secret", config.apiKey)
    }

    @Test
    fun `real shaped skills arrays and multiline agent instructions preserve provider scope`() = withHome { home ->
        val triple = "\"\"\""
        write(home, ".codex/config.toml", """
            model = 'selected-model'
            model_provider = 'gateway'
            [model_providers.gateway]
            base_url = 'https://gateway.test/proxy/v1'
            env_key = 'GATEWAY_KEY'
            wire_api = 'responses'

            [[skills.config]]
            path = '/synthetic/skills/one/SKILL.md'
            enabled = true
            [skills.config.metadata]
            model_provider = 'not-a-root-provider'
            [[skills.config]]
            path = '/synthetic/skills/two/SKILL.md'
            enabled = false
            [skills.config.metadata]
            model_provider = 'still-not-a-root-provider'

            [agents.explore]
            developer_instructions = $triple
            # These are instruction text, not TOML sections:
            [model_providers.gateway]
            base_url = "https://wrong.test/v1"
            env_key = "WRONG_KEY"
            escaped quote: \" and line continuation \
                continued text
            $triple
            [agents.executor]
            developer_instructions = '''
            [[skills.config]]
            model_provider = 'wrong-again'
            literal backslash: \ # not a TOML comment
            '''
        """.trimIndent())
        val config = success(read(home, env = mapOf("GATEWAY_KEY" to "selected-key", "WRONG_KEY" to "wrong-key")))
        assertEquals("selected-key", config.apiKey)
        assertEquals("selected-model", config.model)
        assertEquals("https://gateway.test/proxy/v1/responses", config.endpoint)
    }

    @Test
    fun `multiline connection fields and connection array tables fail closed`() = withHome { home ->
        val triple = "\"\"\""
        for (text in listOf(
            "model = ${triple}selected-model$triple",
            "model_provider = 'gateway'\n[model_providers.gateway]\nbase_url = ${triple}https://gateway.test$triple",
            "[[profiles.work]]\nmodel_provider = 'gateway'",
            "[[model_providers.gateway]]\nbase_url = 'https://gateway.test'",
            "[model_providers.gateway]\n[[model_providers.gateway.http_headers]]\nAuthorization = 'Bearer key'",
        )) {
            write(home, ".codex/config.toml", text)
            assertFailure(LocalAiImportFailure.UNSUPPORTED_CONFIG, read(home))
        }
        write(home, ".codex/config.toml", "[agents.explore]\ndeveloper_instructions = ${triple}unterminated")
        assertFailure(LocalAiImportFailure.MALFORMED, read(home, env = mapOf("OPENAI_API_KEY" to "no-fallback")))
    }

    @Test
    fun `models share the UI save limit of 128 characters`() = withHome { home ->
        for (length in listOf(128, 129)) {
            val model = "m".repeat(length)
            write(home, ".codex/config.toml", "model = '$model'")
            write(home, ".claude/settings.json", """{"model":"$model"}""")
            for (source in LocalAiConfigSource.entries) {
                if (length == 128) assertEquals(model, success(read(home, source)).model)
                else assertFailure(LocalAiImportFailure.MALFORMED, read(home, source))
            }
        }
    }

    @Test
    fun `invalid or unsupported TOML cannot silently select another provider`() = withHome { home ->
        val malformed = listOf(
            "model_provider = gateway", "model_provider = [ 'gateway' ]", "profile = 42",
            "model_provider = 'openai'\nmodel_provider = 'gateway'",
            "model = 'unterminated", "model = \"bad\\q\"", "[model_providers.gateway",
            "model_provider = { name = 'gateway' }", "model = \"bad\\uD800\"",
            "[model_providers.openai]\nwire_api = true", "[model_providers.openai]\nrequires_openai_auth = 'true'",
        )
        for (text in malformed) {
            write(home, ".codex/config.toml", text)
            assertFailure(LocalAiImportFailure.MALFORMED, read(home, env = mapOf("OPENAI_API_KEY" to "no-fallback")))
        }
        for (text in listOf(
            "model = \"\"\"multiline\"\"\"",
            "[[model_providers.gateway]]\nbase_url = 'https://gateway.test'",
        )) {
            write(home, ".codex/config.toml", text)
            assertFailure(LocalAiImportFailure.UNSUPPORTED_CONFIG, read(home))
        }
    }

    @Test
    fun `rejects unsafe URLs and accepts only exact HTTP loopback hosts`() = withHome { home ->
        val unsafe = listOf(
            "http://gateway.test/v1", "http://localhost.evil.test/v1", "http://127.0.0.2/v1",
            "http://127.1/v1", "http://2130706433/v1", "file:///tmp/key", "https://user:pass@gateway.test/v1",
            "https://gateway.test/v1?key=secret", "https://gateway.test/v1#secret", "https://gateway.test:99999/v1",
            "https://gateway.test:/v1", "https:///missing-host", "not-a-url", "https://gateway.test/has space",
        )
        for (base in unsafe) {
            codexGateway(home, "env_key = 'KEY'", base)
            assertFailure(
                LocalAiImportFailure.MALFORMED,
                read(home, env = mapOf("KEY" to "secret", "OPENAI_API_KEY" to "no-fallback")),
            )
            write(home, ".claude/settings.json", """{"env":{"ANTHROPIC_BASE_URL":"$base"}}""")
            assertFailure(LocalAiImportFailure.MALFORMED, read(home, LocalAiConfigSource.CLAUDE))
        }
        for (base in listOf("http://localhost:1234/v1", "http://127.0.0.1:1234/v1", "http://[::1]:1234/v1")) {
            codexGateway(home, "", base)
            assertEquals("$base/responses", success(read(home)).endpoint)
        }
    }

    @Test
    fun `rejects invalid field types secrets and oversized or malformed JSON`() = withHome { home ->
        for (text in listOf(
            "{", "[]", "{\"env\":[]}", "{\"model\":42}", "{\"model\":null}",
            "{\"env\":{\"ANTHROPIC_API_KEY\":123}}", "{\"env\":{\"ANTHROPIC_BASE_URL\":true}}",
            "{\"env\":{\"ANTHROPIC_API_KEY\":\"line\\nbreak\"}}", "{\"model\":\"line\\nbreak\"}",
        )) {
            write(home, ".claude/settings.json", text)
            assertFailure(LocalAiImportFailure.MALFORMED, read(home, LocalAiConfigSource.CLAUDE))
        }
        Files.delete(home.resolve(".claude/settings.json"))
        for (key in listOf("", "line\nbreak", "has space", "x".repeat(8193), "bad\u0000key")) {
            assertFailure(LocalAiImportFailure.MALFORMED, read(home, env = mapOf("OPENAI_API_KEY" to key)))
            assertFailure(
                LocalAiImportFailure.MALFORMED,
                read(home, LocalAiConfigSource.CLAUDE, mapOf("ANTHROPIC_AUTH_TOKEN" to key)),
            )
        }
        write(home, ".claude/settings.json", "{\"ignored\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}")
        assertFailure(LocalAiImportFailure.MALFORMED, read(home, LocalAiConfigSource.CLAUDE))
        write(home, ".codex/auth.json", """{"OPENAI_API_KEY":42}""")
        assertFailure(LocalAiImportFailure.MALFORMED, read(home))
    }

    @Test
    fun `file reads are bounded and strictly UTF8`() = withHome { home ->
        for ((source, file) in listOf(
            LocalAiConfigSource.CODEX to ".codex/config.toml",
            LocalAiConfigSource.CODEX to ".codex/auth.json",
            LocalAiConfigSource.CLAUDE to ".claude/settings.json",
        )) {
            val path = write(home, file, "x".repeat(256 * 1024 + 1))
            assertFailure(LocalAiImportFailure.MALFORMED, read(home, source))
            Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
            assertFailure(LocalAiImportFailure.MALFORMED, read(home, source))
            Files.delete(path)
        }
        write(home, ".codex/config.toml", "#" + "x".repeat(256 * 1024 - 1))
        assertEquals("key", success(read(home, env = mapOf("OPENAI_API_KEY" to "key"))).apiKey)
    }

    @Test
    fun `directories and symbolic links are rejected including linked config directories`() = withHome { home ->
        val directory = Files.createDirectories(home.resolve(".codex/config.toml"))
        assertFailure(LocalAiImportFailure.MALFORMED, read(home))
        Files.delete(directory)
        val target = write(home, "fixture.toml", "model = 'safe-model'")
        Files.createSymbolicLink(directory, target)
        assertFailure(LocalAiImportFailure.MALFORMED, read(home))
        Files.delete(directory)
        Files.createSymbolicLink(directory, home.resolve("missing.toml"))
        assertFailure(LocalAiImportFailure.MALFORMED, read(home))
        Files.delete(directory)
        Files.delete(home.resolve(".codex"))
        Files.createSymbolicLink(home.resolve(".codex"), Files.createDirectory(home.resolve("other-config")))
        assertFailure(LocalAiImportFailure.MALFORMED, read(home))
    }

    @Test
    fun `permission failures have a stable reason without file details`() = withHome { home ->
        val path = write(home, ".codex/config.toml", "model = 'private-model'")
        val original = Files.getPosixFilePermissions(path)
        try {
            Files.setPosixFilePermissions(path, emptySet<PosixFilePermission>())
            // Privileged test users can still read mode-000 files.
            if (!Files.isReadable(path)) assertFailure(LocalAiImportFailure.PERMISSION_DENIED, read(home))
        } finally {
            Files.setPosixFilePermissions(path, original)
        }
    }

    @Test
    fun `config result and failures never print secrets or raw input`() = withHome { home ->
        val secret = "fixture-secret-never-print"
        val config = ImportedAiConfiguration(LocalAiConfigSource.CODEX, secret, "https://$secret.test", secret)
        for (rendered in listOf(config.toString(), LocalAiConfigurationImport.Success(config).toString())) {
            assertFalse(rendered.contains(secret))
            assertTrue(rendered.contains("redacted"))
        }
        write(home, ".codex/auth.json", "{ $secret malformed input }")
        val failure = read(home)
        assertFailure(LocalAiImportFailure.MALFORMED, failure)
        assertFalse(failure.toString().contains(secret))
        assertFalse(failure.toString().contains(home.toString()))
    }

    private fun codexGateway(home: Path, declaration: String, base: String = "https://gateway.test/proxy/v1") {
        write(
            home, ".codex/config.toml",
            "model_provider = 'gateway'\n[model_providers.gateway]\nbase_url = '$base'\n$declaration",
        )
    }

    private fun read(
        home: Path,
        source: LocalAiConfigSource = LocalAiConfigSource.CODEX,
        env: Map<String, String> = emptyMap(),
    ): LocalAiConfigurationImport = LocalAiConfigurationImporter(home, env).read(source)

    private fun success(result: LocalAiConfigurationImport): ImportedAiConfiguration =
        assertIs<LocalAiConfigurationImport.Success>(result).config

    private fun assertFailure(reason: LocalAiImportFailure, result: LocalAiConfigurationImport) {
        assertEquals(reason, assertIs<LocalAiConfigurationImport.Failure>(result).reason)
    }

    private fun write(home: Path, relative: String, text: String): Path {
        val path = home.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    private fun withHome(block: (Path) -> Unit) {
        val home = Files.createTempDirectory("local-ai-import-fixture-").toRealPath()
        try {
            block(home)
        } finally {
            Files.walk(home).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
