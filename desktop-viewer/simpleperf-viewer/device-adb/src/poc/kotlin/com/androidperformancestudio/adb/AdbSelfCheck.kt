package com.androidperformancestudio.adb

import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.toolchain.HostPlatform
import com.androidperformancestudio.toolchain.JvmProcessRunner
import com.androidperformancestudio.toolchain.ProcessRequest
import com.androidperformancestudio.toolchain.ProcessRunResult
import com.androidperformancestudio.toolchain.SystemHostPlatformDetector
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

suspend fun main() {
    val platform = detectPlatform()
    val configuredPath = System.getenv("APS_ADB_PATH")?.takeIf(String::isNotBlank)?.let(Path::of)
    val location =
        when (val result = SystemAdbLocator(platform).locate(AdbConfiguration(configuredPath))) {
            is StudioResult.Success -> result.value
            is StudioResult.Failure -> fail("${result.error.code}: ${result.error.message}")
        }
    val request =
        ProcessRequest(
            executable = location.executable,
            arguments = listOf("version"),
            timeout = 5.seconds,
        )
    when (val result = JvmProcessRunner().run(request)) {
        is ProcessRunResult.Completed -> {
            println("ADB_SELF_CHECK_OK")
            println("path=${location.executable}")
            println("source=${location.source}")
            print(result.output.stdout.text)
        }
        is ProcessRunResult.Failed -> fail("${result.error.code}: ${result.error.message}")
    }
    printConnectedDevices(location.executable)
}

private suspend fun printConnectedDevices(adbExecutable: Path) {
    when (val result = AdbDeviceRefresher(adbExecutable).refresh()) {
        is StudioResult.Success -> {
            println("deviceCount=${result.value.size}")
            for (device in result.value) {
                println("device=${device.serial} state=${device.rawState} model=${device.model.orEmpty()}")
                if (device.state == AdbDeviceState.ONLINE) printDeviceProperties(adbExecutable, device.serial)
            }
        }
        is StudioResult.Failure -> fail("${result.error.code}: ${result.error.message}")
    }
}

private suspend fun printDeviceProperties(
    adbExecutable: Path,
    serial: String,
) {
    when (val result = AdbDevicePropertiesReader(adbExecutable).read(serial)) {
        is StudioResult.Success -> {
            val properties = result.value
            println("properties=${properties.model} android=${properties.androidVersion} sdk=${properties.sdkInt}")
            println("abis=${properties.abis.joinToString()}")
            printCapabilities(adbExecutable, properties)
        }
        is StudioResult.Failure -> fail("${result.error.code}: ${result.error.message}")
    }
}

private suspend fun printCapabilities(
    adbExecutable: Path,
    properties: AndroidDeviceProperties,
) {
    when (val result = AdbDeviceCapabilityDetector(adbExecutable).detect(properties)) {
        is StudioResult.Success -> {
            val capabilities = result.value
            println("readiness=${capabilities.readiness} root=${capabilities.rootAccess}")
            println("profilingScope=${capabilities.profilingScope}")
            println("simpleperf=${capabilities.simpleperfVersion.orEmpty()}")
            println("limitations=${capabilities.limitations.joinToString()}")
            printTargets(adbExecutable, properties.serial)
        }
        is StudioResult.Failure -> fail("${result.error.code}: ${result.error.message}")
    }
}

private suspend fun printTargets(
    adbExecutable: Path,
    serial: String,
) {
    when (val result = AdbTargetCatalog(adbExecutable).refresh(serial)) {
        is StudioResult.Success -> {
            println("packageCount=${result.value.packages.size}")
            val capturablePackages = result.value.packages.filter { it.debuggable || it.profileableByShell }
            println("capturablePackageCount=${capturablePackages.size}")
            println("capturablePackages=${capturablePackages.joinToString { it.packageName }}")
            println("processCount=${result.value.processes.size}")
        }
        is StudioResult.Failure -> fail("${result.error.code}: ${result.error.message}")
    }
}

private fun detectPlatform(): HostPlatform =
    when (val result = SystemHostPlatformDetector().detect()) {
        is StudioResult.Success -> result.value
        is StudioResult.Failure -> fail("${result.error.code}: ${result.error.message}")
    }

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
