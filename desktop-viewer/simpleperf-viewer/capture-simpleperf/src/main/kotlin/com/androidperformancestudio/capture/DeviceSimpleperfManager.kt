package com.androidperformancestudio.capture

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbCommandFailedException
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.HostCancellationSignal
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

fun interface DeviceSimpleperfPreparer {
    suspend fun prepare(
        serial: String,
        availability: DeviceSimpleperfAvailability,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<PreparedSimpleperf>
}

class DeviceSimpleperfManager(
    private val adbClient: AdbClient,
    assets: List<BundledSimpleperfAsset>,
) : DeviceSimpleperfPreparer {
    constructor(
        adbExecutable: Path,
        assets: List<BundledSimpleperfAsset>,
    ) : this(DefaultAdbClient(adbExecutable), assets)

    private val assetsByAbi = assets.associateBy(BundledSimpleperfAsset::abi)

    suspend fun prepare(
        serial: String,
        availability: DeviceSimpleperfAvailability,
    ): StudioResult<PreparedSimpleperf> = prepare(serial, availability, HostCancellationSignal())

    override suspend fun prepare(
        serial: String,
        availability: DeviceSimpleperfAvailability,
        cancellationSignal: HostCancellationSignal,
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
        cancellationSignal: HostCancellationSignal,
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
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<String?> =
        try {
            StudioResult.Success(
                adbClient
                    .shell(
                        serial = serial,
                        arguments = listOf("sha256sum", REMOTE_SIMPLEPERF),
                        isCancellationRequested = cancellationSignal::isCancelled,
                    ).stdout
                    .trim()
                    .substringBefore(' ')
                    .takeIf(String::isNotBlank),
            )
        } catch (_: AdbCommandFailedException) {
            StudioResult.Success(null)
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: AdbException) {
            StudioResult.Failure(adbStudioError(error))
        }

    private suspend fun deploy(
        serial: String,
        asset: BundledSimpleperfAsset,
        cancellationSignal: HostCancellationSignal,
    ): StudioResult<PreparedSimpleperf> {
        val cancelled = cancellationSignal::isCancelled
        return try {
            adbClient.shell(serial, listOf("mkdir", "-p", REMOTE_DIRECTORY), isCancellationRequested = cancelled)
            adbClient.push(serial, asset.executable, REMOTE_SIMPLEPERF, isCancellationRequested = cancelled)
            adbClient.shell(
                serial,
                listOf("chmod", EXECUTABLE_MODE, REMOTE_SIMPLEPERF),
                isCancellationRequested = cancelled,
            )
            val version =
                adbClient
                    .shell(serial, listOf(REMOTE_SIMPLEPERF, "--version"), isCancellationRequested = cancelled)
                    .stdout
                    .trim()
                    .ifEmpty { null }
            prepared(asset, SimpleperfSource.BUNDLED_PUSHED, version)
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: AdbException) {
            StudioResult.Failure(adbStudioError(error))
        }
    }

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

private fun adbStudioError(error: RuntimeException): StudioError =
    StudioError(
        category = ErrorCategory.PROCESS_EXIT,
        code = "ADB_COMMAND_FAILED",
        message = error.message ?: "ADB command failed",
        cause = error,
    )
