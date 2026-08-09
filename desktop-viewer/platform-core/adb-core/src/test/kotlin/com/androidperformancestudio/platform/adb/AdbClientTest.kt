package com.androidperformancestudio.platform.adb

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
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class AdbClientTest {
    @Test
    fun `builds shell exec-out and forwarding arguments without a shell`() =
        runBlocking {
            val runner = RecordingRunner()
            val client = DefaultAdbClient(Path.of("/sdk/adb"), runner)

            client.shell("emulator-5554", listOf("getprop", "ro.build.type"))
            client.execOut("emulator-5554", listOf("screencap", "-p"))
            client.forward("emulator-5554", "tcp:39123", "localabstract:agentperf")
            client.removeForward("emulator-5554", "tcp:39123")

            assertEquals(
                listOf(
                    listOf("-s", "emulator-5554", "shell", "getprop", "ro.build.type"),
                    listOf("-s", "emulator-5554", "exec-out", "screencap", "-p"),
                    listOf("-s", "emulator-5554", "forward", "tcp:39123", "localabstract:agentperf"),
                    listOf("-s", "emulator-5554", "forward", "--remove", "tcp:39123"),
                ),
                runner.commands.map(HostProcessRequest::arguments),
            )
        }

    @Test
    fun `rejects unsafe serial and remote path before execution`() =
        runBlocking {
            val runner = RecordingRunner()
            val client = DefaultAdbClient(Path.of("/sdk/adb"), runner)

            assertFailsWith<AdbInputException> {
                client.shell("device; reboot", listOf("id"))
            }
            assertFailsWith<AdbInputException> {
                client.pull("device", "/data/local/tmp/\u0000trace", Path.of("trace"))
            }
            assertEquals(emptyList(), runner.commands)
        }

    private class RecordingRunner : HostProcessRunner {
        val commands = mutableListOf<HostProcessRequest>()

        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
            commands += request
            return HostProcessTextResult(-1, 0, "", "", Duration.ZERO, false, false)
        }

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult {
            commands += request
            return HostProcessBinaryResult(-1, 0, byteArrayOf(), byteArrayOf(), Duration.ZERO, false, false)
        }

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess =
            error("not used")
    }
}
