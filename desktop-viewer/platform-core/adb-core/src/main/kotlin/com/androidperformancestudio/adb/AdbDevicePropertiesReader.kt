package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import java.nio.file.Path

typealias AndroidDevicePropertiesResult = StudioResult<AndroidDeviceProperties>

class AdbDevicePropertiesReader(
    private val adbExecutable: Path,
    private val parser: AndroidGetpropParser = AndroidGetpropParser(),
    private val processInvocation: ProcessInvocation = { request, signal ->
        StudioHostProcessExecutor().run(request, signal)
    },
) {
    suspend fun read(
        serial: String,
        cancellationSignal: HostCancellationSignal = HostCancellationSignal(),
    ): AndroidDevicePropertiesResult {
        val request =
            HostProcessRequest(
                executable = adbExecutable,
                arguments = listOf("-s", serial, "shell", "getprop"),
            )
        return when (val result = processInvocation(request, cancellationSignal)) {
            is HostCommandResult.Completed -> parser.parse(serial, result.output.stdout.text)
            is HostCommandResult.Failed -> StudioResult.Failure(result.error)
        }
    }
}
