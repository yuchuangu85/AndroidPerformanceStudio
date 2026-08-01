@file:Suppress("NestedBlockDepth", "MaxLineLength", "MagicNumber")

package com.androidperformancestudio.source

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class SourceHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

public fun interface SourceHttpTransport {
    public fun get(
        url: String,
        headers: Map<String, String>,
    ): SourceHttpResponse
}

public class JdkSourceHttpTransport : SourceHttpTransport {
    // Redirects are handled as explicit failures so credentials can never be replayed to another host.
    private val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(REMOTE_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun get(
        url: String,
        headers: Map<String, String>,
    ): SourceHttpResponse {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(REMOTE_TIMEOUT_SECONDS)).GET()
        headers.forEach(builder::header)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return SourceHttpResponse(response.statusCode(), response.body())
    }

    private companion object {
        const val REMOTE_TIMEOUT_SECONDS: Long = 15
    }
}

public fun interface SourceCredentialProvider {
    public fun credential(key: String): String?
}

public class GitHubSourceProvider(
    private val transport: SourceHttpTransport = JdkSourceHttpTransport(),
    private val credentials: SourceCredentialProvider = SourceCredentialProvider { null },
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SourceProvider {
    override val kind: SourceProviderKind = SourceProviderKind.GITHUB

    override suspend fun resolveRevision(config: SourceProviderConfig): String {
        val github = config.requireGitHub()
        if (github.ref.matches(COMMIT_SHA)) return github.ref.lowercase()
        val response = get(github, "/repos/${github.owner}/${github.repository}/commits/${encode(github.ref)}")
        checkSuccessful(response, "GitHub revision")
        return json.parseToJsonElement(response.body.decodeToString()).jsonObject.getValue("sha").jsonPrimitive.content
    }

    override suspend fun listFiles(
        config: SourceProviderConfig,
        revision: String,
    ): List<ProviderSourceFile> {
        val github = config.requireGitHub()
        val response = get(github, "/repos/${github.owner}/${github.repository}/git/trees/$revision?recursive=1")
        checkSuccessful(response, "GitHub tree")
        return json.parseToJsonElement(response.body.decodeToString()).jsonObject
            .getValue("tree")
            .jsonArray
            .mapNotNull { entry ->
                val item = entry.jsonObject
                if (item["type"]?.jsonPrimitive?.contentOrNull != "blob") return@mapNotNull null
                val path = item.getValue("path").jsonPrimitive.content
                if (!path.isSourcePath()) return@mapNotNull null
                ProviderSourceFile(
                    relativePath = path,
                    sizeBytes = item["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                    contentHash = item["sha"]?.jsonPrimitive?.contentOrNull,
                )
            }.sortedBy(ProviderSourceFile::relativePath)
    }

    override suspend fun readFile(
        config: SourceProviderConfig,
        revision: String,
        relativePath: String,
    ): ByteArray {
        requireSafeRelativePath(relativePath)
        val github = config.requireGitHub()
        val response = get(
            github,
            "/repos/${github.owner}/${github.repository}/contents/${encodePath(relativePath)}?ref=$revision",
            accept = "application/vnd.github.raw+json",
        )
        checkSuccessful(response, "GitHub content")
        return response.body
    }

    private fun get(
        config: SourceProviderConfig.GitHub,
        path: String,
        accept: String = "application/vnd.github+json",
    ): SourceHttpResponse {
        val headers = linkedMapOf("Accept" to accept, "X-GitHub-Api-Version" to "2022-11-28")
        config.credentialKey?.let(credentials::credential)?.takeIf(String::isNotBlank)?.let { token ->
            headers["Authorization"] = "Bearer $token"
        }
        return transport.get("https://api.github.com$path", headers)
    }

    private companion object {
        val COMMIT_SHA: Regex = Regex("[0-9a-fA-F]{40}")
    }
}

public class AospSourceProvider(
    private val transport: SourceHttpTransport = JdkSourceHttpTransport(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SourceProvider {
    override val kind: SourceProviderKind = SourceProviderKind.AOSP

    override suspend fun resolveRevision(config: SourceProviderConfig): String {
        val aosp = config.requireAosp()
        if (aosp.ref.matches(COMMIT_SHA)) return aosp.ref.lowercase()
        val response = transport.get(showUrl(aosp, aosp.ref, "", "JSON"), emptyMap())
        checkSuccessful(response, "AOSP revision")
        val payload = response.body.decodeToString().removeJsonGuard()
        return json.parseToJsonElement(payload).jsonObject.getValue("commit").jsonPrimitive.content
    }

    override suspend fun listFiles(
        config: SourceProviderConfig,
        revision: String,
    ): List<ProviderSourceFile> {
        val aosp = config.requireAosp()
        val pending = ArrayDeque<String>()
        val result = mutableListOf<ProviderSourceFile>()
        pending.addLast("")
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val response = transport.get(showUrl(aosp, revision, directory, "JSON"), emptyMap())
            checkSuccessful(response, "AOSP tree")
            val entries = json.parseToJsonElement(response.body.decodeToString().removeJsonGuard()).jsonObject
                .getValue("entries")
                .jsonArray
            entries.forEach { element ->
                val entry = element.jsonObject
                val name = entry.getValue("name").jsonPrimitive.content
                val path = listOf(directory, name).filter(String::isNotBlank).joinToString("/")
                when (entry.getValue("type").jsonPrimitive.content) {
                    "tree" -> pending.addLast(path)
                    "blob" -> if (path.isSourcePath()) result += ProviderSourceFile(path, 0, entry["id"]?.jsonPrimitive?.contentOrNull)
                }
            }
        }
        return result.sortedBy(ProviderSourceFile::relativePath)
    }

    override suspend fun readFile(
        config: SourceProviderConfig,
        revision: String,
        relativePath: String,
    ): ByteArray {
        requireSafeRelativePath(relativePath)
        val aosp = config.requireAosp()
        val response = transport.get(showUrl(aosp, revision, relativePath, "TEXT"), emptyMap())
        checkSuccessful(response, "AOSP content")
        return Base64.getMimeDecoder().decode(response.body)
    }

    private fun showUrl(
        config: SourceProviderConfig.Aosp,
        revision: String,
        path: String,
        format: String,
    ): String {
        val suffix = path.takeIf(String::isNotBlank)?.let { "/${encodePath(it)}" }.orEmpty()
        return "https://android.googlesource.com/${config.project}/+/${encode(revision)}$suffix?format=$format"
    }

    private companion object {
        val COMMIT_SHA: Regex = Regex("[0-9a-fA-F]{40}")
    }
}

private fun SourceProviderConfig.requireGitHub(): SourceProviderConfig.GitHub =
    requireNotNull(this as? SourceProviderConfig.GitHub) { "GitHubSourceProvider requires GitHub config" }

private fun SourceProviderConfig.requireAosp(): SourceProviderConfig.Aosp =
    requireNotNull(this as? SourceProviderConfig.Aosp) { "AospSourceProvider requires AOSP config" }

private fun checkSuccessful(
    response: SourceHttpResponse,
    operation: String,
) {
    check(response.statusCode in 200..299) { "$operation failed (${response.statusCode})" }
}

private fun String.removeJsonGuard(): String = lineSequence().dropWhile { it.startsWith(")]}'") }.joinToString("\n")

private fun String.isSourcePath(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("kt", "kts", "java", "xml", "c", "cc", "cpp", "cxx", "h", "hh", "hpp")

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

private fun requireSafeRelativePath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." }) {
        "Unsafe source path: $path"
    }
}
