@file:Suppress("MaxLineLength", "ReturnCount", "TooGenericExceptionCaught")

package com.androidperformancestudio.startup.app

import com.androidperformancestudio.adb.AdbDeviceRefresher
import com.androidperformancestudio.adb.AdbTargetCatalog
import com.androidperformancestudio.adb.SystemAdbLocator
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import com.androidperformancestudio.platform.toolchain.SystemHostPlatformDetector
import com.androidperformancestudio.startup.capture.StartupExperimentRunner
import com.androidperformancestudio.startup.model.StartupDevice
import com.androidperformancestudio.startup.model.StartupTarget
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal sealed interface StartupBackendResult<out T> {
    data class Success<T>(
        val value: T,
    ) : StartupBackendResult<T>

    data class Failure(
        val message: String,
    ) : StartupBackendResult<Nothing>
}

internal interface StartupBackend {
    suspend fun listDevices(): StartupBackendResult<List<StartupDevice>>

    suspend fun listTargets(serial: String): StartupBackendResult<List<StartupTarget>>

    fun openRunner(
        serial: String,
        target: StartupTarget,
    ): StartupBackendResult<StartupExperimentRunner>
}

internal class DesktopStartupBackend(
    private val adbLocator: () -> Path? = ::locateSystemAdb,
) : StartupBackend {
    override suspend fun listDevices(): StartupBackendResult<List<StartupDevice>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val result = AdbDeviceRefresher(adb).refresh()) {
            is StudioResult.Failure -> StartupBackendResult.Failure(result.error.message)
            is StudioResult.Success ->
                StartupBackendResult.Success(
                    result.value.map { device ->
                        StartupDevice(
                            serial = device.serial,
                            name = device.model?.replace('_', ' ') ?: device.serial,
                            online = device.state == AdbDeviceState.ONLINE,
                        )
                    },
                )
        }
    }

    override suspend fun listTargets(serial: String): StartupBackendResult<List<StartupTarget>> {
        val adb = adbLocator() ?: return missingAdb()
        return when (val catalog = AdbTargetCatalog(adb).refresh(serial)) {
            is StudioResult.Failure -> StartupBackendResult.Failure(catalog.error.message)
            is StudioResult.Success -> {
                val output =
                    try {
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
                    } catch (exception: Exception) {
                        return StartupBackendResult.Failure(exception.message ?: "Unable to resolve launcher activities.")
                    }
                val capabilities = catalog.value.packages.associateBy { it.packageName }
                val targets =
                    parseLauncherComponents(output).map { component ->
                        val packageName = component.substringBefore('/')
                        StartupTarget(
                            packageName = packageName,
                            componentName = component,
                            debuggable = capabilities[packageName]?.debuggable == true,
                        )
                    }
                StartupBackendResult.Success(targets)
            }
        }
    }

    override fun openRunner(
        serial: String,
        target: StartupTarget,
    ): StartupBackendResult<StartupExperimentRunner> {
        val adb = adbLocator() ?: return missingAdb()
        return StartupBackendResult.Success(StartupExperimentRunner(adb, serial, target))
    }

    private suspend fun executeShell(
        adb: Path,
        serial: String,
        arguments: List<String>,
    ): String =
        DefaultAdbClient(adb)
            .shell(serial, arguments, COMMAND_TIMEOUT, MAX_OUTPUT)
            .stdout

    private fun missingAdb(): StartupBackendResult.Failure =
        StartupBackendResult.Failure("Android SDK Platform Tools were not found. Configure ANDROID_HOME or ANDROID_SDK_ROOT.")

    private companion object {
        const val MAX_OUTPUT = 4 * 1024 * 1024
        val COMMAND_TIMEOUT = 30.seconds

        fun locateSystemAdb(): Path? {
            val platform = (SystemHostPlatformDetector().detect() as? StudioResult.Success)?.value ?: return null
            return (SystemAdbLocator(platform).locate() as? StudioResult.Success)?.value?.executable
        }
    }
}

internal fun parseLauncherComponents(output: String): List<String> =
    output
        .lineSequence()
        .map(String::trim)
        .filter { COMPONENT.matches(it) }
        .distinct()
        .sorted()
        .toList()

private val COMPONENT = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
