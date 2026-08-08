package com.androidperformancestudio.network.model

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

public const val NETWORK_REDACTION_POLICY_VERSION: Int = 1

public class NetworkUrlRedactor(
    private val pathSalt: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
) {
    public fun redact(raw: String): String = runCatching {
        val uri = URI(raw)
        require(!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank())
        buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.host.lowercase())
            if (uri.port != -1) append(':').append(uri.port)
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") append("/_aps/").append(endpointId(uri.rawPath)) else append('/')
            uri.rawQuery?.let { query ->
                val keys = query.split('&').map { it.substringBefore('=') }.filter { it.isNotBlank() }
                if (keys.isNotEmpty()) append('?').append(keys.joinToString("&") { "$it=<redacted>" })
            }
        }
    }.getOrElse { "redacted://invalid-url" }

    private fun endpointId(path: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pathSalt + path.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

public object NetworkHeaderRedactor {
    private val safeValueHeaders = setOf("content-type", "content-length", "content-encoding")

    public fun redact(values: Iterable<Pair<String, String>>): Map<String, String> =
        values.associate { (name, value) -> name to if (name.lowercase() in safeValueHeaders) value else "<redacted>" }
}
