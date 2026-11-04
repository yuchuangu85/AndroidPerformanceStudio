package com.androidperformancestudio.desktop

import com.androidperformancestudio.aiplatform.app.AiPlatformRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** Own the runtime continuously, even if its UI is disposed before publication recomposes. */
internal suspend fun runDesktopAiRuntime(
    open: () -> AiPlatformRuntime,
    onReady: (AiPlatformRuntime) -> Unit,
): Nothing {
    val runtime = acquireDesktopAiRuntime(open)
    try {
        onReady(runtime)
        awaitCancellation()
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { runtime.shutdown() }
    }
}

/** A cancelled IO result delivery must not strand the exclusive profile lock. */
internal suspend fun acquireDesktopAiRuntime(open: () -> AiPlatformRuntime): AiPlatformRuntime {
    var acquired: AiPlatformRuntime? = null
    try {
        return withContext(Dispatchers.IO) { open().also { acquired = it } }
    } catch (failure: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                acquired?.shutdown()
            } catch (_: Exception) {
                // Shutdown attempts every resource; preserve the original caller cancellation.
            }
        }
        throw failure
    }
}
