package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.model.NetworkCaptureResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal data class NetworkCaptureCallbacks(
    val onStarted: () -> Unit,
    val onEvents: (Int) -> Unit,
    val onCompleted: (NetworkCaptureResult) -> Unit,
    val onFailure: (Exception) -> Unit,
    val onFinished: () -> Unit,
)

@Suppress("LongParameterList")
internal suspend fun <S : Any> runNetworkCaptureSession(
    start: suspend () -> S,
    poll: suspend (S) -> Int,
    stop: suspend (S) -> NetworkCaptureResult,
    publishResult: () -> Boolean,
    callbacks: NetworkCaptureCallbacks,
    pollIntervalMillis: Long = NETWORK_POLL_INTERVAL_MILLIS,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var session: S? = null
    var failure: Exception? = null
    var recoverableEnd = false
    try {
        // Keep ownership even if cancellation wins while dispatching the acquired session back.
        withContext(ioDispatcher) { session = start() }
        val active = checkNotNull(session)
        callbacks.onStarted()
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMillis)
            val count = withContext(ioDispatcher) { poll(active) }
            callbacks.onEvents(count)
        }
        recoverableEnd = true
    } catch (cancelled: CancellationException) {
        recoverableEnd = true
        throw cancelled
    } catch (error: Exception) {
        recoverableEnd = true
        currentCoroutineContext().ensureActive()
        failure = error
    } finally {
        withContext(NonCancellable) {
            try {
                finishNetworkCaptureSession(session, stop, publishResult, callbacks, ioDispatcher, failure, recoverableEnd)
            } finally {
                callbacks.onFinished()
            }
        }
    }
}

@Suppress("LongParameterList")
private suspend fun <S : Any> finishNetworkCaptureSession(
    session: S?,
    stop: suspend (S) -> NetworkCaptureResult,
    publishResult: () -> Boolean,
    callbacks: NetworkCaptureCallbacks,
    ioDispatcher: CoroutineDispatcher,
    failure: Exception?,
    recoverableEnd: Boolean,
) {
    var error = failure
    var result: NetworkCaptureResult? = null
    try {
        session?.let { active -> result = withContext(ioDispatcher) { stop(active) } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cleanupError: Exception) {
        val original = error
        if (original == null) {
            error = cleanupError
        } else if (original !== cleanupError) {
            original.addSuppressed(cleanupError)
        }
    }
    val reported = error
    if (recoverableEnd && reported != null) {
        callbacks.onFailure(reported)
    } else if (recoverableEnd && publishResult()) {
        result?.let(callbacks.onCompleted)
    }
}

private const val NETWORK_POLL_INTERVAL_MILLIS = 750L
