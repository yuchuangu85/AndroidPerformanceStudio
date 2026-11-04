package com.androidperformancestudio.aiplatform.app

import com.androidperformancestudio.aiplatform.script.SandboxToolchain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** The packaged helper is a host resource, never a model-selected executable or a PATH search. */
internal fun scriptSandboxToolchain(
    os: String = System.getProperty("os.name"),
    resourceRoot: Path? = System.getProperty("compose.application.resources.dir")?.takeIf(String::isNotBlank)?.let(Path::of),
    architecture: String = System.getProperty("os.arch"),
): SandboxToolchain? {
    if (!os.startsWith("Linux") || resourceRoot == null) return SandboxToolchain(os = os)
    val executable = resourceRoot.toAbsolutePath().normalize().resolve("script-sandbox/aps-script-sandbox")
    val checksum = executable.resolveSibling("aps-script-sandbox.sha256")
    return try {
        var parent: Path? = executable
        while (parent != null) {
            if (Files.isSymbolicLink(parent)) return null
            parent = parent.parent
        }
        if (!Files.isRegularFile(executable, NOFOLLOW_LINKS) ||
            !Files.isExecutable(executable) ||
            !Files.isRegularFile(checksum, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(checksum) ||
            Files.size(executable) !in 64..1_048_576 ||
            Files.size(checksum) !in 64..66
        ) {
            return null
        }
        val bytes = Files.newInputStream(executable, NOFOLLOW_LINKS).use { it.readNBytes(1_048_577) }
        if (bytes.size !in 64..1_048_576) return null
        val expected =
            Files
                .newInputStream(checksum, NOFOLLOW_LINKS)
                .use { it.readNBytes(67) }
                .decodeToString()
                .trim()
        if (!expected.matches(Regex("[a-f0-9]{64}"))) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (digest != expected) return null
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val machine =
            when (architecture.lowercase()) {
                "amd64", "x86_64" -> 62
                "arm64", "aarch64" -> 183
                else -> return null
            }
        if (header.getInt(0) != 0x464c457f ||
            bytes[4].toInt() != 2 ||
            bytes[5].toInt() != 1 ||
            (header.getShort(18).toInt() and 0xffff) != machine
        ) {
            return null
        }
        SandboxToolchain(os = os, linuxLauncher = executable)
    } catch (_: Exception) {
        null
    }
}
