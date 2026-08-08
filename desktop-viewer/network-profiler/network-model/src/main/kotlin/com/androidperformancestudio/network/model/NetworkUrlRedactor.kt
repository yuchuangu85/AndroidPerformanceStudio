package com.androidperformancestudio.network.model

import java.net.URI

/**
 * Redacts sensitive information from HTTP URLs before display or storage.
 *
 * ## Redaction policy
 *
 * The redactor ensures that **no sensitive data leaks into persisted records, exports, or UI**.
 * It follows a "deny-by-default" strategy:
 *
 * | URL component  | Behavior                                      |
 * |----------------|-----------------------------------------------|
 * | scheme         | Preserved                                     |
 * | userinfo       | Always replaced with `<redacted>`             |
 * | host           | Preserved                                     |
 * | port           | Preserved if present                          |
 * | path           | Replaced with a stable non-identifying marker |
 * | query          | All values redacted except whitelisted keys   |
 * | fragment       | Always removed                                |
 *
 * ### Query key whitelisting
 *
 * By default, **no query keys are whitelisted**. Every `?key=value` is rendered as `key=<redacted>`.
 * Pass `queryKeyAllowlist` to preserve specific keys:
 * ```kotlin
 * NetworkUrlRedactor(queryKeyAllowlist = setOf("page", "size", "lang"))
 * ```
 * Sensitive keys (`token`, `auth`, `session`, `key`, `password`, `secret`,
 * `credential`, `apikey`) are **never** whitelisted — even if they appear in the
 * allowlist they are still redacted, and a `redactionWarning` is recorded.
 *
 * ### Path minimization
 *
 * Paths are unrestricted application data and may contain user identifiers,
 * credentials, or business data. The default policy therefore replaces every
 * non-root path with a single marker before the URL leaves the capture process.
 *
 * ### Fallback
 *
 * Malformed URLs (missing scheme or host) are replaced with
 * `redacted://invalid-url`.
 *
 * @param queryKeyAllowlist set of query parameter names whose values should be
 *   preserved. Sensitive keys are always redacted regardless.
 */
public class NetworkUrlRedactor(
    public val queryKeyAllowlist: Set<String> = emptySet(),
) {
    /** Warnings produced during the most recent redaction, e.g. blocked allowlist keys. */
    public val redactionWarnings: MutableList<String> = mutableListOf()

    /**
     * Redacts [raw] and returns a privacy-safe URL string.
     * Clears any previous [redactionWarnings] and populates new ones if
     * allowlist keys were blocked.
     */
    public fun redact(raw: String): String = runCatching {
        redactionWarnings.clear()
        val uri = URI(raw)
        require(!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank())
        buildString {
            append(uri.scheme)
            append("://")
            uri.rawUserInfo?.let { append(REDACTED_VALUE).append('@') }
            append(uri.host)
            if (uri.port != -1) append(':').append(uri.port)
            append(minimizePath(uri.rawPath.orEmpty()))
            uri.rawQuery?.let { query ->
                append('?')
                append(query.split('&').joinToString("&") { parameter ->
                    val key = parameter.substringBefore('=')
                    val value = if (isKeySensitive(key) || key !in queryKeyAllowlist) REDACTED_VALUE else parameter.substringAfter('=', "")
                    if (isKeySensitive(key) && key in queryKeyAllowlist) {
                        redactionWarnings += "Query key \"$key\" is in the allowlist but is classified as sensitive and was redacted."
                    }
                    "$key=$value"
                })
            }
        }
    }.getOrElse { INVALID_URL }

    private fun isKeySensitive(key: String): Boolean =
        SENSITIVE_KEYS.any { key.equals(it, ignoreCase = true) }

    private fun minimizePath(path: String): String =
        when (path) {
            "" -> ""
            "/" -> "/"
            else -> REDACTED_PATH
        }

    public companion object {
        /** Sensitive query keys that are ALWAYS redacted, even if allowlisted. */
        public val SENSITIVE_KEYS: Set<String> = setOf(
            "token", "access_token", "refresh_token", "id_token",
            "auth", "authorization", "apikey", "api_key", "key",
            "password", "passwd", "secret", "client_secret",
            "session", "sessionid", "jsessionid", "credential",
            "signature", "sig",
        )

        internal const val REDACTED_VALUE: String = "<redacted>"
        internal const val REDACTED_PATH: String = "/<redacted-path>"
        internal const val INVALID_URL: String = "redacted://invalid-url"

        /** Returns a redactor with the original "deny-all" behaviour (every query value redacted). */
        public fun default(): NetworkUrlRedactor = NetworkUrlRedactor(queryKeyAllowlist = emptySet())
    }
}
