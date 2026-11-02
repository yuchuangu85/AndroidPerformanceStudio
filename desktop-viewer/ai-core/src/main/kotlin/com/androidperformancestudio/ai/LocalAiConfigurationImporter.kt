package com.androidperformancestudio.ai

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public enum class LocalAiConfigSource { CODEX, CLAUDE }

public interface LocalAiConfigurationReader {
    public fun read(source: LocalAiConfigSource): LocalAiConfigurationImport
}

public sealed class LocalAiConfigurationImport {
    public data class Success(public val config: ImportedAiConfiguration) : LocalAiConfigurationImport()
    public data class Failure(public val reason: LocalAiImportFailure) : LocalAiConfigurationImport()
}

public enum class ImportedCredentialKind { API_KEY, BEARER_TOKEN }

public class ImportedAiConfiguration(
    public val source: LocalAiConfigSource,
    public val apiKey: String?,
    public val endpoint: String?,
    public val model: String?,
    public val credentialKind: ImportedCredentialKind = ImportedCredentialKind.API_KEY,
) {
    override fun toString(): String =
        "ImportedAiConfiguration(source=$source, apiKey=<redacted>, endpoint=<redacted>, " +
            "model=<redacted>, credentialKind=$credentialKind)"
}

public enum class LocalAiImportFailure {
    NOT_FOUND, MALFORMED, PERMISSION_DENIED, UNSUPPORTED_AUTH, UNSUPPORTED_CONFIG,
}

