package com.androidperformancestudio.desktop

import com.androidperformancestudio.capture.BundledSimpleperfAsset
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal fun loadBundledDeviceSimpleperfAssets(
    extractionRoot: Path = defaultBundledSimpleperfExtractionRoot(),
    resourceLoader: (String) -> InputStream? = { resourcePath ->
        BundledSimpleperfResources::class.java.classLoader.getResourceAsStream(resourcePath)
    },
): List<BundledSimpleperfAsset> =
    BUNDLED_SIMPLEPERF_DESCRIPTORS.map { descriptor ->
        val executable = extractVerifiedAsset(descriptor, extractionRoot, resourceLoader)
        BundledSimpleperfAsset(descriptor.abi, executable, descriptor.sha256)
    }

private fun extractVerifiedAsset(
    descriptor: BundledSimpleperfDescriptor,
    extractionRoot: Path,
    resourceLoader: (String) -> InputStream?,
): Path {
    val executable = extractionRoot.resolve(descriptor.abi).resolve(SIMPLEPERF_EXECUTABLE)
    if (Files.isRegularFile(executable) && sha256(executable) == descriptor.sha256) {
        return executable
    }

    Files.createDirectories(executable.parent)
    val temporary = Files.createTempFile(executable.parent, "simpleperf-", ".tmp")
    try {
        resourceLoader(descriptor.resourcePath).use { resource ->
            requireNotNull(resource) { "Missing bundled simpleperf resource: ${descriptor.resourcePath}" }
            Files.copy(resource, temporary, StandardCopyOption.REPLACE_EXISTING)
        }
        check(sha256(temporary) == descriptor.sha256) {
            "Bundled simpleperf digest mismatch for ${descriptor.abi}"
        }
        moveIntoPlace(temporary, executable)
    } finally {
        Files.deleteIfExists(temporary)
    }
    check(sha256(executable) == descriptor.sha256) {
        "Extracted simpleperf digest mismatch for ${descriptor.abi}"
    }
    return executable
}

private fun moveIntoPlace(
    source: Path,
    destination: Path,
) {
    try {
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun defaultBundledSimpleperfExtractionRoot(): Path =
    Path
        .of(System.getProperty("user.home"))
        .resolve(".android-performance-studio")
        .resolve("tools")
        .resolve("simpleperf")
        .resolve(BUNDLE_VERSION)

private data class BundledSimpleperfDescriptor(
    val abi: String,
    val sha256: String,
) {
    val resourcePath: String = "$RESOURCE_ROOT/$abi/$SIMPLEPERF_EXECUTABLE"
}

private object BundledSimpleperfResources

private const val BUNDLE_VERSION = "android-ndk-r27b"
private const val RESOURCE_ROOT = "bundled-simpleperf/android"
private const val SIMPLEPERF_EXECUTABLE = "simpleperf"

private val BUNDLED_SIMPLEPERF_DESCRIPTORS =
    listOf(
        BundledSimpleperfDescriptor(
            abi = "arm64-v8a",
            sha256 = "e814416ac315681bec1c28d73f0ddb2dd486ff49a1ad6cb28e49f387139bc18f",
        ),
        BundledSimpleperfDescriptor(
            abi = "armeabi-v7a",
            sha256 = "39271a20f28e6304fb59bbc8fe560ee3dbbcf3ac0109cdbf8d2c08aa41006135",
        ),
        BundledSimpleperfDescriptor(
            abi = "x86",
            sha256 = "37ab52d87a815be407d7f758ff8ebe2060917215aec43775006b0a3697078baa",
        ),
        BundledSimpleperfDescriptor(
            abi = "x86_64",
            sha256 = "830866314c3db85aa3ffcb7f72cd20b7225a0491e9cd71fdfedf1109604d7e89",
        ),
    )
