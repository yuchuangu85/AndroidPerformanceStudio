package com.androidperformancestudio.platform.adb

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManagedAdbBundleTest {
    @Test
    fun `bundled adb resolves without SDK environment PATH or default SDK on every supported host`() {
        val hosts =
            listOf(
                Triple("Mac OS X", "arm64", "macos-arm64"),
                Triple("Mac OS X", "x86_64", "macos-x64"),
                Triple("Windows 11", "amd64", "windows-x64"),
                Triple("Linux", "amd64", "linux-x64"),
                Triple("Linux", "aarch64", "linux-arm64"),
            )
        hosts.forEach { (os, arch, host) ->
            withRoot { root ->
                val resources = root.resolve("Application With Spaces/resources")
                val bundle = resources.resolve("adb-tools")
                val executable = createBundle(bundle, host)
                val result = locator(root, os, arch, resources).locate()

                assertEquals(executable.toRealPath(), result.executable)
                assertEquals(AdbLocationSource.BUNDLED, result.source)
            }
        }
    }

    @Test
    fun `managed development bundle works without an installed SDK`() {
        withRoot { root ->
            val bundle = root.resolve("build/adb-tools")
            val executable = createBundle(bundle)
            val result = locator(root, managedTools = bundle).locate()

            assertEquals(executable.toRealPath(), result.executable)
            assertEquals(AdbLocationSource.MANAGED_TOOLS, result.source)
        }
    }

    @Test
    fun `explicit executable and explicit SDK keep precedence over bundled adb`() {
        withRoot { root ->
            val resources = root.resolve("resources")
            createBundle(resources.resolve("adb-tools"))
            val explicit = createExecutable(root.resolve("custom/adb"))
            val sdk = root.resolve("sdk")
            val sdkAdb = createExecutable(sdk.resolve("platform-tools/adb"))
            val locator = locator(root, resources = resources)

            assertEquals(explicit, locator.locate(AdbLocatorConfiguration(executablePath = explicit)).executable)
            assertEquals(sdkAdb, locator.locate(AdbLocatorConfiguration(androidSdkPath = sdk)).executable)
            assertFailsWith<AdbNotExecutableException> {
                locator.locate(AdbLocatorConfiguration(executablePath = root.resolve("missing")))
            }
        }
    }

    @Test
    fun `corrupt executable is rejected before it can be launched`() {
        withRoot { root ->
            val resources = root.resolve("resources")
            val executable = createBundle(resources.resolve("adb-tools"))
            Files.writeString(executable, "corrupt")

            val error = assertFailsWith<AdbBundleInvalidException> { locator(root, resources = resources).locate() }
            assertTrue(error.message.orEmpty().contains("checksum mismatch: adb"))
        }
    }

    @Test
    fun `missing Windows companion library is rejected`() {
        withRoot { root ->
            val resources = root.resolve("resources")
            val bundle = resources.resolve("adb-tools")
            createBundle(bundle, "windows-x64", mapOf("AdbWinApi.dll" to "library"))
            Files.delete(bundle.resolve("AdbWinApi.dll"))

            assertFailsWith<AdbBundleInvalidException> {
                locator(root, "Windows 11", "amd64", resources).locate()
            }
        }
    }

    @Test
    fun `bundle for a different host architecture is rejected`() {
        withRoot { root ->
            val resources = root.resolve("resources")
            createBundle(resources.resolve("adb-tools"), "linux-x64")

            val error = assertFailsWith<AdbBundleInvalidException> { locator(root, resources = resources).locate() }
            assertTrue(error.message.orEmpty().contains("does not match macos-arm64"))
        }
    }

    @Test
    fun `bundle without a manifest is not accepted as an unmanaged executable`() {
        withRoot { root ->
            val resources = root.resolve("resources")
            createExecutable(resources.resolve("adb-tools/adb"))

            assertFailsWith<AdbBundleInvalidException> { locator(root, resources = resources).locate() }
        }
    }

    @Test
    fun `oversized bundle manifest is rejected before parsing`() {
        withRoot { root ->
            val bundle = root.resolve("adb-tools")
            createBundle(bundle)
            Files.writeString(bundle.resolve("manifest.properties"), "x".repeat(129 * 1024))

            val error = assertFailsWith<AdbBundleInvalidException> { ManagedAdbBundle(bundle, "Mac OS X", "arm64").verify() }
            assertTrue(error.message.orEmpty().contains("manifest is too large"))
        }
    }

    @Test
    fun `bundle paths cannot escape the bundle directory`() {
        withRoot { root ->
            val bundle = root.resolve("adb-tools")
            createBundle(bundle)
            val manifest = loadManifest(bundle)
            manifest.setProperty("file.0.path", "../outside")
            Files.writeString(root.resolve("outside"), "adb")
            saveManifest(bundle, manifest)

            assertFailsWith<AdbBundleInvalidException> { ManagedAdbBundle(bundle, "Mac OS X", "arm64").verify() }
        }
    }

    @Test
    fun `missing license notices are rejected`() {
        withRoot { root ->
            val bundle = root.resolve("adb-tools")
            createBundle(bundle)
            Files.delete(bundle.resolve("NOTICE.txt"))

            assertFailsWith<AdbBundleInvalidException> { ManagedAdbBundle(bundle, "Mac OS X", "arm64").verify() }
        }
    }

    @Test
    fun `absolute bundled executable starts with empty process environment`() {
        if (System.getProperty("os.name").contains("windows", ignoreCase = true)) return
        withRoot { root ->
            val resources = root.resolve("App With Spaces/resources")
            createBundle(
                resources.resolve("adb-tools"),
                executableContent = "#!/bin/sh\nprintf 'List of devices attached\\nfixture-device device model:Test_Device\\n'\n",
            )
            val executable = locator(root, resources = resources).locate().executable
            val process =
                ProcessBuilder(executable.toString(), "devices", "-l").apply {
                    environment().clear()
                    environment()["PATH"] = ""
                }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }

            assertEquals(0, process.waitFor())
            assertTrue(output.contains("fixture-device device"))
        }
    }

    private fun locator(
        root: Path,
        os: String = "Mac OS X",
        arch: String = "arm64",
        resources: Path? = null,
        managedTools: Path? = null,
    ): AdbExecutableLocator =
        AdbExecutableLocator(
            environment = emptyMap(),
            userHome = root.resolve("empty-home"),
            osName = os,
            applicationResourcesPath = resources,
            managedToolsPath = managedTools,
            osArch = arch,
        )

    private fun createBundle(
        directory: Path,
        host: String = "macos-arm64",
        companions: Map<String, String> = emptyMap(),
        executableContent: String = "adb",
    ): Path {
        val executableName = if (host.startsWith("windows")) "adb.exe" else "adb"
        val files = mapOf(executableName to executableContent, "NOTICE.txt" to "Apache License notices") + companions
        val manifest =
            Properties().apply {
                setProperty("schemaVersion", "1")
                setProperty("version", "37.0.1")
                setProperty("host", host)
                setProperty("file.count", files.size.toString())
            }
        files.entries.forEachIndexed { index, (name, content) ->
            val file = createExecutable(directory.resolve(name), content)
            manifest.setProperty("file.$index.path", name)
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            manifest.setProperty("file.$index.sha256", digest.joinToString("") { "%02x".format(it) })
        }
        saveManifest(directory, manifest)
        return directory.resolve(executableName)
    }

    private fun createExecutable(
        path: Path,
        content: String = "adb",
    ): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        path.toFile().setExecutable(true)
        return path
    }

    private fun loadManifest(directory: Path): Properties =
        Properties().apply { Files.newInputStream(directory.resolve("manifest.properties")).use(::load) }

    private fun saveManifest(
        directory: Path,
        manifest: Properties,
    ) {
        Files.newOutputStream(directory.resolve("manifest.properties")).use { manifest.store(it, null) }
    }

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("managed-adb-test")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