/** Reads only the selected user's static configuration. Never invokes credential helpers. */
public class LocalAiConfigurationImporter(
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val environment: Map<String, String> = System.getenv(),
) : LocalAiConfigurationReader {
    override fun read(source: LocalAiConfigSource): LocalAiConfigurationImport = try {
        val config = when (source) {
            LocalAiConfigSource.CODEX -> readCodex()
            LocalAiConfigSource.CLAUDE -> readClaude()
        }
        LocalAiConfigurationImport.Success(config)
    } catch (failure: LocalConfigImportProblem) {
        LocalAiConfigurationImport.Failure(failure.reason)
    } catch (_: AccessDeniedException) {
        LocalAiConfigurationImport.Failure(LocalAiImportFailure.PERMISSION_DENIED)
    } catch (_: SecurityException) {
        LocalAiConfigurationImport.Failure(LocalAiImportFailure.PERMISSION_DENIED)
    } catch (_: IOException) {
        LocalAiConfigurationImport.Failure(LocalAiImportFailure.MALFORMED)
    } catch (_: IllegalArgumentException) {
        LocalAiConfigurationImport.Failure(LocalAiImportFailure.MALFORMED)
    }

    private fun readCodex(): ImportedAiConfiguration {
        val directory = configDirectory("CODEX_HOME", ".codex")
        val root = readFile(directory.resolve("config.toml"))?.let { BoundedLocalConfigToml(it).parse() }
            ?: TomlTable()
        val connection = CodexConnection(root, environment)
        val declarations = CodexCredentialDeclarations(connection.provider, connection.requiresOpenAiAuth)
        val hasHelper = root.hasHelper() || connection.provider?.hasHelper() == true
        val credential = declarations.read(environment) ?: readOfficialCodexCredential(directory, connection, hasHelper)
        return connection.metadata.complete(credential, hasHelper)
    }

    private fun readOfficialCodexCredential(
        directory: Path,
        connection: CodexConnection,
        hasHelper: Boolean,
    ): Credential {
        if (!connection.acceptsOfficialCredential) return Credential(null)
        val processKey = LocalConfigFields.secret(environment["OPENAI_API_KEY"])
        return when {
            processKey != null -> Credential(processKey)
            connection.readsAuthFile -> readCodexAuthFile(directory, hasHelper)
            else -> Credential(null)
        }
    }

    private fun readCodexAuthFile(directory: Path, hasHelper: Boolean): Credential {
        val auth = readFile(directory.resolve("auth.json"))?.let(::jsonObject)
        // Keep short-circuiting: helper/OAuth markers do not require inspecting other auth values.
        val unsupportedAuth = hasHelper || auth?.keys?.any { it in OAUTH_FIELDS } == true ||
            auth?.string("auth_mode") == "chatgpt"
        return Credential(LocalConfigFields.secret(auth?.string("OPENAI_API_KEY")), unsupportedAuth = unsupportedAuth)
    }

    private fun readClaude(): ImportedAiConfiguration {
        val directory = configDirectory("CLAUDE_CONFIG_DIR", ".claude")
        val root = readFile(directory.resolve("settings.json"))?.let(::jsonObject)
        val settings = root?.objectField("env")
        val connectionKeys = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL")
        // Select one complete connection group; never attach an inherited key to a settings gateway.
        val useSettings = connectionKeys.any { settings?.containsKey(it) == true }
        fun connection(name: String): String? = if (useSettings) settings?.string(name) else environment[name]
        val explicitBase = connection("ANTHROPIC_BASE_URL")
        val endpoint = LocalConfigFields.endpoint(explicitBase ?: ANTHROPIC_BASE)
        val model = LocalConfigFields.model(
            settings?.string("ANTHROPIC_MODEL") ?: root?.string("model") ?: environment["ANTHROPIC_MODEL"],
        )
        val metadata = ConnectionMetadata(LocalAiConfigSource.CLAUDE, endpoint, model, explicitBase != null)
        val token = connection("ANTHROPIC_AUTH_TOKEN")
        val key = if (token == null) connection("ANTHROPIC_API_KEY") else null
        val value = LocalConfigFields.secret(token ?: key)
        val oauth = value?.startsWith("sk-ant-oat", ignoreCase = true) == true
        val credential = Credential(
            if (oauth) null else value,
            if (token != null) ImportedCredentialKind.BEARER_TOKEN else ImportedCredentialKind.API_KEY,
            unsupportedAuth = oauth,
        )
        return metadata.complete(credential, root?.containsKey("apiKeyHelper") == true)
    }

    private fun configDirectory(variable: String, defaultName: String): Path {
        val configured = environment[variable] ?: return home.toAbsolutePath().normalize().resolve(defaultName)
        LocalConfigFields.field(configured)
        val path = Path.of(configured)
        if (!path.isAbsolute) invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        return path.normalize()
    }

    private fun readFile(path: Path): String? = try {
        var ancestor = path.root
        for (part in path.parent) {
            ancestor = ancestor.resolve(part)
            val attributes = Files.readAttributes(ancestor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink) invalidLocalConfig()
        }
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.isSymbolicLink || before.size() > MAX_FILE_BYTES) invalidLocalConfig()
        val bytes = ByteBuffer.allocate(MAX_FILE_BYTES + 1)
        Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            while (bytes.hasRemaining() && channel.read(bytes) != -1) {
                // The extra byte detects growth beyond the limit after the attribute check.
            }
        }
        if (bytes.position() > MAX_FILE_BYTES) invalidLocalConfig()
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || before.fileKey() != after.fileKey()) invalidLocalConfig()
        bytes.flip()
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(bytes).toString()
    } catch (_: NoSuchFileException) {
        null
    }

    private companion object {
        const val MAX_FILE_BYTES = 256 * 1024
        const val ANTHROPIC_BASE = "https://api.anthropic.com"
        val OAUTH_FIELDS = setOf("tokens", "oauth", "id_token", "access_token", "refresh_token")
    }
}

