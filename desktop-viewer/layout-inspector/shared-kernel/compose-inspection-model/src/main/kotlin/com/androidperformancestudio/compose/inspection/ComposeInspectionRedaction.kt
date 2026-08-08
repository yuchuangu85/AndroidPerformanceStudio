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

private fun ComposeValue.redacted(): ComposeValue = copy(
    value = value?.let { "<redacted>" },
    elements = elements.map(ComposeValue::redacted),
)
