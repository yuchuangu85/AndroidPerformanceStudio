package com.androidperformancestudio.platform.adb

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerTest {
    @Test
    fun `captures text stdout and stderr separately`() =
        runBlocking {
            val result =
                JvmProcessRunner().executeText(
                    command("/bin/sh", "-c", "printf stdout; printf stderr >&2"),
                )

            assertEquals(0, result.exitCode)
            assertEquals("stdout", result.stdout)
            assertEquals("stderr", result.stderr)
        }

    @Test
    fun `binary output is not decoded and re-encoded`() =
        runBlocking {
            val result =
                JvmProcessRunner().executeBinary(
                    command("/usr/bin/printf", "\\377\\000PNG"),
                )

            assertContentEquals(
                byteArrayOf(0xff.toByte(), 0x00, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                result.stdout,
            )
        }

    @Test
    fun `timeout terminates the process`() =
        runBlocking {
            val started = System.nanoTime()

            assertFailsWith<AdbCommandTimeoutException> {
                JvmProcessRunner().executeText(
                    AdbCommand(
                        executable = Path.of("/bin/sleep"),
                        arguments = listOf("5"),
                        timeout = 50.milliseconds,
                    ),
                )
            }

            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        }

    @Test
    fun `coroutine cancellation destroys the process`() =
        runBlocking {
            val operation =
                async {
                    JvmProcessRunner().executeText(
                        AdbCommand(
                            executable = Path.of("/bin/sleep"),
                            arguments = listOf("5"),
                            timeout = 10.seconds,
                        ),
                    )
                }
            delay(50)
            operation.cancel()

            assertFailsWith<AdbCommandCancelledException> { operation.await() }
        }

    private fun command(
        executable: String,
        vararg arguments: String,
    ): AdbCommand =
        AdbCommand(
            executable = Path.of(executable),
            arguments = arguments.toList(),
        )
}
