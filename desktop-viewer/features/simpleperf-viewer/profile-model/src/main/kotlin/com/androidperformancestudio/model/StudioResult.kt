package com.androidperformancestudio.model

enum class ErrorCategory {
    CONFIGURATION,
    PROCESS_START,
    PROCESS_TIMEOUT,
    PROCESS_CANCELLED,
    PROCESS_EXIT,
    IO,
    DATA_VALIDATION,
    UNSUPPORTED_PLATFORM,
    UNKNOWN,
}

data class StudioError(
    val category: ErrorCategory,
    val code: String,
    val message: String,
    val cause: Throwable? = null,
)

sealed interface StudioResult<out T> {
    data class Success<T>(
        val value: T,
    ) : StudioResult<T>

    data class Failure(
        val error: StudioError,
    ) : StudioResult<Nothing>
}
