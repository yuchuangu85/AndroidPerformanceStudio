package com.androidperformancestudio.toolchain

import com.androidperformancestudio.model.ErrorCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class JvmProcessRunnerTest {
    private val runner = JvmProcessRunner()

    @Test
    fun `drains stdout and stderr concurrently with bounded capture`() =
        runBlocking {
            val result =
                runner.run(
                    javaRequest("flood").copy(
                        timeout = 10.seconds,
                        maxCapturedCharactersPerStream = 4_096,
                    ),
                )

            val completed = assertIs<ProcessRunResult.Completed>(result)
            assertEquals(0, completed.output.exitCode)
            val stdout = completed.output.stdout
            val stderr = completed.output.stderr
            assertTrue(stdout.text.startsWith("stdout-0"))
            assertTrue(stderr.text.startsWith("stderr-0"))
            assertTrue(completed.output.stdout.truncated)
            assertTrue(completed.output.stderr.truncated)
        }

    @Test
    fun `classifies nonzero exit and preserves command output`() =
        runBlocking {
            val result = runner.run(javaRequest("exit", "7"))

            val failed = assertIs<ProcessRunResult.Failed>(result)
            assertEquals(ErrorCategory.PROCESS_EXIT, failed.error.category)
            val output = assertNotNull(failed.output)
            assertEquals(7, output.exitCode)
            assertTrue(output.stdout.text.contains("before-exit"))
            assertTrue(output.stderr.text.contains("exit-code-7"))
        }

    @Test
    fun `times out and terminates the process`() =
        runBlocking {
            val result = runner.run(javaRequest("sleep", "30000").copy(timeout = 150.milliseconds))

            val failed = assertIs<ProcessRunResult.Failed>(result)
            assertEquals(ErrorCategory.PROCESS_TIMEOUT, failed.error.category)
            assertFalse(isAlive(failed.output?.pid))
        }

    @Test
    fun `explicit cancellation terminates the process and returns structured failure`() =
        runBlocking {
            val pidFile = Files.createTempFile("aps-process-pid-", ".txt")
            pidFile.deleteIfExists()
            try {
                val cancellation = ProcessCancellationSignal()
                val deferred =
                    async {
                        runner.run(
                            javaRequest("write-pid-sleep", pidFile.toString()).copy(timeout = 10.seconds),
                            cancellation,
                        )
                    }
                val pid = waitForPid(pidFile)

                cancellation.cancel()

                val failed = assertIs<ProcessRunResult.Failed>(deferred.await())
                assertEquals(ErrorCategory.PROCESS_CANCELLED, failed.error.category)
                assertEquals(pid, failed.output?.pid)
                assertFalse(isAlive(pid))
            } finally {
                pidFile.deleteIfExists()
            }
        }

    private fun javaRequest(vararg fixtureArguments: String): ProcessRequest =
        ProcessRequest(
            executable = javaExecutable(),
            arguments =
                listOf(
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessFixtureMain::class.qualifiedName.orEmpty(),
                ) + fixtureArguments,
            timeout = 5.seconds,
        )

    private fun javaExecutable(): Path {
        val executable = if (System.getProperty("os.name").contains("windows", true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executable)
    }

    private suspend fun waitForPid(pidFile: Path): Long {
        repeat(WAIT_ATTEMPTS) {
            val pid =
                if (pidFile.exists()) {
                    runCatching { pidFile.readText().trim().toLongOrNull() }.getOrNull()
                } else {
                    null
                }
            if (pid != null) return pid
            delay(WAIT_INTERVAL)
        }
        error("PID file wasn't populated before timeout")
    }

    private fun isAlive(pid: Long?): Boolean {
        if (pid == null) return false
        val process = ProcessHandle.of(pid)
        return process.isPresent && process.get().isAlive
    }

    companion object {
        private const val WAIT_ATTEMPTS = 100
        private val WAIT_INTERVAL = 25.milliseconds
    }
}