private class CodexConnection(root: TomlTable, environment: Map<String, String>) {
    private val profile = selectedProfile(root)
    private val model = LocalConfigFields.model(
        profile?.text("model") ?: root.text("model") ?: environment["OPENAI_MODEL"],
    )
    private val providerId = LocalConfigFields.field(profile?.text("model_provider") ?: root.text("model_provider"))
        ?: "openai"
    val provider = selectedProvider(root)
    private val configuredBase = provider?.text("base_url")
    private val environmentBase = if (providerId == "openai" && configuredBase == null) {
        environment["OPENAI_BASE_URL"]
    } else {
        null
    }
    private val explicitBase = configuredBase ?: environmentBase
    private val base = validatedBase()
    private val responsesEndpoint = base.trimEnd('/').let { if (it.endsWith("/responses")) it else "$it/responses" }
    val requiresOpenAiAuth = provider?.boolean("requires_openai_auth") ?: false
    private val officialEndpoint = LocalConfigFields.isOfficialOpenAi(base)
    val acceptsOfficialCredential = requiresOpenAiAuth ||
        providerId == "openai" && (officialEndpoint || environmentBase != null)
    val readsAuthFile = requiresOpenAiAuth || officialEndpoint
    val metadata = ConnectionMetadata(LocalAiConfigSource.CODEX, responsesEndpoint, model, explicitBase != null)

    private fun selectedProfile(root: TomlTable): TomlTable? {
        val name = LocalConfigFields.field(root.text("profile")) ?: return null
        return root.table("profiles")?.table(name) ?: invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
    }

    private fun selectedProvider(root: TomlTable): TomlTable? {
        val selected = root.table("model_providers")?.table(providerId)
        if (selected == null && providerId != "openai") invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        selected?.text("wire_api")?.let {
            if (it != "responses") invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        }
        return selected
    }

    private fun validatedBase(): String {
        if (providerId != "openai" && explicitBase == null) invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        return LocalConfigFields.endpoint(explicitBase ?: "https://api.openai.com/v1")
    }
}

private class CodexCredentialDeclarations(provider: TomlTable?, requiresOpenAiAuth: Boolean) {
    // requires_openai_auth deliberately disables env_key, including its lookup.
    private val envKey = if (requiresOpenAiAuth) null else provider?.text("env_key")
    private val literalHeader = provider?.table("http_headers")?.authorization()
    private val headerEnvironment = provider?.table("env_http_headers")?.authorization()
    private val bearerToken = provider?.text("experimental_bearer_token")

    init {
        val declarations = listOfNotNull(envKey, literalHeader, headerEnvironment, bearerToken).size
        // A provider's explicit bearer token does not require borrowing an OpenAI login credential.
        val conflictingOpenAiHeaders = requiresOpenAiAuth && (literalHeader != null || headerEnvironment != null)
        if (declarations > 1 || conflictingOpenAiHeaders) {
            invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_CONFIG)
        }
    }

    fun read(environment: Map<String, String>): Credential? = when {
        envKey != null -> Credential(LocalConfigFields.secret(environment[LocalConfigFields.environmentName(envKey)]))
        headerEnvironment != null -> Credential(
            LocalConfigFields.authorization(environment[LocalConfigFields.environmentName(headerEnvironment)]),
            ImportedCredentialKind.BEARER_TOKEN,
        )
        literalHeader != null -> Credential(
            LocalConfigFields.authorization(literalHeader), ImportedCredentialKind.BEARER_TOKEN,
        )
        bearerToken != null -> Credential(LocalConfigFields.secret(bearerToken), ImportedCredentialKind.BEARER_TOKEN)
        else -> null
    }
}

private class ConnectionMetadata(
    private val source: LocalAiConfigSource,
    private val endpoint: String,
    private val model: String?,
    explicitEndpoint: Boolean,
) {
    private val hasMetadata = explicitEndpoint || model != null

    fun complete(credential: Credential, hasHelper: Boolean): ImportedAiConfiguration {
        if (credential.value == null && !hasMetadata) {
            val unsupported = hasHelper || credential.unsupportedAuth
            val reason = if (unsupported) LocalAiImportFailure.UNSUPPORTED_AUTH else LocalAiImportFailure.NOT_FOUND
            invalidLocalConfig(reason)
        }
        return ImportedAiConfiguration(source, credential.value, endpoint, model, credential.kind)
    }
}

