package com.androidperformancestudio.aiplatform.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

public const val PLATFORM_SCHEMA_VERSION: Int = 1

/** Capabilities describe an upper bound, not a grant or a sandbox implementation. */
@Serializable
public enum class Permission {
    READ_EVIDENCE,
    READ_SOURCE_CANDIDATES,
    SEND_SOURCE_SNIPPETS,
    PROVIDER_NETWORK,
    EXECUTE_SCRIPT,
    WRITE_ARTIFACT,
    DEVICE_WRITE,
}

@Serializable
public enum class ExtensionKind { AGENT, SKILL }

@Serializable
public enum class ScriptRuntime { NONE, PYTHON, NODE }

@Serializable
public enum class ExtensionOrigin { BUILTIN, IMPORTED }

@Serializable
public enum class TrustStatus { UNTRUSTED, TRUSTED, REVOKED }

@Serializable
public data class ResourceBudget(
    val timeoutMillis: Long = 60_000,
    val maxInputBytes: Int = 262_144,
    val maxOutputBytes: Int = 65_536,
    val maxConcurrentRuns: Int = 1,
    val maxChildDepth: Int = 0,
    val maxCpuMillis: Long = 30_000,
    val maxMemoryBytes: Long = 268_435_456,
) {
    init {
        require(timeoutMillis in 1..300_000)
        require(maxInputBytes in 1..1_048_576 && maxOutputBytes in 1..1_048_576)
        require(maxConcurrentRuns in 1..4 && maxChildDepth in 0..3)
        require(maxCpuMillis in 1..300_000 && maxMemoryBytes in 1..1_073_741_824)
    }
}

/** Content only. Origin, trust, signature and enablement are host-owned installation metadata. */
@Serializable
public data class ExtensionManifest(
    val schemaVersion: Int = PLATFORM_SCHEMA_VERSION,
    val kind: ExtensionKind,
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val entrypoint: String,
    val inputSchema: String,
    val outputSchema: String,
    val contextKinds: Set<String>,
    val evidenceKinds: Set<String>,
    val allowedProviders: Set<String> = emptySet(),
    val allowedSkills: Set<String> = emptySet(),
    val allowedAgents: Set<String> = emptySet(),
    val allowedTools: Set<String> = emptySet(),
    val permissions: Set<Permission> = emptySet(),
    val budget: ResourceBudget = ResourceBudget(),
    val runtime: ScriptRuntime = ScriptRuntime.NONE,
    val runtimeVersion: String? = null,
    val scripts: Set<String> = emptySet(),
    val dependencies: Set<String> = emptySet(),
    val artifactFormats: Set<ArtifactFormat> = emptySet(),
    val files: Map<String, String>,
) {
    init {
        require(schemaVersion == PLATFORM_SCHEMA_VERSION) { "E_SCHEMA_VERSION" }
        requireStableId(id)
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && version.length <= 32) { "E_VERSION" }
        require(name.isNotBlank() && name.length <= 128)
        require(description.length <= 2048)
        require(inputSchema.isNotBlank() && inputSchema.length <= 128)
        require(outputSchema.isNotBlank() && outputSchema.length <= 128)
        require(contextKinds.isNotEmpty() && contextKinds.size <= 32 && evidenceKinds.size <= 64)
        require((contextKinds + evidenceKinds).all { it.length in 1..128 })
        require(files.isNotEmpty() && files.size <= 64) { "E_FILE_LIMIT" }
        files.forEach { (path, hash) ->
            requireBundlePath(path)
            require(path != "manifest.json" && path != "installation.json") { "E_RESERVED_PATH" }
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "E_CONTENT_HASH" }
        }
        require(entrypoint in files) { "E_ENTRYPOINT" }
        require(entrypoint.endsWith(".md")) { "E_ENTRYPOINT" }
        require(scripts.size <= 16 && scripts.all { it in files && it.startsWith("scripts/") })
        require((runtime == ScriptRuntime.NONE) == scripts.isEmpty()) { "E_SCRIPT_RUNTIME" }
        require(scripts.isEmpty() || Permission.EXECUTE_SCRIPT in permissions) { "E_SCRIPT_PERMISSION" }
        require(scripts.all { it.endsWith(if (runtime == ScriptRuntime.PYTHON) ".py" else ".js") })
        require(
            files.keys.all { path ->
                path in scripts || !path.startsWith("scripts/") && listOf(".md", ".txt", ".json").any(path::endsWith)
            },
        ) { "E_UNDECLARED_SCRIPT" }
        require(runtimeVersion == null || runtimeVersion.length in 1..128)
        require(dependencies.size <= 32 && dependencies.all { it.length in 1..128 })
        require(listOf(allowedProviders, allowedSkills, allowedTools).all { it.size <= 32 })
        require(allowedAgents.size <= 16)
        (allowedProviders + allowedSkills + allowedTools + allowedAgents).forEach(::requireStableId)
    }
}

@Serializable
public enum class ArtifactFormat { HTML, MARKDOWN }

public object PlatformJson {
    public val codec: Json =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
        }

    public fun decodeManifest(bytes: ByteArray): ExtensionManifest {
        require(bytes.size <= 65_536) { "E_MANIFEST_LIMIT" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        rejectDuplicateJsonMembers(text)
        return codec.decodeFromString<ExtensionManifest>(text)
    }
}

public fun requireStableId(id: String) {
    require(id.length in 1..80 && id.matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*"))) { "E_STABLE_ID" }
}

public fun requireBundlePath(path: String) {
    require(path.length in 1..240 && !path.startsWith('/') && '\\' !in path && ':' !in path) { "E_BUNDLE_PATH" }
    require(
        path.split('/').all {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) &&
                !it.endsWith('.') &&
                !it.substringBefore('.').uppercase().matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
        },
    ) {
        "E_BUNDLE_PATH"
    }
}

/** Json's object decoder otherwise accepts duplicate members with last-value-wins semantics. */
private fun rejectDuplicateJsonMembers(text: String) {
    val scopes = mutableListOf<MutableSet<String>?>()
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '{', '[' -> {
                require(scopes.size < 32) { "E_MANIFEST_DEPTH" }
                scopes.add(if (text[index] == '{') mutableSetOf() else null)
            }
            '}', ']' -> {
                require(scopes.isNotEmpty()) { "E_MANIFEST_JSON" }
                scopes.removeAt(scopes.lastIndex)
            }
            '"' -> {
                val start = index++
                while (index < text.length && text[index] != '"') {
                    if (text[index] == '\\') index++
                    index++
                }
                require(index < text.length) { "E_MANIFEST_JSON" }
                var next = index + 1
                while (next < text.length && text[next].isWhitespace()) next++
                if (next < text.length && text[next] == ':') {
                    val key = PlatformJson.codec.decodeFromString<String>(text.substring(start, index + 1))
                    require(scopes.lastOrNull()?.add(key) == true) { "E_DUPLICATE_MEMBER" }
                }
            }
        }
        index++
    }
}
