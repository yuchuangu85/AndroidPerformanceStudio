package com.androidperformancestudio.visualization

/** A deterministic drag session that deduplicates preview queries and never commits after cancellation. */
class TimelineRangeInteraction(
    private val viewport: TimeViewport,
    private val widthPx: Float,
) {
    private var startPx: Float? = null
    private var endPx: Float? = null
    private var lastPublished: TimeViewport? = null
    private var cancelled = false

    var preview: TimeViewport? = null
        private set

    fun start(positionPx: Float): TimeViewport? {
        if (!positionPx.isFinite() || !widthPx.isFinite() || widthPx <= 0f) return null
        cancelled = false
        startPx = positionPx
        endPx = positionPx
        return publish(positionPx)
    }

    fun drag(positionPx: Float): TimeViewport? {
        if (startPx == null || cancelled || !positionPx.isFinite()) return null
        endPx = positionPx
        return publish(positionPx)
    }

    fun commit(): TimeViewport? {
        val first = startPx
        val last = endPx
        val result =
            if (canCommit(first, last)) {
                viewport.selection(requireNotNull(first), requireNotNull(last), widthPx)
            } else {
                null
            }
        reset()
        return result
    }

    fun cancel() {
        cancelled = true
        reset(keepCancelled = true)
    }

    private fun canCommit(
        first: Float?,
        last: Float?,
    ): Boolean = !cancelled && first != null && last != null && widthPx > 0f

    private fun publish(positionPx: Float): TimeViewport? {
        val first = startPx ?: return null
        val next = viewport.selection(first, positionPx, widthPx)
        preview = next
        return next.takeUnless { it == lastPublished }.also { published ->
            if (published != null) lastPublished = published
        }
    }

    private fun reset(keepCancelled: Boolean = false) {
        startPx = null
        endPx = null
        preview = null
        lastPublished = null
        if (!keepCancelled) cancelled = false
    }
}
