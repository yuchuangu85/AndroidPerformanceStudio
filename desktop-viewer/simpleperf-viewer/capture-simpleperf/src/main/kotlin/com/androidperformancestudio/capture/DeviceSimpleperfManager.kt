package com.androidperformancestudio.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessCancellationSignal
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import java.nio.file.Path

data class BundledSimpleperfAsset(
    val abi: String,
    val executable: Path,
    val sha256: String,
) {
    init {
        require(abi.isNotBlank()) { "abi must not be blank" }
        require(SHA_256.matches(sha256)) { "sha256 must be a lowercase hexadecimal digest" }
    }

    companion object {
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

data class DeviceSimpleperfAvailability(
    val deviceVersion: String?,
    val abis: List<String>,
)

enum class SimpleperfSource {
    DEVICE,
    BUNDLED_EXISTING,
    BUNDLED_PUSHED,
}

data class PreparedSimpleperf(
    val source: SimpleperfSource,
    val devicePath: String,
    val version: String?,
    val abi: String?,
)

typealias CaptureProcessInvocation =
    suspend (ProcessRequest, ProcessCancellationSignal) -> ProcessRunResult

fun interface DeviceSimpleperfPreparer {
    suspend fun prepare(
        serial: String,
        availability: DeviceSimpleperfAvailability,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<PreparedSimpleperf>
}

class DeviceSimpleperfManager(
    private val adbExecutable: Path,
    assets: List<BundledSimpleperfAsset>,
    private val processInvocation: CaptureProcessInvocation = { request, signal ->
        JvmProcessRunner().run(request, signal)
    },
) : DeviceSimpleperfPreparer {
    private val assetsByAbi = assets.associateBy(BundledSimpleperfAsset::abi)

    suspend fun prepare(
        serial: String,
        availability: DeviceSimpleperfAvailability,
    ): StudioResult<PreparedSimpleperf> = prepare(serial, availability, ProcessCancellationSignal())

    override suspend fun prepare(
        serial: String,
        availability: DeviceSimpleperfAvailability,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<PreparedSimpleperf> =
        availability.deviceVersion?.let { version ->
            StudioResult.Success(
                PreparedSimpleperf(
                    source = SimpleperfSource.DEVICE,
                    devicePath = DEVICE_SIMPLEPERF,
                    version = version,
                    abi = null,
                ),
            )
        } ?: prepareBundled(serial, availability.abis, cancellationSignal)

    private suspend fun prepareBundled(
        serial: String,
        abis: List<String>,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<PreparedSimpleperf> {
        val asset = abis.firstNotNullOfOrNull(assetsByAbi::get) ?: return unsupportedAbi(abis)
        return when (val checksum = remoteChecksum(serial, cancellationSignal)) {
            is StudioResult.Failure -> checksum
            is StudioResult.Success ->
                if (checksum.value == asset.sha256) {
                    prepared(asset, SimpleperfSource.BUNDLED_EXISTING, version = null)
                } else {
                    deploy(serial, asset, cancellationSignal)
                }
        }
    }

    private suspend fun remoteChecksum(
        serial: String,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<String?> {
        val request = shellRequest(serial, "sha256sum", REMOTE_SIMPLEPERF)
        return when (val result = processInvocation(request, cancellationSignal)) {
            is ProcessRunResult.Completed ->
                StudioResult.Success(
                    result.output.stdout.text
                        .trim()
                        .substringBefore(' ')
                        .takeIf(String::isNotBlank),
                )
            is ProcessRunResult.Failed ->
                if (result.error.category == ErrorCategory.PROCESS_EXIT) {
                    StudioResult.Success(null)
                } else {
                    StudioResult.Failure(result.error)
                }
        }
    }

    private suspend fun deploy(
        serial: String,
        asset: BundledSimpleperfAsset,
        cancellationSignal: ProcessCancellationSignal,
    ): StudioResult<PreparedSimpleperf> {
        val requests =
            listOf(
                shellRequest(serial, "mkdir", "-p", REMOTE_DIRECTORY),
                adbRequest(serial, "push", asset.executable.toString(), REMOTE_SIMPLEPERF),
                shellRequest(serial, "chmod", EXECUTABLE_MODE, REMOTE_SIMPLEPERF),
                shellRequest(serial, REMOTE_SIMPLEPERF, "--version"),
            )
        var version: String? = null
        for (request in requests) {
            when (val result = processInvocation(request, cancellationSignal)) {
                is ProcessRunResult.Failed -> return StudioResult.Failure(result.error)
                is ProcessRunResult.Completed ->
                    version =
                        result.output.stdout.text
                            .trim()
                            .ifEmpty { version }
            }
        }
        return prepared(asset, SimpleperfSource.BUNDLED_PUSHED, version)
    }

    private fun shellRequest(
        serial: String,
        vararg arguments: String,
    ): ProcessRequest = adbRequest(serial, "shell", *arguments)

    private fun adbRequest(
        serial: String,
        vararg arguments: String,
    ): ProcessRequest =
        ProcessRequest(
            executable = adbExecutable,
            arguments = listOf("-s", serial) + arguments,
        )

    private fun prepared(
        asset: BundledSimpleperfAsset,
        source: SimpleperfSource,
        version: String?,
    ): StudioResult.Success<PreparedSimpleperf> =
        StudioResult.Success(
            PreparedSimpleperf(
                source = source,
                devicePath = REMOTE_SIMPLEPERF,
                version = version,
                abi = asset.abi,
            ),
        )

    private fun unsupportedAbi(abis: List<String>): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.CONFIGURATION,
                code = "BUNDLED_SIMPLEPERF_ABI_UNAVAILABLE",
                message = "No bundled simpleperf is available for device ABIs: ${abis.joinToString()}",
            ),
        )

    companion object {
        private const val DEVICE_SIMPLEPERF = "simpleperf"
        private const val REMOTE_DIRECTORY = "/data/local/tmp/aps"
        private const val REMOTE_SIMPLEPERF = "$REMOTE_DIRECTORY/simpleperf"
        private const val EXECUTABLE_MODE = "755"
    }
}
