package com.androidperformancestudio.network.model

public const val NETWORK_REDACTION_POLICY_VERSION: Int = 1

public object NetworkHeaderRedactor {
    private val safeValueHeaders = setOf("content-type", "content-length", "content-encoding")

    public fun redact(values: Iterable<Pair<String, String>>): Map<String, String> =
        values.associate { (name, value) -> name to if (name.lowercase() in safeValueHeaders) value else "<redacted>" }
}
