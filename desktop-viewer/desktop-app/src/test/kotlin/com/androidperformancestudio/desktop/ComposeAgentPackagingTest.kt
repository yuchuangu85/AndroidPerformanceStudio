package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeAgentPackagingTest {
    private val desktopBuild = Files.readString(Path.of("build.gradle.kts"))

    @Test
    fun `native distributions include the verified cross platform Compose runtime bundle`() {
        assertTrue(desktopBuild.contains("layout-inspector/compose-agent-build/build/compose-agent-bundle"))
        assertTrue(desktopBuild.contains("from(composeAgentBundle)"))
        assertTrue(desktopBuild.contains("into(\"compose-agent-bundle\")"))
        assertTrue(desktopBuild.contains("dependsOn(verifyPackagedComposeAgentBundle)"))
    }

    @Test
    fun `packaging rejects missing corrupt or non dexed agent runtime files`() {
        assertTrue(desktopBuild.contains("manifest.properties"))
        assertTrue(desktopBuild.contains("agent/\$abi/lib_ui_inspector_agent.so"))
        listOf("armeabi-v7a", "arm64-v8a", "x86_64").forEach { abi ->
            assertTrue(desktopBuild.contains("\"$abi\""))
        }
        listOf("service", "payload", "view").forEach { key ->
            assertTrue(desktopBuild.contains("\"$key.sha256\""))
        }
        assertTrue(desktopBuild.contains("path.startsWith(root)"))
        assertTrue(desktopBuild.contains("actual == manifest.getProperty(key)"))
        assertTrue(desktopBuild.contains("zip.getEntry(\"classes.dex\") != null"))
    }

    @Test
    fun `ordinary desktop builds do not resolve Android tools at configuration time`() {
        val agentBuild =
            Files.readString(Path.of("../layout-inspector/compose-agent-build/build.gradle.kts"))
        listOf("androidJar", "d8Tool", "ndkDir", "cmakeBinDir", "llvmStrip").forEach { tool ->
            assertTrue(agentBuild.contains("val $tool = providers.provider {"))
        }
        assertTrue(agentBuild.contains("compileOnly(files(androidJar))"))
        assertTrue(agentBuild.contains("inputs.file(androidJar)"))
    }
}
