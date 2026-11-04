package com.androidperformancestudio.desktop

import kotlinx.coroutines.CancellationException

/** Capture recovery must not swallow cancellation or try another capture after a fatal JVM error. */
internal inline fun <T> runInspectorCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
