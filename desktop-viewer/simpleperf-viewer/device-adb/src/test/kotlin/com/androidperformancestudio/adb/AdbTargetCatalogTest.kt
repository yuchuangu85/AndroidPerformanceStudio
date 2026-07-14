package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.CapturedProcessText
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessOutput
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AdbTargetCatalogTest {
    @Test
    fun `refreshes packages and running processes for the selected device`() =
        runBlocking {
            val requests = mutableListOf<ProcessRequest>()
            val signal = ProcessCancellationSignal()
            val catalog =
                AdbTargetCatalog(Path.of("adb")) { request, receivedSignal ->
                    assertSame(signal, receivedSignal)
                    requests += request
                    when (requests.size) {
                        1 ->
                            completed(
                                request,
                                """
                                package:com.example.zeta
                                package:com.example.alpha
                                package:com.example.alpha
                                """.trimIndent(),
                            )
                        else ->
                            completed(
                                request,
                                """
                                PID   PPID USER      NAME
                                101   1    u0_a123   com.example.alpha
                                202   1    root      surfaceflinger
                                """.trimIndent(),
                            )
                    }
                }

            val result = assertIs<StudioResult.Success<AdbTargetSnapshot>>(catalog.refresh("serial-1", signal)).value

            assertEquals(
                listOf(
                    listOf("-s", "serial-1", "shell", "cmd", "package", "list", "packages"),
                    listOf("-s", "serial-1", "shell", "ps", "-A", "-o", "PID,PPID,USER,NAME"),
                ),
                requests.map(ProcessRequest::arguments),
            )
            assertEquals(
                listOf("com.example.alpha", "com.example.zeta"),
                result.packages.map(AndroidPackage::packageName),
            )
            assertEquals(listOf(101, 202), result.processes.map(AndroidProcess::pid))
            assertEquals("u0_a123", result.processes.first().user)
            assertEquals("com.example.alpha", result.processes.first().name)
        }

    @Test
    fun `searches packages processes and pids without case sensitivity`() {
        val snapshot =
            AdbTargetSnapshot(
                packages = listOf(AndroidPackage("com.example.Camera"), AndroidPackage("com.example.music")),
                processes =
                    listOf(
                        AndroidProcess(pid = 321, parentPid = 1, user = "u0_a1", name = "com.example.Camera"),
                        AndroidProcess(pid = 654, parentPid = 1, user = "root", name = "surfaceflinger"),
                    ),
            )

        assertEquals(listOf("com.example.Camera"), snapshot.search("camera").packages.map(AndroidPackage::packageName))
        assertEquals(listOf(654), snapshot.search("654").processes.map(AndroidProcess::pid))
        assertEquals(2, snapshot.search("  ").processes.size)
    }

    @Test
    fun `lists threads for a positive process id`() =
        runBlocking {
            var request: ProcessRequest? = null
            val catalog =
                AdbTargetCatalog(Path.of("adb")) { received, _ ->
                    request = received
                    completed(
                        received,
                        """
                        PID TID NAME
                        321 321 com.example.app
                        321 333 RenderThread
                        """.trimIndent(),
                    )
                }

            val threads =
                assertIs<StudioResult.Success<List<AndroidThread>>>(
                    catalog.listThreads("serial-1", 321),
                ).value

            assertEquals(
                listOf("-s", "serial-1", "shell", "ps", "-T", "-p", "321", "-o", "PID,TID,NAME"),
                request?.arguments,
            )
            assertEquals(listOf(321, 333), threads.map(AndroidThread::tid))
            assertEquals("RenderThread", threads.last().name)
        }

    @Test
    fun `preserves a structured failure and skips dependent refresh commands`() =
        runBlocking {
            val expected =
                StudioError(
                    category = ErrorCategory.PROCESS_TIMEOUT,
                    code = "PROCESS_TIMEOUT",
                    message = "timed out",
                )
            var invocationCount = 0
            val catalog =
                AdbTargetCatalog(Path.of("adb")) { _, _ ->
                    invocationCount += 1
                    ProcessRunResult.Failed(expected)
                }

            val result = assertIs<StudioResult.Failure>(catalog.refresh("serial-1"))

            assertSame(expected, result.error)
            assertEquals(1, invocationCount)
        }

    private fun completed(
        request: ProcessRequest,
        stdout: String,
    ): ProcessRunResult.Completed =
        ProcessRunResult.Completed(
            ProcessOutput(
                pid = 1,
                command = request.command,
                exitCode = 0,
                stdout = CapturedProcessText(stdout, truncated = false),
                stderr = CapturedProcessText("", truncated = false),
                startedAt = Instant.EPOCH,
                finishedAt = Instant.EPOCH,
            ),
        )
}
