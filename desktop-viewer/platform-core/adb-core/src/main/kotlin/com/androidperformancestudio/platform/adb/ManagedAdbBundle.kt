package com.androidperformancestudio.platform.adb

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Checks the executable and its companion files before using an application-managed ADB. */
internal class ManagedAdbBundle(
    private val directory: Path,
    private val osName: String,
    private val osArch: String,
) {
    fun verify(): Path =
        try {
            verifyFiles()
        } catch (error: Exception) {
            throw AdbBundleInvalidException(directory, error.message ?: "Bundle validation failed", error)
        }

    private fun verifyFiles(): Path {
        val root = directory.toRealPath()
        val manifestFile = containedFile(root, MANIFEST_NAME)
        require(Files.size(manifestFile) <= MAX_MANIFEST_BYTES) { "ADB bundle manifest is too large" }
        val manifest = Properties().apply { Files.newInputStream(manifestFile).use(::load) }
        require(manifest.getProperty("schemaVersion") == "1") { "Unsupported ADB bundle manifest" }
        require(!manifest.getProperty("version").isNullOrBlank()) { "ADB bundle version is missing" }
        require(manifest.getProperty("host") == hostKey()) { "ADB bundle does not match ${hostKey()}" }
        val count = manifest.getProperty("file.count")?.toIntOrNull()
        require(count != null && count in 1..MAX_BUNDLE_FILES) { "Invalid ADB bundle file count" }
        val paths = mutableSetOf<String>()
        repeat(count) { index ->
            val relative = requireNotNull(manifest.getProperty("file.$index.path")) { "Missing ADB bundle file path" }
            require(relative != MANIFEST_NAME && paths.add(relative)) { "Duplicate or reserved ADB bundle path" }
            val expected = manifest.getProperty("file.$index.sha256").orEmpty()
            require(SHA_256.matches(expected)) { "Invalid checksum for $relative" }
            val file = containedFile(root, relative)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var countRead = input.read(buffer)
                while (countRead >= 0) {
                    digest.update(buffer, 0, countRead)
                    countRead = input.read(buffer)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == expected) { "ADB bundle checksum mismatch: $relative" }
        }
        val executableName = if (osName.contains("windows", ignoreCase = true)) "adb.exe" else "adb"
        require(executableName in paths) { "ADB executable is missing from bundle manifest" }
        require("NOTICE.txt" in paths) { "ADB bundle license notices are missing" }
        return containedFile(root, executableName)
    }

    private fun containedFile(
        root: Path,
        relative: String,
    ): Path {
        val path = Path.of(relative)
        require(!path.isAbsolute && path.none { it.toString() == ".." }) { "Invalid ADB bundle path: $relative" }
        val file = root.resolve(path).toRealPath()
        require(file.startsWith(root) && Files.isRegularFile(file)) { "Invalid ADB bundle file: $relative" }
        return file
    }

    private fun hostKey(): String {
        val os =
            when {
                osName.contains("mac", ignoreCase = true) -> "macos"
                osName.contains("windows", ignoreCase = true) -> "windows"
                osName.contains("linux", ignoreCase = true) -> "linux"
                else -> error("Unsupported ADB host OS: $osName")
            }
        val arch =
            when (osArch.lowercase()) {
                "x64", "x86_64", "amd64" -> "x64"
                "arm64", "aarch64" -> "arm64"
                else -> error("Unsupported ADB host architecture: $osArch")
            }
        return "$os-$arch"
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.properties"
        const val MAX_MANIFEST_BYTES = 128 * 1024
        const val MAX_BUNDLE_FILES = 64
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
