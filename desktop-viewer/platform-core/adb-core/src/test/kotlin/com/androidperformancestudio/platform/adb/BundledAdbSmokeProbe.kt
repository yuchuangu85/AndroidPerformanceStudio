package com.androidperformancestudio.platform.adb

import com.androidperformancestudio.adb.AdbLocationSource
import com.androidperformancestudio.adb.resolveSystemAdb
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Explicit smoke-task entry point, never run as an ordinary device-free unit test. */
object BundledAdbSmokeProbe {
    @JvmStatic
    fun main(arguments: Array<String>) =
        runBlocking {
            check(System.getenv("PATH").isNullOrEmpty()) { "Smoke probe must not inherit a useful PATH" }
            check(listOf("ADB", "ADB_PATH", "ANDROID_HOME", "ANDROID_SDK_ROOT").all { System.getenv(it) == null }) {
                "Smoke probe must not inherit ADB or SDK configuration"
            }
            val bundle = Path.of(arguments[0]).toRealPath()
            System.setProperty("compose.application.resources.dir", bundle.parent.toString())
            val result = resolveSystemAdb()
            check(result is StudioResult.Success) { "Bundled ADB resolution failed: $result" }
            val location = result.value
            check(location.source == AdbLocationSource.BUNDLED) { "Smoke probe used ${location.source}, not bundled ADB" }
            check(location.executable == bundle.resolve(if (isWindows()) "adb.exe" else "adb"))
            val version =
                JvmHostProcessRunner().executeText(
                    HostProcessRequest(executable = location.executable, arguments = listOf("version")),
                )
            check(version.exitCode == 0 && "Android Debug Bridge version" in version.stdout) { "Bundled ADB cannot start" }
            println("ADB source: ${location.source}; SDK environment absent; PATH empty")
            println(version.stdout.trim())
            if (arguments.getOrNull(1) == "true") {
                val client = DefaultAdbClient(location.executable)
                val online = client.listDevices().filter { it.state == AdbDeviceState.ONLINE }
                check(online.size == 1) { "Device smoke requires exactly one authorized device; found ${online.size}" }
                val memory = client.shell(online.single().serial, listOf("cat", "/proc/meminfo"))
                check(memory.exitCode == 0 && "MemTotal:" in memory.stdout) { "Bundled ADB could not capture device memory data" }
                println("Authorized devices: ${online.size}; captured /proc/meminfo: ${memory.stdout.length} characters")
            }
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
}
