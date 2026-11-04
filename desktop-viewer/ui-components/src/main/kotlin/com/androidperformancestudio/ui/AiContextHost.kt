package com.androidperformancestudio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf

/** Neutral Profiler-to-host facts. No provider, prompt, credentials or filesystem authority. */
public data class UiAiEvidence(val id: String, val kind: String, val summary: String, val payloadJson: String)

public data class UiAiContext(
    val key: String,
    val title: String,
    val currentSelection: Boolean,
    val evidence: List<UiAiEvidence>,
) {
    init {
        require(evidence.size in 1..100)
        require(evidence.all { it.id.length in 1..256 && it.payloadJson.length <= 65_536 })
    }
}

public interface AiContextHost {
    public val analysisBusy: Boolean get() = false
    public fun publish(context: UiAiContext)
    public fun remove(key: String)
    public fun analyze(context: UiAiContext)
}

/** Fail closed on a large summary; callers aggregate facts before reaching this boundary. */
public fun boundedAiContext(context: UiAiContext?): UiAiContext? {
    context ?: return null
    val payloadBytes = context.evidence.sumOf { it.payloadJson.toByteArray(Charsets.UTF_8).size.toLong() }
    val labelBytes = context.evidence.sumOf { it.summary.toByteArray(Charsets.UTF_8).size.toLong() }
    return context.takeIf { payloadBytes <= 8 * 1024 && labelBytes <= 2 * 1024 }
}

public val LocalAiContextHost = staticCompositionLocalOf<AiContextHost?> { null }

/** Retained inactive pages must not replace the active page's immutable context. */
@Composable
public fun PublishAiContext(key: String, context: UiAiContext?) {
    val host = LocalAiContextHost.current
    val active = LocalWindowMenuBarActive.current
    LaunchedEffect(host, key, context, active) {
        if (active) {
            if (context == null) host?.remove(key) else host?.publish(context)
        }
    }
    DisposableEffect(host, key) { onDispose { host?.remove(key) } }
}

/** Explicit primitive facts only: rejects arbitrary models, NaN, excessive nesting and oversized text. */
public fun uiAiPayload(vararg fields: Pair<String, Any?>): String {
    fun encode(value: Any?, depth: Int): String {
        require(depth <= 8)
        fun quoted(text: String): String {
            require(text.length <= 4096)
            return buildString {
                append('"')
                text.forEach { char ->
                    when (char) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
                    }
                }
                append('"')
            }
        }
        return when (value) {
            null -> "null"
            is String -> quoted(value)
            is Boolean, is Int, is Long -> value.toString()
            is Double -> if (value.isFinite()) value.toString() else "null"
            is Float -> if (value.isFinite()) value.toString() else "null"
            is List<*> -> {
                require(value.size <= 100)
                value.joinToString(",", "[", "]") { encode(it, depth + 1) }
            }
            is Map<*, *> -> {
                require(value.size <= 100 && value.keys.all { it is String })
                value.entries.joinToString(",", "{", "}") { (key, item) -> "${quoted(key as String)}:${encode(item, depth + 1)}" }
            }
            else -> error("Unsupported AI evidence value")
        }
    }
    require(fields.map { it.first }.distinct().size == fields.size)
    return encode(fields.toMap(), 0).also { require(it.length <= 65_536) }
}

/** Binds reference identity to canonical snapshot/session data without exposing raw identity strings. */
public fun uiAiEvidenceId(kind: String, vararg identity: String): String {
    require(kind.matches(Regex("[a-z][a-z0-9-]{0,63}")))
    val bytes = identity.joinToString("\u0000").toByteArray()
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return "$kind:$hash"
}
