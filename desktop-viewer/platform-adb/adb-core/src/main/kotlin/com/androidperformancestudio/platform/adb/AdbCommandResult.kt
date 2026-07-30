package com.androidperformancestudio.platform.adb

import kotlin.time.Duration

data class AdbTextResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val duration: Duration,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val pid: Long = -1,
) {
    val isSuccess: Boolean
        get() = exitCode == 0

    fun requireSuccess(command: AdbCommand): AdbTextResult =
        apply {
            if (!isSuccess) {
                throw AdbCommandFailedException(command.commandLine, exitCode, stderr.ifBlank { stdout })
            }
        }
}

data class AdbBinaryResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val duration: Duration,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val pid: Long = -1,
) {
    val isSuccess: Boolean
        get() = exitCode == 0

    val stderrText: String
        get() = stderr.toString(Charsets.UTF_8)

    fun requireSuccess(command: AdbCommand): AdbBinaryResult =
        apply {
            if (!isSuccess) {
                throw AdbCommandFailedException(command.commandLine, exitCode, stderrText)
            }
        }

    override fun equals(other: Any?): Boolean =
        other is AdbBinaryResult &&
            exitCode == other.exitCode &&
            stdout.contentEquals(other.stdout) &&
            stderr.contentEquals(other.stderr) &&
            duration == other.duration &&
            stdoutTruncated == other.stdoutTruncated &&
            stderrTruncated == other.stderrTruncated &&
            pid == other.pid

    override fun hashCode(): Int {
        var result = exitCode
        result = 31 * result + stdout.contentHashCode()
        result = 31 * result + stderr.contentHashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + stdoutTruncated.hashCode()
        result = 31 * result + stderrTruncated.hashCode()
        return 31 * result + pid.hashCode()
    }
}