private class Credential(
    val value: String?,
    val kind: ImportedCredentialKind = ImportedCredentialKind.API_KEY,
    val unsupportedAuth: Boolean = false,
)

internal class LocalConfigImportProblem(val reason: LocalAiImportFailure) : RuntimeException()

internal fun invalidLocalConfig(reason: LocalAiImportFailure = LocalAiImportFailure.MALFORMED): Nothing =
    throw LocalConfigImportProblem(reason)

private object LocalConfigFields {
    private const val MAX_FIELD_LENGTH = 4096
    private const val MAX_SECRET_LENGTH = 8192
    private const val MAX_MODEL_LENGTH = 128
    private const val MAX_PORT = 65535
    private const val HTTPS_PORT = 443
    private const val BEARER_PREFIX = "Bearer "

    fun field(value: String?): String? {
        if (value != null) {
            if (value.isBlank() || value.length > MAX_FIELD_LENGTH) invalidLocalConfig()
            if (value.any { it.isISOControl() }) invalidLocalConfig()
        }
        return value
    }

    fun secret(value: String?): String? {
        if (value != null) {
            if (value.isEmpty() || value.length > MAX_SECRET_LENGTH) invalidLocalConfig()
            if (value.any { it !in '!'..'~' }) invalidLocalConfig()
        }
        return value
    }

    fun model(value: String?): String? {
        field(value)
        if (value != null && value.length > MAX_MODEL_LENGTH) invalidLocalConfig()
        return value
    }

    fun authorization(value: String?): String? {
        if (value == null) return null
        if (!value.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            invalidLocalConfig(LocalAiImportFailure.UNSUPPORTED_AUTH)
        }
        return secret(value.substring(BEARER_PREFIX.length))
    }

    fun environmentName(value: String): String {
        if (!Regex("[A-Za-z_][A-Za-z0-9_]{0,127}").matches(value)) invalidLocalConfig()
        return value
    }

    fun endpoint(value: String): String {
        field(value)
        val uri = try { URI(value) } catch (_: Exception) { invalidLocalConfig() }
        val host = uri.host ?: invalidLocalConfig()
        val loopback = host.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        val safeTransport = uri.scheme == "https" || uri.scheme == "http" && loopback
        if (!safeTransport || uri.isOpaque) invalidLocalConfig()
        val decorated = uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
        if (decorated) invalidLocalConfig()
        val validPort = uri.port in -1..MAX_PORT && uri.port != 0
        if (!validPort || value.any { it.isWhitespace() } || uri.rawAuthority.endsWith(':')) invalidLocalConfig()
        return value
    }

    fun isOfficialOpenAi(value: String): Boolean {
        val uri = URI(value)
        return uri.scheme == "https" && uri.host.equals("api.openai.com", ignoreCase = true) &&
            uri.port in setOf(-1, HTTPS_PORT) && uri.path.trimEnd('/') in setOf("/v1", "/v1/responses")
    }
}

private fun jsonObject(text: String): JsonObject {
    JsonNestingGuard().validate(text)
    return try {
        Json.parseToJsonElement(text) as? JsonObject ?: invalidLocalConfig()
    } catch (_: IllegalArgumentException) {
        invalidLocalConfig()
    }
}

private class JsonNestingGuard {
    private var depth = 0
    private var quoted = false
    private var escaped = false

    fun validate(text: String) {
        for (character in text) accept(character)
    }

    private fun accept(character: Char) {
        if (quoted) {
            acceptQuoted(character)
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MAX_DEPTH) invalidLocalConfig()
                '}', ']' -> if (--depth < 0) invalidLocalConfig()
            }
        }
    }

    private fun acceptQuoted(character: Char) {
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '"' -> quoted = false
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

private fun JsonObject.string(key: String): String? {
    val value = get(key) ?: return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidLocalConfig()
}

private fun JsonObject.objectField(key: String): JsonObject? =
    get(key)?.let { it as? JsonObject ?: invalidLocalConfig() }
