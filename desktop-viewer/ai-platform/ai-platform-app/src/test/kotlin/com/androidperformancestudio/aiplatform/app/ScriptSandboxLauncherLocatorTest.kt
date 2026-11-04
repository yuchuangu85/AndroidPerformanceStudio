package com.androidperformancestudio.aiplatform.app

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScriptSandboxLauncherLocatorTest {
    @Test
    fun `packaged Linux launcher is selected only with matching digest ELF and host architecture`() =
        fixture { root, binary ->
            assertEquals(binary, assertNotNull(scriptSandboxToolchain("Linux", root, "x86_64")).linuxLauncher)
            assertNull(scriptSandboxToolchain("Linux", root, "arm64"))
            assertNull(scriptSandboxToolchain("Linux", root, "unqualified"))
        }

    @Test
    fun `bad missing oversized or symlink checksums never fall back to a system helper`() =
        fixture { root, binary ->
            val hash = binary.resolveSibling("aps-script-sandbox.sha256")
            Files.writeString(hash, "0".repeat(64))
            assertNull(scriptSandboxToolchain("Linux", root, "x86_64"))
            Files.writeString(hash, "f".repeat(67))
            assertNull(scriptSandboxToolchain("Linux", root, "x86_64"))
            Files.delete(hash)
            assertNull(scriptSandboxToolchain("Linux", root, "x86_64"))
            val external = Files.writeString(root.resolve("external-sha"), digest(Files.readAllBytes(binary)))
            Files.createSymbolicLink(hash, external)
            assertNull(scriptSandboxToolchain("Linux", root, "x86_64"))
        }

    @Test
    fun `rehashed non-ELF binaries and symlinked executables remain unqualified`() =
        fixture { root, binary ->
            Files.write(binary, ByteArray(64))
            Files.writeString(binary.resolveSibling("aps-script-sandbox.sha256"), digest(Files.readAllBytes(binary)))
            assertNull(scriptSandboxToolchain("Linux", root, "x86_64"))
            val external = Files.move(binary, root.resolve("external-binary"))
            Files.createSymbolicLink(binary, external)
            assertNull(scriptSandboxToolchain("Linux", root, "x86_64"))
        }

    @Test
    fun `unpackaged and unsupported OS toolchains stay explicit without running any helper`() {
        assertEquals(Path.of("/usr/libexec/aps/aps-script-sandbox"), assertNotNull(scriptSandboxToolchain("Linux", null)).linuxLauncher)
        assertEquals("Mac OS X", assertNotNull(scriptSandboxToolchain("Mac OS X", Path.of("/missing"))).os)
        assertEquals("Windows 11", assertNotNull(scriptSandboxToolchain("Windows 11", Path.of("/missing"))).os)
    }

    private fun fixture(block: (Path, Path) -> Unit) {
        assumeTrue(
            !System.getProperty("os.name").startsWith("Windows"),
            "ELF permission and symlink contract fixture requires a POSIX filesystem",
        )
        val root = Files.createTempDirectory("aps-script-launcher-").toRealPath()
        try {
            val directory = Files.createDirectory(root.resolve("script-sandbox"))
            val bytes = ByteArray(64)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 0x464c457f)
                put(4, 2)
                put(5, 1)
                putShort(18, 62)
            }
            val binary = Files.write(directory.resolve("aps-script-sandbox"), bytes)
            check(binary.toFile().setExecutable(true, true))
            Files.writeString(directory.resolve("aps-script-sandbox.sha256"), digest(bytes) + "\n")
            block(root, binary)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
