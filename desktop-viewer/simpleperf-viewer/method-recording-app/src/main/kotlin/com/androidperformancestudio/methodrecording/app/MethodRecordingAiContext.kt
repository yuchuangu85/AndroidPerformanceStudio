package com.androidperformancestudio.methodrecording.app

import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiEvidenceId
import com.androidperformancestudio.ui.uiAiPayload

private const val MAX_PAYLOAD_BYTES = 8 * 1024
private const val MAX_TOP_METHODS = 8
private const val MAX_SYMBOL_CHARS = 128
private const val MAX_LIBRARY_CHARS = 64
private const val EVIDENCE_KIND = "method-recording-summary"

/** Builds bounded, privacy-safe facts for the method-recording AI entry point. */
internal fun methodRecordingAiContext(state: MethodRecordingState): UiAiContext? {
    val analysis = state.analysis ?: return null
    if (state.isLoading || state.capturePhase is MethodTraceCapturePhase.Recording || analysis.events.size == 0) return null

    val payload = boundedMethodRecordingPayload(state)
    val evidenceId =
        uiAiEvidenceId(
            EVIDENCE_KIND,
            analysis.header.startTimeNanos.toString(),
            payload,
        )
    return UiAiContext(
        key = "method-recording",
        title = "Method recording evidence",
        currentSelection = false,
        evidence =
            listOf(
                UiAiEvidence(
                    id = evidenceId,
                    kind = EVIDENCE_KIND,
                    summary = "ART method trace summary; timing is recorded method measurement, not scheduling or ANR causality",
                    payloadJson = payload,
                ),
            ),
    )
}

private fun boundedMethodRecordingPayload(state: MethodRecordingState): String {
    val available = state.topMethods.size
    var included = minOf(available, MAX_TOP_METHODS)
    while (true) {
        val payload = methodRecordingPayload(state, included)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
        if (payloadBytes <= MAX_PAYLOAD_BYTES || included == 0) {
            require(payloadBytes <= MAX_PAYLOAD_BYTES) {
                "Method recording AI evidence exceeds the UTF-8 payload budget"
            }
            return payload
        }
        included--
    }
}

private fun methodRecordingPayload(
    state: MethodRecordingState,
    included: Int,
): String {
    val analysis = requireNotNull(state.analysis)
    val methods = state.topMethods.take(included)
    val omitted = (state.topMethods.size - included).coerceAtLeast(0)
    return uiAiPayload(
        "schemaVersion" to 1,
        "scope" to "RECORDING_SUMMARY",
        "measurement" to
            "Recorded method enter/exit intervals on the normalized event timeline, not statistical samples or pure CPU execution time. " +
            "No scheduling, blocking, jank, or ANR causality is established.",
        "timeUnit" to "microseconds",
        "selfSemantics" to "Exclusive leaf-method interval time",
        "totalSemantics" to "Inclusive stack-method interval time; nested methods and threads can overlap, so totals are not additive",
        "callCountSemantics" to "Recorded ENTER event count, not interval sample count",
        "captureRange" to
            mapOf(
                "timeUnit" to "nanoseconds",
                "timeDomain" to "NORMALIZED_EVENT_TIMELINE",
                "startNanos" to analysis.startTimeNanos,
                "endNanos" to analysis.endTimeNanos,
            ),
        "traceVersion" to analysis.header.version,
        "clockSource" to analysis.header.clockSource.name,
        "eventCount" to analysis.events.size,
        "methodCount" to analysis.methods.size,
        "threadCount" to analysis.threads.size,
        "warningCount" to analysis.warnings.size,
        "topMethodCoverage" to "LOADED_TOP_METHODS_ONLY",
        "topMethodLimit" to MAX_TOP_METHODS,
        "topMethodCount" to state.topMethods.size,
        "includedTopMethodCount" to included,
        "omittedTopMethodCount" to omitted,
        "budgetOmittedTopMethodCount" to (minOf(state.topMethods.size, MAX_TOP_METHODS) - included),
        "topMethods" to
            methods.map { method ->
                val rawLibrary = method.resource.substringAfterLast('/').substringAfterLast('\\')
                mapOf(
                    "symbol" to method.symbolName.boundedLabel(MAX_SYMBOL_CHARS),
                    "library" to rawLibrary.boundedLabel(MAX_LIBRARY_CHARS),
                    "selfMicros" to method.selfMicros,
                    "totalMicros" to method.totalMicros,
                    "callCount" to method.callCount,
                    "threadCount" to method.threadCount,
                    "symbolTruncated" to (method.symbolName.length > MAX_SYMBOL_CHARS),
                    "libraryTruncated" to (rawLibrary.length > MAX_LIBRARY_CHARS),
                )
            },
    )
}

/** Avoid splitting supplementary characters at the label boundary. */
private fun String.boundedLabel(limit: Int): String =
    if (length <= limit) this else take(if (this[limit - 1].isHighSurrogate()) limit - 1 else limit)
