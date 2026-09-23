package com.androidperformancestudio.adb

import com.androidperformancestudio.platform.adb.AdbCommandExecutor
import com.androidperformancestudio.platform.adb.AdbCommandTimeoutException
import com.androidperformancestudio.platform.adb.AdbProcessStartException
import com.androidperformancestudio.platform.adb.DefaultAdbCommandExecutor
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutBytes: ByteArray = stdout.toByteArray(StandardCharsets.UTF_8),
)

fun interface ProcessRunner {
    fun run(arguments: List<String>): ProcessResult
}

/**
 * Layout Inspector compatibility port backed by adb-core's shared execution boundary.
 *
 * Inspector-specific commands continue to come from [AdbCommandFactory], while command execution,
 * timeout handling, and process-start errors are owned by adb-core.
 */
class AdbProcessRunner(
    private val executable: Path = defaultAdbCommandExecutable(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val delegate: AdbCommandExecutor = DefaultAdbCommandExecutor(executable),
) : ProcessRunner {
    override fun run(arguments: List<String>): ProcessResult =
        try {
            val result =
                runBlocking {
                    delegate.executeBinary(
                        arguments = arguments,
                        timeout = timeoutMillis.milliseconds,
                    )
                }
            ProcessResult(
                exitCode = result.exitCode,
                stdout = result.stdout.toString(StandardCharsets.UTF_8),
                stderr = result.stderr.toString(StandardCharsets.UTF_8),
                stdoutBytes = result.stdout,
            )
        } catch (error: AdbCommandTimeoutException) {
            ProcessResult(
                exitCode = TIMEOUT_EXIT_CODE,
                stdout = "",
                stderr = error.message.orEmpty(),
            )
        } catch (error: AdbProcessStartException) {
            ProcessResult(
                exitCode = COMMAND_NOT_FOUND_EXIT_CODE,
                stdout = "",
                stderr = missingExecutableMessage(executable, error.cause?.message),
            )
        }

    companion object {
        const val TIMEOUT_EXIT_CODE = -1
        const val COMMAND_NOT_FOUND_EXIT_CODE = 127
        private const val DEFAULT_TIMEOUT_MILLIS = 15_000L

        private fun missingExecutableMessage(
            executable: Path,
            causeMessage: String?,
        ): String = buildString {
            append("ADB executable not found: $executable. ")
            append("Install Android SDK Platform Tools, set ANDROID_HOME or ANDROID_SDK_ROOT, ")
            append("or add platform-tools to PATH.")
            if (!causeMessage.isNullOrBlank()) append(" Original error: $causeMessage")
        }
    }
}
