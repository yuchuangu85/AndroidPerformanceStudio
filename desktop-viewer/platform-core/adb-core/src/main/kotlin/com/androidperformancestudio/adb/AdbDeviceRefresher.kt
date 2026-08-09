package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import java.nio.file.Path

typealias ProcessInvocation = suspend (HostProcessRequest, HostCancellationSignal) -> HostCommandResult
typealias AdbDevicesResult = StudioResult<List<AdbDevice>>

class AdbDeviceRefresher(
    private val adbExecutable: Path,
    private val parser: StudioAdbDevicesParser = StudioAdbDevicesParser(),
    private val processInvocation: ProcessInvocation = { request, signal ->
        StudioHostProcessExecutor().run(request, signal)
    },
) {
    suspend fun refresh(cancellationSignal: HostCancellationSignal = HostCancellationSignal()): AdbDevicesResult {
        val request =
            HostProcessRequest(
                executable = adbExecutable,
                arguments = listOf("devices", "-l"),
            )
        return when (val result = processInvocation(request, cancellationSignal)) {
            is HostCommandResult.Completed -> parser.parse(result.output.stdout.text)
            is HostCommandResult.Failed -> StudioResult.Failure(result.error)
        }
    }
}
