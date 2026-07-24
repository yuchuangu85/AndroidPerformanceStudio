package com.androidperformancestudio.gpu.toolchain

import com.androidperformancestudio.gpu.model.AgiLaunchMode
import java.time.Duration
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgiToolchainTest {
    @Test
    fun `uses configured executable and detects gui only`() {
        val executable = createTempFile()
        executable.toFile().setExecutable(true)
        val runner = object : HostProcessRunner {
            override fun run(arguments: List<String>, timeout: Duration) = ProcessResult(0, if (arguments.last() == "--version") "AGI 4.0" else "usage", "", false)

            override fun launch(arguments: List<String>): Process = error("not used")
        }
        val capability = AgiLocator(runner, emptyMap(), "Linux").locate(executable)
        assertEquals(AgiLaunchMode.GUI_ONLY, capability.launchMode)
        assertTrue(capability.launchSupported)
    }
}
