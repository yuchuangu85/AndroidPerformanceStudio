package com.androidperformancestudio.platform.adb

import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.time.Duration

sealed class AdbException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class AdbNotFoundException(
    message: String = "ADB was not found in the configured Android SDK, environment, or PATH",
) : AdbException(message)

class AdbNotExecutableException(
    val path: Path,
) : AdbException("ADB executable is not usable: $path")

class AdbProcessStartException(
    val command: List<String>,
    cause: Throwable,
) : AdbException("Failed to start ADB command: ${command.joinToString(" ")}", cause)

class AdbCommandTimeoutException(
    val command: List<String>,
    val timeout: Duration,
    val pid: Long = -1,
) : AdbException("ADB command timed out after $timeout: ${command.joinToString(" ")}")

class AdbCommandCancelledException(
    val command: List<String>,
    val pid: Long = -1,
) : CancellationException("ADB command was cancelled: ${command.joinToString(" ")}")

class AdbCommandFailedException(
    val command: List<String>,
    val exitCode: Int,
    val standardError: String,
) : AdbException(
        "ADB command failed with exit code $exitCode: " +
            standardError.trim().ifEmpty { command.joinToString(" ") },
    )

class AdbOutputParseException(
    message: String,
) : AdbException(message)

class AdbInputException(
    message: String,
) : AdbException(message)
