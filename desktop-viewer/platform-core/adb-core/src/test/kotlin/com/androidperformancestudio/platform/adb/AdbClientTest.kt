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
import kotlin.test.assertIs
import kotlin.time.Duration

class AdbClientTest {
    @Test
    fun `discovers immutable device targets through the shared client`() =
        runBlocking {
            val runner =
                RecordingRunner(
                    textStdout =
                        "List of devices attached\n" +
                            "emulator-5554 device product:sdk model:Pixel_8 transport_id:1\n",
                )

            val target: DeviceTarget = DefaultAdbClient(Path.of("/sdk/adb"), runner).listDevices().single()

            assertIs<DeviceTarget>(target)
            assertEquals("emulator-5554", target.serial)
            assertEquals(AdbDeviceState.ONLINE, target.state)
        }

    @Test
    fun `preserves shell arguments and builds exec-out and forwarding arguments`() =
        runBlocking {
            val runner = RecordingRunner()
            val client = DefaultAdbClient(Path.of("/sdk/adb"), runner)

            client.shell("emulator-5554", listOf("getprop", "ro.build.type"))
            client.shell("emulator-5554", listOf("sh", "-c", "cat config | perfetto -c - --background-wait"))
            client.execOut("emulator-5554", listOf("screencap", "-p"))
            client.forward("emulator-5554", "tcp:39123", "localabstract:agentperf")
            client.removeForward("emulator-5554", "tcp:39123")

            assertEquals(
                listOf(
                    listOf("-s", "emulator-5554", "shell", "'getprop'", "'ro.build.type'"),
                    listOf(
                        "-s",
                        "emulator-5554",
                        "shell",
                        "'sh'",
                        "'-c'",
                        "'cat config | perfetto -c - --background-wait'",
                    ),
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

    @Test
    fun `propagates capture cancellation and output bounds through the typed boundary`() =
        runBlocking {
            val runner = RecordingRunner()
            val client = DefaultAdbClient(Path.of("/sdk/adb"), runner)

            client.shell(
                serial = "emulator-5554",
                arguments = listOf("id"),
                maxOutputBytesPerStream = 1024,
                isCancellationRequested = { true },
            )

            val request = runner.commands.single()
            assertEquals(1024, request.maxOutputBytesPerStream)
            assertEquals(true, request.isCancellationRequested())
        }

    private class RecordingRunner(
        private val textStdout: String = "",
    ) : HostProcessRunner {
        val commands = mutableListOf<HostProcessRequest>()

        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
            commands += request
            return HostProcessTextResult(-1, 0, textStdout, "", Duration.ZERO, false, false)
        }

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult {
            commands += request
            return HostProcessBinaryResult(-1, 0, byteArrayOf(), byteArrayOf(), Duration.ZERO, false, false)
        }

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess =
            error("not used")
    }
}

private class AdbCoreMigrationTest {
    @Test
    fun `generic feature command executor keeps host execution inside adb core`() =
        runBlocking {
            val runner = AdbClientTestRecordingRunner(textStdout = "ok")
            val executor = DefaultAdbCommandExecutor(Path.of("/sdk/adb"), runner)

            val result = executor.executeText(listOf("-s", "serial-1", "shell", "run-as", "dev.example", "pwd"))

            assertEquals("ok", result.stdout)
            assertEquals(
                listOf("-s", "serial-1", "shell", "run-as", "dev.example", "pwd"),
                runner.commands.single().arguments,
            )
        }

    @Test
    fun `shared property client uses the precise adb core getprop command`() =
        runBlocking {
            val runner = AdbClientTestRecordingRunner(textStdout = "35\n")
            val properties = com.androidperformancestudio.adb.AndroidDevicePropertyClient(
                DefaultAdbClient(Path.of("/sdk/adb"), runner),
            )

            val result = properties.sdkInt("serial-1")

            assertEquals(35, (result as com.androidperformancestudio.model.StudioResult.Success).value)
            assertEquals(
                listOf("-s", "serial-1", "shell", "'getprop'", "'ro.build.version.sdk'"),
                runner.commands.single().arguments,
            )
        }

    @Test
    fun `shared command arguments validate and construct generic forwarding operations`() {
        assertEquals(
            listOf("-s", "serial-1", "forward", "tcp:0", "tcp:48123"),
            AdbCommandArguments.forward("serial-1", "tcp:0", "tcp:48123"),
        )
        assertEquals(
            listOf("-s", "serial-1", "forward", "--remove", "tcp:48123"),
            AdbCommandArguments.removeForward("serial-1", "tcp:48123"),
        )
        assertFailsWith<AdbInputException> {
            AdbCommandArguments.pidOf("serial-1", "dev.example; rm -rf /")
        }
    }
}

private class AdbClientTestRecordingRunner(
    private val textStdout: String,
) : HostProcessRunner {
    val commands = mutableListOf<HostProcessRequest>()

    override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
        commands += request
        return HostProcessTextResult(-1, 0, textStdout, "", Duration.ZERO, false, false)
    }

    override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("not used")

    override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
}
