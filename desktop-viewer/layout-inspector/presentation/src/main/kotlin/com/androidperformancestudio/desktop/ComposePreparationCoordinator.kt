package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.host.PreparedComposeInspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal class ComposePreparationCoordinator(
    private val prepare: suspend (serial: String, packageName: String) -> PreparedComposeInspection,
    private val nanoTime: () -> Long = System::nanoTime,
    retryDelayMillis: Long = 5000L,
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private val retryDelayNanos = TimeUnit.MILLISECONDS.toNanos(retryDelayMillis.coerceAtLeast(0L))
    private var generation = Any()
    private var completed: Completed? = null

    suspend fun prepare(
        serial: String,
        packageName: String,
        forceRetry: Boolean = false,
    ): Result<PreparedComposeInspection> {
        val target = Target(serial, packageName)
        val (requestedGeneration, observedResult) = synchronized(stateLock) { generation to completed }
        return mutex.withLock {
            val context = currentCoroutineContext()
            synchronized(stateLock) {
                context.ensureActive()
                ensureCurrent(requestedGeneration)
                completed
                    ?.takeIf {
                        // A result completed while this caller waited satisfies even a forced retry.
                        it.target == target &&
                            (
                                it !== observedResult ||
                                    (!forceRetry && nanoTime() - it.completedAtNanos < retryDelayNanos)
                            )
                    }?.let { return@withLock it.result }
                completed = null
            }

            val result = runInspectorCatching { prepare.invoke(serial, packageName) }
            synchronized(stateLock) {
                context.ensureActive()
                ensureCurrent(requestedGeneration)
                completed = Completed(target, result, nanoTime())
            }
            result
        }
    }

    fun invalidate() {
        synchronized(stateLock) {
            generation = Any()
            completed = null
        }
    }

    suspend fun prepareForCapture(
        serial: String,
        packageName: String,
        forceRetry: Boolean = false,
    ): Result<PreparedComposeInspection>? =
        try {
            prepare(serial, packageName, forceRetry)
        } catch (_: ComposePreparationInvalidatedException) {
            currentCoroutineContext().ensureActive()
            if (forceRetry) throw InspectorCaptureTargetChangedException()
            null
        }

    private fun ensureCurrent(requestedGeneration: Any) {
        if (generation !== requestedGeneration) throw ComposePreparationInvalidatedException()
    }

    private data class Target(
        val serial: String,
        val packageName: String,
    )

    private data class Completed(
        val target: Target,
        val result: Result<PreparedComposeInspection>,
        val completedAtNanos: Long,
    )
}

internal class ComposePreparationInvalidatedException : CancellationException("Compose preparation invalidated")
