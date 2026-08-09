package com.androidperformancestudio.parser

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile

enum class HostSimpleperfSource {
    CONFIGURED,
    BUNDLED,
    PATH,
}

data class BundledHostSimpleperf(
    val executable: Path,
    val expectedSha256: String,
) {
    init {
        require(SHA_256.matches(expectedSha256)) { "expectedSha256 must be a lowercase hexadecimal digest" }
    }

    companion object {
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

data class HostSimpleperf(
    val executable: Path,
    val version: String,
    val sha256: String,
    val source: HostSimpleperfSource,
)

data class HostSimpleperfCandidate(
    val executable: Path,
    val source: HostSimpleperfSource,
    val expectedSha256: String? = null,
)

typealias ParserProcessInvocation =
    suspend (HostProcessRequest, HostCancellationSignal) -> HostCommandResult

class HostSimpleperfLocator(
    private val configuredExecutable: Path?,
    private val bundledExecutable: BundledHostSimpleperf?,
    private val pathDirectories: List<Path>,
    private val processInvocation: ParserProcessInvocation = { request, signal ->
        StudioHostProcessExecutor().run(request, signal)
    },
) {
    fun candidates(): List<HostSimpleperfCandidate> =
        buildList {
            configuredExecutable?.let { add(HostSimpleperfCandidate(it, HostSimpleperfSource.CONFIGURED)) }
            bundledExecutable?.let {
                add(HostSimpleperfCandidate(it.executable, HostSimpleperfSource.BUNDLED, it.expectedSha256))
            }
            pathDirectories.forEach { directory ->
                add(HostSimpleperfCandidate(directory.resolve(executableName()), HostSimpleperfSource.PATH))
            }
        }.filter { it.executable.isRegularFile() }
            .distinctBy { it.executable.toAbsolutePath().normalize() }

    suspend fun locate(): StudioResult<HostSimpleperf> = locate(HostCancellationSignal())

    suspend fun locate(cancellationSignal: HostCancellationSignal): StudioResult<HostSimpleperf> {
        val candidate = candidates().firstOrNull() ?: return notFound()
        return when (val digest = calculateDigest(candidate)) {
            is StudioResult.Failure -> digest
            is StudioResult.Success ->
                if (candidate.expectedSha256 != null && candidate.expectedSha256 != digest.value) {
                    hashMismatch(candidate, digest.value)
                } else {
                    verifyVersion(candidate, digest.value, cancellationSignal)
                }
        }
    }

    private fun calculateDigest(candidate: HostSimpleperfCandidate): StudioResult<String> =
        try {
            StudioResult.Success(candidate.executable.sha256())
        } catch (exception: IOException) {
            ioFailure(candidate.executable, exception)
        }

    private suspend fun verifyVersion(
        candidate: HostSimpleperfCandidate,
        digest: String,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<HostSimpleperf> =
        when (
            val result =
                processInvocation(
                    HostProcessRequest(candidate.executable, arguments = listOf("--version")),
                    cancellationSignal,
                )
        ) {
            is HostCommandResult.Failed -> StudioResult.Failure(result.error)
            is HostCommandResult.Completed -> {
                val version =
                    result.output.stdout.text
                        .ifBlank { result.output.stderr.text }
                        .trim()
                if (version.isBlank()) {
                    StudioResult.Failure(
                        StudioError(
                            ErrorCategory.DATA_VALIDATION,
                            "HOST_SIMPLEPERF_VERSION_EMPTY",
                            "Host simpleperf returned an empty version: ${candidate.executable}",
                        ),
                    )
                } else {
                    StudioResult.Success(HostSimpleperf(candidate.executable, version, digest, candidate.source))
                }
            }
        }

    private fun notFound(): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                ErrorCategory.CONFIGURATION,
                "HOST_SIMPLEPERF_NOT_FOUND",
                "Host simpleperf was not found in configured, bundled, or PATH locations",
            ),
        )

    private fun ioFailure(
        executable: Path,
        cause: IOException,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                ErrorCategory.IO,
                "HOST_SIMPLEPERF_HASH_READ_FAILED",
                "Failed to read host simpleperf: $executable",
                cause,
            ),
        )

    private fun hashMismatch(
        candidate: HostSimpleperfCandidate,
        actual: String,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                ErrorCategory.DATA_VALIDATION,
                "HOST_SIMPLEPERF_HASH_MISMATCH",
                "Host simpleperf hash mismatch: expected ${candidate.expectedSha256}, actual $actual",
            ),
        )

    companion object {
        fun executableName(osName: String = System.getProperty("os.name").orEmpty()): String =
            if (osName.contains("windows", ignoreCase = true)) "simpleperf.exe" else "simpleperf"
    }
}

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
