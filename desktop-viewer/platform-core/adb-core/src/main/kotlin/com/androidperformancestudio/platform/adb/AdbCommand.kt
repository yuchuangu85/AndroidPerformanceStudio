package com.androidperformancestudio.platform.adb

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AdbCommand(
    val executable: Path,
    val arguments: List<String>,
    val timeout: Duration = 30.seconds,
    val maxOutputBytesPerStream: Int = DEFAULT_MAX_OUTPUT_BYTES,
    val isCancellationRequested: () -> Boolean = { false },
    val workingDirectory: Path? = null,
    val environmentOverrides: Map<String, String> = emptyMap(),
    val charset: Charset = StandardCharsets.UTF_8,
) {
    init {
        require(timeout.isPositive() && timeout.isFinite()) {
            "timeout must be positive and finite"
        }
        require(maxOutputBytesPerStream > 0) {
            "maxOutputBytesPerStream must be positive"
        }
    }

    val commandLine: List<String> = listOf(executable.toString()) + arguments

    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 16 * 1024 * 1024
    }
}
