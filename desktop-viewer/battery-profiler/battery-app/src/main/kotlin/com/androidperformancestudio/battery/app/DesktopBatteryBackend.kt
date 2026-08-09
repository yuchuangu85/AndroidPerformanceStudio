@file:Suppress(
    "MaxLineLength",
    "ktlint:standard:max-line-length",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package com.androidperformancestudio.battery.app

import com.androidperformancestudio.adb.AdbDeviceRefresher
import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.battery.capture.BatteryExperimentRunner
import com.androidperformancestudio.battery.historian.BatteryHistorianAdapter
import com.androidperformancestudio.battery.model.BatteryDevice
import com.androidperformancestudio.battery.model.BatteryTarget
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.toolchain.HostCommandResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.StudioHostProcessExecutor
import com.androidperformancestudio.platform.toolchain.SystemHostPlatformDetector
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal sealed interface BatteryBackendResult<out T> {
    data class Success<T>(
        val value: T,
    ) : BatteryBackendResult<T>

    data class Failure(
        val message: String,
    ) : BatteryBackendResult<Nothing>
}

internal interface BatteryBackend {
    suspend fun listDevices(): BatteryBackendResult<List<BatteryDevice>>

    suspend fun listTargets(serial: String): BatteryBackendResult<List<BatteryTarget>>

    fun openRunner(
        serial: String,
        target: BatteryTarget,
    ): BatteryBackendResult<BatteryExperimentRunner>

    fun openHistorian(serial: String): BatteryBackendResult<BatteryHistorianAdapter>

    suspend fun resetStatistics(serial: String): BatteryBackendResult<Unit>
}

internal class DesktopBatteryBackend(
    private val adbLocator: () -> Path? = ::locateSystemAdb,
    private val processRunner: StudioHostProcessExecutor = StudioHostProcessExecutor(),
) : BatteryBackend {
    override suspend fun listDevices(): BatteryBackendResult<List<BatteryDevice>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = AdbDeviceRefresher(adb).refresh()) {
            is StudioResult.Failure -> BatteryBackendResult.Failure(result.error.message)
            is StudioResult.Success ->
                BatteryBackendResult.Success(
                    result.value.map {
                        BatteryDevice(
                            it.serial,
                            it.model?.replace('_', ' ') ?: it.serial,
                            it.state == AdbDeviceState.ONLINE,
                        )
                    },
                )
        }
    }

    override suspend fun listTargets(serial: String): BatteryBackendResult<List<BatteryTarget>> {
        val adb = adbLocator() ?: return missingAdb()
        return try {
            val packages = executeShell(adb, serial, listOf("cmd", "package", "list", "packages", "-U"))
            val launchers =
                runCatching {
                    executeShell(
                        adb,
                        serial,
                        listOf(
                            "cmd",
                            "package",
                            "query-activities",
                            "--brief",
                            "-a",
                            "android.intent.action.MAIN",
                            "-c",
                            "android.intent.category.LAUNCHER",
                        ),
                    )
                }.getOrDefault("")
            BatteryBackendResult.Success(parsePackageUidList(packages, launchers))
        } catch (exception: Exception) {
            BatteryBackendResult.Failure(exception.message ?: "Unable to list packages and UIDs.")
        }
    }

    override fun openRunner(
        serial: String,
        target: BatteryTarget,
    ): BatteryBackendResult<BatteryExperimentRunner> {
        val adb = adbLocator() ?: return missingAdb()
        return BatteryBackendResult.Success(BatteryExperimentRunner(adb, serial, target))
    }

    override fun openHistorian(serial: String): BatteryBackendResult<BatteryHistorianAdapter> {
        val adb = adbLocator() ?: return missingAdb()
        return BatteryBackendResult.Success(BatteryHistorianAdapter(adb, serial))
    }

    override suspend fun resetStatistics(serial: String): BatteryBackendResult<Unit> {
        val adb = adbLocator() ?: return missingAdb()
        return try {
            executeShell(adb, serial, listOf("dumpsys", "batterystats", "--reset"))
            BatteryBackendResult.Success(Unit)
        } catch (exception: Exception) {
            BatteryBackendResult.Failure(exception.message ?: "Unable to reset batterystats.")
        }
    }

    private suspend fun executeShell(
        adb: Path,
        serial: String,
        arguments: List<String>,
    ): String {
        val request =
            HostProcessRequest(
                adb,
                listOf("-s", serial, "shell") + arguments,
                timeout = COMMAND_TIMEOUT,
                maxOutputBytesPerStream = MAX_OUTPUT,
            )
        return when (val result = processRunner.run(request)) {
            is HostCommandResult.Completed -> result.output.stdout.text
            is HostCommandResult.Failed -> throw IllegalStateException(
                result.output
                    ?.stderr
                    ?.text
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { result.error.message },
            )
        }
    }

    private fun missingAdb() =
        BatteryBackendResult.Failure("Android SDK Platform Tools were not found. Configure ANDROID_HOME or ANDROID_SDK_ROOT.")

    private companion object {
        const val MAX_OUTPUT = 16 * 1024 * 1024
        val COMMAND_TIMEOUT = 60.seconds

        fun locateSystemAdb(): Path? {
            val platform = (SystemHostPlatformDetector().detect() as? StudioResult.Success)?.value ?: return null
            return (SystemAdbLocator(platform).locate() as? StudioResult.Success)?.value?.executable
        }
    }
}

internal fun parsePackageUidList(
    packageOutput: String,
    launcherOutput: String,
): List<BatteryTarget> {
    val launchers =
        launcherOutput
            .lineSequence()
            .map(String::trim)
            .filter { COMPONENT.matches(it) }
            .associateBy { it.substringBefore('/') }
    val packages =
        packageOutput
            .lineSequence()
            .mapNotNull { line ->
                PACKAGE_UID.find(line.trim())?.let { match -> match.groupValues[1] to match.groupValues[2].toInt() }
            }.distinct()
            .toList()
    val sharedUids =
        packages
            .groupingBy { it.second }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return packages
        .map { (name, uid) ->
            BatteryTarget(name, uid, sharedUid = uid in sharedUids, launcherComponent = launchers[name])
        }.sortedBy(BatteryTarget::packageName)
}

private val PACKAGE_UID = Regex("package:([A-Za-z0-9_.$]+)\\s+uid:(\\d+)")
private val COMPONENT = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
