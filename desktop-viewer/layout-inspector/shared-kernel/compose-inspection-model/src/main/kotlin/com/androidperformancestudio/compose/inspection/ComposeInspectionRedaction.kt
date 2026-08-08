package com.androidperformancestudio.compose.inspection

fun ComposeInspectionDocument.redacted(): ComposeInspectionDocument = copy(
    privacy = ComposeArchivePrivacy.SAFE_REDACTED,
    frame = frame.copy(
        details = frame.details.mapValues { (_, detail) ->
            detail.copy(
                parameters = detail.parameters.map(ComposeValue::redacted),
                modifiers = detail.modifiers.map(ComposeValue::redacted),
                mergedSemantics = detail.mergedSemantics.map(ComposeValue::redacted),
                unmergedSemantics = detail.unmergedSemantics.map(ComposeValue::redacted),
            )
        },
    ),
)

/**
 * Applies the non-negotiable archive exclusions shared by safe and full-fidelity exports.
 * Full fidelity may retain ordinary runtime values, but it never retains credentials,
 * authentication/session tokens, cookies, or private device paths.
 */
fun ComposeInspectionDocument.sanitizedForExport(
    privacy: ComposeArchivePrivacy,
): ComposeInspectionDocument =
    if (privacy == ComposeArchivePrivacy.SAFE_REDACTED) {
        redacted()
    } else {
        copy(
            privacy = ComposeArchivePrivacy.FULL_FIDELITY,
            artifact = artifact?.copy(source = artifact.source.sanitizedArtifactSource()),
            frame =
                frame.copy(
                    details =
                        frame.details.mapValues { (_, detail) ->
                            detail.copy(
                                parameters = detail.parameters.map(ComposeValue::sensitiveValuesRedacted),
                                modifiers = detail.modifiers.map(ComposeValue::sensitiveValuesRedacted),
                                mergedSemantics = detail.mergedSemantics.map(ComposeValue::sensitiveValuesRedacted),
                                unmergedSemantics = detail.unmergedSemantics.map(ComposeValue::sensitiveValuesRedacted),
                            )
                        },
                ),
        )
    }

private fun ComposeValue.redacted(): ComposeValue = copy(
    value = value?.let { "<redacted>" },
    elements = elements.map(ComposeValue::redacted),
)

private fun ComposeValue.sensitiveValuesRedacted(): ComposeValue {
    val sensitive = name.isSensitiveName() || value?.isSensitiveValue() == true
    return copy(
        value = if (sensitive && value != null) SENSITIVE_REDACTION else value,
        elements = elements.map(ComposeValue::sensitiveValuesRedacted),
    )
}

private fun String.isSensitiveName(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return SENSITIVE_NAMES.any(normalized::contains)
}

private fun String.isSensitiveValue(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("Bearer ", ignoreCase = true) ||
        trimmed.startsWith("Basic ", ignoreCase = true) ||
        JWT.matches(trimmed) ||
        PRIVATE_DEVICE_PATH.containsMatchIn(trimmed)
}

private fun String.sanitizedArtifactSource(): String =
    if (PRIVATE_DEVICE_PATH.containsMatchIn(this) || startsWith('/')) "local-redacted" else this

private const val SENSITIVE_REDACTION = "<redacted:sensitive>"
private val SENSITIVE_NAMES =
    setOf(
        "password",
        "passwd",
        "secret",
        "token",
        "credential",
        "authorization",
        "authentication",
        "cookie",
        "sessionid",
        "apikey",
        "privatekey",
    )
private val JWT = Regex("""^[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}$""")
private val PRIVATE_DEVICE_PATH =
    Regex("""(?:^|[\s\"'=:,(])/(?:data|sdcard|storage|mnt|system|vendor|product|apex)(?:/|$)\S*""")
