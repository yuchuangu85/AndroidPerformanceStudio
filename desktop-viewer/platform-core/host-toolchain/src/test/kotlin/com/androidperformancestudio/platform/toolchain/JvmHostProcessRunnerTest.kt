package com.androidperformancestudio.platform.toolchain

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class JvmHostProcessRunnerTest {
    private val runner = JvmHostProcessRunner()

    @Test
    fun `keeps text streams separate and binary output untouched`() =
        runBlocking {
            val text = runner.executeText(request("streams"))
            val binary = runner.executeBinary(request("binary"))

            assertEquals("stdout", text.stdout)
            assertEquals("stderr", text.stderr)
            assertContentEquals(
                byteArrayOf(0xff.toByte(), 0, 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
                binary.stdout,
            )
        }

    @Test
    fun `applies output limits environment and working directory`() =
        runBlocking {
            val workingDirectory = Files.createTempDirectory("aps-host-runner-")
            try {
                val context =
                    runner.executeText(
                        request("context").copy(
                            workingDirectory = workingDirectory,
                            environmentOverrides = mapOf("APS_TEST_VALUE" to "present"),
                        ),
                    )
                val bounded = runner.executeBinary(request("flood").copy(maxOutputBytesPerStream = 32))

                assertEquals("present|${workingDirectory.toRealPath()}", context.stdout)
                assertEquals(0, context.exitCode)
                assertTrue(context.duration.isPositive())
                assertEquals(32, bounded.stdout.size)
                assertTrue(bounded.stdoutTruncated)
            } finally {
                workingDirectory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `returns nonzero exit status and actionable start failure`() =
        runBlocking {
            assertEquals(7, runner.executeText(request("exit", "7")).exitCode)

            val missing = Path.of("missing-host-tool-${System.nanoTime()}")
            val failure =
                assertFailsWith<HostProcessStartException> {
                    runner.executeText(HostProcessRequest(executable = missing))
                }
            assertEquals(listOf(missing.toString()), failure.command)
        }

    @Test
    fun `timeout terminates the full process tree`() =
        runBlocking {
            val pidFile = Files.createTempFile("aps-child-pid-", ".txt")
            pidFile.deleteIfExists()
            try {
                assertFailsWith<HostProcessTimeoutException> {
                    runner.executeText(request("spawn-child", pidFile.toString()).copy(timeout = 500.milliseconds))
                }

                val childPid = waitForPid(pidFile)
                assertFalse(isAlive(childPid))
            } finally {
                pidFile.deleteIfExists()
            }
        }

    @Test
    fun `coroutine and explicit cancellation terminate the process`() =
        runBlocking {
            val pidFile = Files.createTempFile("aps-process-pid-", ".txt")
            pidFile.deleteIfExists()
            try {
                val coroutineRun =
                    async {
                        runner.executeText(request("spawn-child", pidFile.toString()).copy(timeout = 30.seconds))
                    }
                val childPid = waitForPid(pidFile)
                coroutineRun.cancel()
                assertFailsWith<CancellationException> { coroutineRun.await() }
                assertFalse(isAlive(childPid))
            } finally {
                pidFile.deleteIfExists()
            }

            var cancelled = false
            val explicitRun =
                async {
                    runner.executeText(
                        request("sleep").copy(
                            timeout = 30.seconds,
                            isCancellationRequested = { cancelled },
                        ),
                    )
                }
            delay(100)
            cancelled = true
            assertFailsWith<HostProcessCancelledException> { explicitRun.await() }
            Unit
        }

    @Test
    fun `managed process termination closes the full process tree`() =
        runBlocking {
            val pidFile = Files.createTempFile("aps-managed-child-pid-", ".txt")
            val logFile = Files.createTempFile("aps-managed-process-", ".log")
            pidFile.deleteIfExists()
            try {
                val process =
                    runner.launch(
                        HostProcessLaunchRequest(
                            executable = javaExecutable(),
                            arguments = request("spawn-child", pidFile.toString()).arguments,
                            outputFile = logFile,
                        ),
                    )
                val childPid = waitForPid(pidFile)

                process.close()

                assertFalse(process.isAlive)
                assertFalse(isAlive(childPid))
            } finally {
                pidFile.deleteIfExists()
                logFile.deleteIfExists()
            }
        }

    private fun request(
        vararg fixtureArguments: String,
    ): HostProcessRequest =
        HostProcessRequest(
            executable = javaExecutable(),
            arguments =
                listOf(
                    "-cp",
                    System.getProperty("java.class.path"),
                    HostProcessFixture::class.qualifiedName.orEmpty(),
                ) + fixtureArguments,
            timeout = 5.seconds,
        )

    private fun javaExecutable(): Path {
        val name = if (System.getProperty("os.name").contains("windows", true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", name)
    }

    private suspend fun waitForPid(pidFile: Path): Long {
        repeat(100) {
            if (pidFile.exists()) {
                pidFile.readText().trim().toLongOrNull()?.let { return it }
            }
            delay(25.milliseconds)
        }
        error("child PID was not written")
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
}
