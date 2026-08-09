package com.androidperformancestudio.gpu.toolchain

import com.androidperformancestudio.gpu.model.AgiLaunchMode
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgiToolchainTest {
    @Test
    fun `uses configured executable and detects gui only`() {
        val executable = createTempFile()
        executable.toFile().setExecutable(true)
        val runner = object : HostProcessRunner {
            override fun run(arguments: List<String>, timeout: Duration) = ProcessResult(0, if (arguments.last() == "--version") "AGI 4.0" else "usage", "", false)

            override fun launch(arguments: List<String>): RunningHostProcess = error("not used")
        }
        val capability = AgiLocator(runner, emptyMap(), "Linux").locate(executable)
        assertEquals(AgiLaunchMode.GUI_ONLY, capability.launchMode)
        assertTrue(capability.launchSupported)
        assertFalse(capability.artifactOpenSupported)
    }

    @Test
    fun `opens artifact only through recognized agi executable`() {
        val directory = createTempDirectory()
        val executable = directory.resolve("agi").also { it.writeText("binary") }
        val artifact = directory.resolve("capture.gfxtrace").also { it.writeText("trace") }
        executable.toFile().setExecutable(true)
        var launchedArguments = emptyList<String>()
        val runner =
            object : HostProcessRunner {
                override fun run(arguments: List<String>, timeout: Duration) =
                    ProcessResult(0, if (arguments.last() == "--version") "AGI 3.3.3" else "usage", "", false)

                override fun launch(arguments: List<String>): RunningHostProcess {
                    launchedArguments = arguments
                    return completedProcess()
                }
            }
        val locator = AgiLocator(runner, emptyMap(), "Linux")
        val capability = locator.locate(executable)

        assertTrue(capability.artifactOpenSupported)
        locator.launchArtifact(capability, artifact)
        assertEquals(listOf(executable.toString(), artifact.toAbsolutePath().toString()), launchedArguments)
    }

    private fun completedProcess(): RunningHostProcess =
        object : RunningHostProcess {
            override val pid: Long = -1
            override val isAlive: Boolean = false

            override fun terminate() = Unit
        }
}
