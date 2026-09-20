package com.androidperformancestudio.memory.capture

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeakCanaryAgentInjectorTest {
    @Test
    fun `injects debug bridge dependencies and preserves a backup`() {
        val root = Files.createTempDirectory("aps-android-project")
        val module = root.resolve("app").createDirectories()
        val gradle = module.resolve("build.gradle.kts")
        gradle.toFile().writeText(
            """
            plugins { id("com.android.application") }
            android { namespace = "com.example" }
            dependencies {
                implementation("androidx.activity:activity:1.11.0")
            }
            """.trimIndent(),
        )
        val aar = root.resolve("bridge.aar").also { Files.writeString(it, "aar") }
        val protocol = root.resolve("protocol.jar").also { Files.writeString(it, "jar") }

        val result = AndroidProjectLeakCanaryInjector().inject(root, aar, protocol)

        assertTrue(result.changed)
        assertTrue(result.backupFile?.toFile()?.isFile == true)
        val updated = gradle.toFile().readText()
        assertContains(updated, "// APS LeakCanary Agent")
        assertContains(updated, "debugImplementation(files(")
        assertContains(updated, "leakcanary-android:2.14")
        assertContains(updated, "kotlinx-serialization-json:1.9.0")
        assertTrue(result.agentAar.toFile().isFile)
        assertTrue(result.protocolJar.toFile().isFile)

        val second = AndroidProjectLeakCanaryInjector().inject(root, aar, protocol)
        assertFalse(second.changed)
    }
}
