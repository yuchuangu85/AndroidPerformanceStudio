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
                        1 -> completed(request, packageListOutput())
                        2 -> completed(request, simpleperfAppTypesOutput())
                        else -> completed(request, processListOutput())
                    }
                }

            val result = assertIs<StudioResult.Success<AdbTargetSnapshot>>(catalog.refresh("serial-1", signal)).value

            assertEquals(listOf("-s", "serial-1", "shell", "sh", "-c"), requests[1].arguments.dropLast(1))
            assertEquals(listOf("ps", "-A", "-o", "PID,PPID,USER,NAME"), requests.last().arguments.takeLast(4))
            assertEquals(
                listOf("com.example.alpha", "com.example.zeta"),
                result.packages.map(AndroidPackage::packageName),
            )
            assertEquals(listOf(101, 202), result.processes.map(AndroidProcess::pid))
            assertEquals("u0_a123", result.processes.first().user)
            assertEquals("com.example.alpha", result.processes.first().name)
            assertEquals(true, result.packages.first { it.packageName == "com.example.alpha" }.debuggable)
            assertEquals(true, result.packages.first { it.packageName == "com.example.zeta" }.profileableByShell)
        }

    @Test
    fun `falls back to packages list when simpleperf app runner is unavailable`() =
        runBlocking {
            val requests = mutableListOf<ProcessRequest>()
            val catalog =
                AdbTargetCatalog(Path.of("adb")) { request, _ ->
                    requests += request
                    when (requests.size) {
                        1 -> completed(request, packageListOutput())
                        2 -> processExitFailure()
                        3 -> completed(request, packagesListMetadataOutput())
                        else -> completed(request, processListOutput())
                    }
                }

            val result = assertIs<StudioResult.Success<AdbTargetSnapshot>>(catalog.refresh("serial-1")).value

            assertEquals(listOf("cat", "/data/system/packages.list"), requests[2].arguments.takeLast(2))
            assertEquals(true, result.packages.first { it.packageName == "com.example.alpha" }.debuggable)
            assertEquals(true, result.packages.first { it.packageName == "com.example.zeta" }.profileableByShell)
        }

    @Test
    fun `falls back to dumpsys when app runner and packages list are inaccessible`() =
        runBlocking {
            val requests = mutableListOf<ProcessRequest>()
            val catalog =
                AdbTargetCatalog(Path.of("adb")) { request, _ ->
                    requests += request
                    when (requests.size) {
                        1 -> completed(request, packageListOutput())
                        2 -> processExitFailure()
                        3 -> processExitFailure()
                        4 -> completed(request, legacyPackageDetailsOutput())
                        else -> completed(request, processListOutput())
                    }
                }

            val result = assertIs<StudioResult.Success<AdbTargetSnapshot>>(catalog.refresh("serial-1")).value

            assertEquals(
                listOf("dumpsys", "package", "packages"),
                requests[3].arguments.takeLast(3),
            )
            assertEquals(true, result.packages.first { it.packageName == "com.example.alpha" }.debuggable)
            assertEquals(true, result.packages.first { it.packageName == "com.example.zeta" }.profileableByShell)
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

    private fun processExitFailure(): ProcessRunResult.Failed =
        ProcessRunResult.Failed(
            StudioError(
                category = ErrorCategory.PROCESS_EXIT,
                code = "PROCESS_EXIT_1",
                message = "permission denied",
            ),
        )

    private fun packageListOutput(): String =
        """
        package:com.example.zeta
        package:com.example.alpha
        package:com.example.alpha
        """.trimIndent()

    private fun simpleperfAppTypesOutput(): String =
        """
        com.example.alpha debuggable
        com.example.zeta profileable
        """.trimIndent()

    private fun packagesListMetadataOutput(): String =
        """
        com.example.alpha 10123 1 /data/user/0/com.example.alpha default:targetSdkVersion=35 none 0 123 0 @null
        com.example.zeta 10124 0 /data/user/0/com.example.zeta default:targetSdkVersion=35 none 1 456 0 @null
        """.trimIndent()

    private fun legacyPackageDetailsOutput(): String =
        """
        Packages:
          Package [com.example.alpha] (abc):
            pkgFlags=[ DEBUGGABLE HAS_CODE ]
            privatePkgFlags=[ PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE ]
          Package [com.example.zeta] (def):
            pkgFlags=[ HAS_CODE ]
            privatePkgFlags=[ PRIVATE_FLAG_PROFILEABLE_BY_SHELL ]
        """.trimIndent()

    private fun processListOutput(): String =
        """
        PID   PPID USER      NAME
        101   1    u0_a123   com.example.alpha
        202   1    root      surfaceflinger
        """.trimIndent()
}
