package com.androidperformancestudio.compose.inspection.host

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

data class ComposeAgentBundle(
    val abi: String,
    val nativeAgent: Path,
    val serviceJar: Path,
    val payloadJar: Path,
    val viewInspectorJar: Path,
    val fingerprint: String,
) {
    fun verifyUnchanged() {
        val current = listOf(nativeAgent, serviceJar, payloadJar, viewInspectorJar)
            .joinToString("") { it.sha256() }.sha256()
        require(current == fingerprint) { "Agent bundle changed after authorization" }
    }

    companion object {
        val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86_64")

        fun load(root: Path, abi: String): ComposeAgentBundle {
            require(abi in supportedAbis) { "Unsupported device ABI: $abi" }
            val normalizedRoot = root.toRealPath()
            val manifestPath = normalizedRoot.resolve("manifest.properties")
            val manifest = Properties().apply {
                Files.newInputStream(manifestPath).use(::load)
            }
            fun verified(relative: String, key: String, maxBytes: Long): Path {
                val path = normalizedRoot.resolve(relative).toRealPath()
                require(path.startsWith(normalizedRoot) && Files.isRegularFile(path)) { "Missing agent bundle file: $relative" }
                require(Files.size(path) in 1..maxBytes) { "Invalid agent bundle file size: $relative" }
                val actual = path.sha256()
                require(actual == manifest.getProperty(key)) { "Agent bundle checksum mismatch: $relative" }
                return path
            }
            val agent = verified("agent/$abi/lib_ui_inspector_agent.so", "agent.$abi.sha256", 32L * MIB)
            val service = verified("lib_ui_inspector_service.jar", "service.sha256", 32L * MIB)
            val payload = verified("lib_ui_inspector_payload.jar", "payload.sha256", 64L * MIB)
            val view = verified("view-inspector.jar", "view.sha256", 64L * MIB)
            return ComposeAgentBundle(
                abi = abi,
                nativeAgent = agent,
                serviceJar = service,
                payloadJar = payload,
                viewInspectorJar = view,
                fingerprint = listOf(agent, service, payload, view).joinToString("") { it.sha256() }.sha256(),
            )
        }

        private const val MIB = 1024 * 1024
    }
}

internal fun Path.sha256(): String = Files.newInputStream(this).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toHex()
}

internal fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
