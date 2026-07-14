package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Path

typealias ProcessInvocation = suspend (ProcessRequest, ProcessCancellationSignal) -> ProcessRunResult
typealias AdbDevicesResult = StudioResult<List<AdbDevice>>

class AdbDeviceRefresher(
    private val adbExecutable: Path,
    private val parser: AdbDevicesParser = AdbDevicesParser(),
    private val processInvocation: ProcessInvocation = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) {
    suspend fun refresh(cancellationSignal: ProcessCancellationSignal = ProcessCancellationSignal()): AdbDevicesResult {
        val request =
            ProcessRequest(
                executable = adbExecutable,
                arguments = listOf("devices", "-l"),
            )
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed -> parser.parse(result.output.stdout.text)
            is ProcessRunResult.Failed -> StudioResult.Failure(result.error)
        }
    }
}
