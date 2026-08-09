package com.androidperformancestudio.network.capture

import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.HostProcessTimeoutException
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessAdbCommandRunnerTest {
    @Test
    fun `delegates structured adb command and reports shared timeout`() =
        runBlocking {
            var capturedRequest: HostProcessRequest? = null
            val delegate =
                object : HostProcessRunner {
                    override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
                        capturedRequest = request
                        throw HostProcessTimeoutException(request.command, request.timeout, 42)
                    }

                    override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("not used")

                    override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
                }

            val result = ProcessAdbCommandRunner(delegate).run(listOf("/sdk/adb", "devices"), Duration.ofSeconds(2))

            assertEquals(Path.of("/sdk/adb"), capturedRequest?.executable)
            assertEquals(listOf("devices"), capturedRequest?.arguments)
            assertTrue(result.timedOut)
        }
}
