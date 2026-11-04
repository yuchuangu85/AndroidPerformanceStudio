package com.androidperformancestudio.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An old dialog must not authorize or revoke a request that replaced it during verification. */
internal suspend fun confirmComposeAgentRequest(
    isCurrentRequest: () -> Boolean,
    verifyTarget: suspend () -> Boolean,
    onTargetChanged: () -> Unit,
    onAuthorized: () -> Unit,
) {
    if (!isCurrentRequest()) return
    val targetCurrent = verifyTarget()
    currentCoroutineContext().ensureActive()
    if (!isCurrentRequest()) return
    if (targetCurrent) onAuthorized() else onTargetChanged()
}

/** Tracks ownership separately from completion so cancelling full capture can retry compatibly. */
internal class ManualRefreshRequests {
    private var completedRequest = 0
    private var active: Attempt? = null

    fun begin(request: Int): Attempt? {
        if (request <= completedRequest) return null
        return Attempt(request).also { active = it }
    }

    /** Automatic mode, a different target, or archive import must not replay an old manual click. */
    fun discardThrough(request: Int) {
        completedRequest = maxOf(completedRequest, request)
        active = null
    }

    /** Returns whether the finishing attempt still owns the progress indicator. */
    fun finish(
        attempt: Attempt,
        cancelled: Boolean,
    ): Boolean {
        if (!cancelled) completedRequest = maxOf(completedRequest, attempt.request)
        if (active !== attempt) return false
        active = null
        return true
    }

    class Attempt internal constructor(
        internal val request: Int,
    )
}
