package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Path

typealias AndroidDevicePropertiesResult = StudioResult<AndroidDeviceProperties>

class AdbDevicePropertiesReader(
    private val adbExecutable: Path,
    private val parser: AndroidGetpropParser = AndroidGetpropParser(),
    private val processInvocation: ProcessInvocation = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) {
    suspend fun read(
        serial: String,
        cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal(),
    ): AndroidDevicePropertiesResult {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell", "getprop"),
            )
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed -> parser.parse(serial, result.output.stdout.text)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }
}
