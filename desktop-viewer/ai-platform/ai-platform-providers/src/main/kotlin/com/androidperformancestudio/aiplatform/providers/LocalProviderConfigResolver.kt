package com.androidperformancestudio.aiplatform.providers

import com.androidperformancestudio.aiplatform.model.CredentialReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

public enum class LocalConfigState { ABSENT, AVAILABLE, MALFORMED, PERMISSION_DENIED, UNSUPPORTED }

/** Contains allowlisted metadata only. No auth file is ever opened by this resolver. */
public data class LocalProviderMetadata(
    val providerId: String,
    val configState: LocalConfigState,
    val configSource: Path?,
    val model: String?,
    val profile: String?,
    val credentialReferences: List<CredentialReference>,
) {
    override fun toString(): String =
        "LocalProviderMetadata(providerId=$providerId, configState=$configState, configSource=masked, model=$model, profile=$profile)"
}

public class LocalProviderConfigResolver(
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val environmentNames: Set<String> = System.getenv().keys.toSet(),
) {
    public fun resolve(providerId: String): LocalProviderMetadata {
        val config =
            when (providerId) {
                "codex" -> home.resolve(".codex/config.toml")
                "claude" -> home.resolve(".claude/settings.json")
                else -> return LocalProviderMetadata(providerId, LocalConfigState.UNSUPPORTED, null, null, null, emptyList())
            }.toAbsolutePath().normalize()
        val credentials =
            when (providerId) {
                "claude" ->
                    listOf(
                        CredentialReference("environment", "anthropic-api-key", "ANTHROPIC_API_KEY" in environmentNames),
                        CredentialReference("environment", "anthropic-auth-token", "ANTHROPIC_AUTH_TOKEN" in environmentNames),
                    )
                else ->
                    listOf(
                        CredentialReference("environment", "openai-api-key", "OPENAI_API_KEY" in environmentNames),
                        // Existence is not proof of a usable managed login. The provider runtime owns it.
                        CredentialReference("codex-runtime", "managed-login", false),
                    )
            }

        fun metadata(
            state: LocalConfigState,
            model: String? = null,
            profile: String? = null,
        ) = LocalProviderMetadata(providerId, state, config, model, profile, credentials)
        try {
            var current = config.root
            for (part in config) {
                current = current.resolve(part)
                if (Files.isSymbolicLink(current)) return metadata(LocalConfigState.PERMISSION_DENIED)
            }
            if (!Files.exists(config, NOFOLLOW_LINKS)) return metadata(LocalConfigState.ABSENT)
            if (!Files.isRegularFile(config, NOFOLLOW_LINKS) ||
                !Files.isReadable(config)
            ) {
                return metadata(LocalConfigState.PERMISSION_DENIED)
            }
            val bytes = Files.newInputStream(config, NOFOLLOW_LINKS).use { it.readNBytes(MAX_CONFIG_BYTES + 1) }
            if (bytes.size > MAX_CONFIG_BYTES) return metadata(LocalConfigState.MALFORMED)
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            return if (providerId == "claude") {
                val root = Json.parseToJsonElement(text) as? JsonObject ?: return metadata(LocalConfigState.MALFORMED)
                val model =
                    root["model"]?.let { value ->
                        val primitive = value as? JsonPrimitive ?: return metadata(LocalConfigState.MALFORMED)
                        if (!primitive.isString) return metadata(LocalConfigState.MALFORMED)
                        primitive.contentOrNull?.takeIf(::validMetadata) ?: return metadata(LocalConfigState.MALFORMED)
                    }
                metadata(LocalConfigState.AVAILABLE, model)
            } else {
                // Deliberately only top-level string metadata; this is not a TOML execution/config loader.
                val selected = linkedMapOf<String, String>()
                for (line in text.lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("[")) {
                        if (!trimmed.endsWith("]")) return metadata(LocalConfigState.MALFORMED)
                        break
                    }
                    if (trimmed.isBlank() || trimmed.startsWith("#")) continue
                    val key = trimmed.substringBefore('=').trim()
                    if (key !in setOf("model", "profile", "model_provider")) continue
                    if ('=' !in trimmed || key in selected) return metadata(LocalConfigState.MALFORMED)
                    val value = trimmed.substringAfter('=').trim()
                    val match =
                        Regex("^\"([A-Za-z0-9._:/-]{1,128})\"\\s*(?:#.*)?$").matchEntire(value)
                            ?: return metadata(LocalConfigState.MALFORMED)
                    selected[key] = match.groupValues[1]
                }
                metadata(LocalConfigState.AVAILABLE, selected["model"], selected["profile"])
            }
        } catch (_: java.nio.file.AccessDeniedException) {
            return metadata(LocalConfigState.PERMISSION_DENIED)
        } catch (_: Exception) {
            return metadata(LocalConfigState.MALFORMED)
        }
    }

    private fun validMetadata(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._:/-]{1,128}"))

    private companion object {
        const val MAX_CONFIG_BYTES = 65_536
    }
}
