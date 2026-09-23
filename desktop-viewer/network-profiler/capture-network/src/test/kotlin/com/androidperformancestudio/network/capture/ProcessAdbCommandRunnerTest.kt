package com.androidperformancestudio.network.capture

import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

class CoreNetworkAdbGatewayTest {
    @Test
    fun `network gateway delegates run as and forwarding through adb core`() =
        runBlocking {
            val runner = RecordingRunner()
            val gateway = CoreNetworkAdbGateway(DefaultAdbClient(Path.of("/sdk/adb"), runner))

            gateway.readAgentToken("serial-1", "dev.example.app")
            gateway.allocateForward("serial-1", 48123)
            gateway.removeForward("serial-1", 43123)

            assertEquals(
                listOf(
                    listOf(
                        "-s",
                        "serial-1",
                        "shell",
                        "'run-as'",
                        "'dev.example.app'",
                        "'cat'",
                        "'files/aps-network/token'",
                    ),
                    listOf("-s", "serial-1", "forward", "tcp:0", "tcp:48123"),
                    listOf("-s", "serial-1", "forward", "--remove", "tcp:43123"),
                ),
                runner.commands.map(HostProcessRequest::arguments),
            )
        }

    private class RecordingRunner : HostProcessRunner {
        val commands = mutableListOf<HostProcessRequest>()

        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
            commands += request
            return HostProcessTextResult(-1, 0, "43123\n", "", Duration.ZERO, false, false)
        }

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("not used")

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
    }
}
