package com.androidperformancestudio.desktop

/**
 * Builds the user-facing reason for a capture failure. Wrapped capture errors (e.g.
 * AdbFallbackUnavailableException) often hide the decisive detail in their cause chain,
 * so the deepest cause message is summarized alongside the outer message instead of
 * being dropped.
 */
internal fun Throwable.failureReasonSummary(maxRootCauseLength: Int = DEFAULT_ROOT_CAUSE_LENGTH): String {
    val base = message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    val rootCause = deepestCause()
    val rootMessage = rootCause
        ?.takeIf { it !== this }
        ?.message?.lineSequence()?.firstOrNull()
        ?.takeIf(String::isNotBlank)
        ?.take(maxRootCauseLength)
        ?.toString()
        ?: return base
    return if (base.contains(rootMessage)) base else "$base (caused by: $rootMessage)"
}

private fun Throwable.deepestCause(): Throwable? {
    var cause = cause ?: return null
    var guard = 0
    while (cause.cause != null && cause.cause !== cause && guard < CAUSE_CHAIN_LIMIT) {
        cause = checkNotNull(cause.cause)
        guard += 1
    }
    return cause
}

private const val DEFAULT_ROOT_CAUSE_LENGTH = 200
private const val CAUSE_CHAIN_LIMIT = 16
