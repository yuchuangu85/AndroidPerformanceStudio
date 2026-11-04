package com.androidperformancestudio.compose.inspection.host

/** Limits retained detail objects in addition to the transport's encoded-message limit. */
data class ComposeDetailLimits(
    val maxGroups: Int = 10_000,
    val maxValues: Int = 100_000,
    val maxDepth: Int = 16,
    val maxTextChars: Int = 4 * 1024 * 1024,
) {
    init {
        require(maxGroups >= 0 && maxValues >= 0 && maxDepth >= 0 && maxTextChars >= 0)
    }
}

/** One capture's mutable budget, shared explicitly when that capture contains multiple window roots. */
class ComposeDetailBudget internal constructor(internal val limits: ComposeDetailLimits) {
    private var remainingGroups = limits.maxGroups
    private var remainingValues = limits.maxValues
    internal var remainingTextChars = limits.maxTextChars
        private set

    internal var exhausted: Boolean = false
        private set

    internal var groupsOmitted: Boolean = false
        private set

    internal fun takeGroup(): Boolean {
        if (remainingGroups == 0) {
            groupsOmitted = true
            reachedLimit()
            return false
        }
        remainingGroups--
        return true
    }

    internal fun takeValue(): Boolean {
        if (remainingValues == 0) {
            reachedLimit()
            return false
        }
        remainingValues--
        return true
    }

    internal fun takeText(text: String): String {
        val retained = text.take(minOf(remainingTextChars, MAX_STRING_CHARS))
        remainingTextChars -= retained.length
        if (retained.length < text.length) reachedLimit()
        return retained
    }

    internal fun reachedLimit() {
        exhausted = true
    }

    internal companion object {
        const val MAX_STRING_CHARS = 64 * 1024
    }
}
