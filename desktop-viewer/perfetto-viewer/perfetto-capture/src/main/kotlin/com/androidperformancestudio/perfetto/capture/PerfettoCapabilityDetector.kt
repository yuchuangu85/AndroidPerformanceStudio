package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.perfetto.model.PerfettoDevice
import com.androidperformancestudio.perfetto.model.PerfettoDeviceCapabilities
import com.androidperformancestudio.platform.adb.AdbClient
import com.androidperformancestudio.platform.adb.AdbException
import com.androidperformancestudio.platform.adb.DefaultAdbClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class PerfettoCapabilityDetector(
    private val adbClientFactory: (Path) -> AdbClient = ::DefaultAdbClient,
) {
    suspend fun detect(
        adbPath: String,
        device: PerfettoDevice,
    ): PerfettoDeviceCapabilities {
        var androidSdk = device.androidSdk
        var buildType = "unknown"
        return try {
            val adb = adbClientFactory(Path.of(adbPath))
            val properties =
                adb
                    .shell(
                        device.serial,
                        listOf("sh", "-c", "getprop ro.build.version.sdk; getprop ro.build.type"),
                        5.seconds,
                    ).stdout
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()
            androidSdk = properties.getOrNull(0)?.toIntOrNull() ?: androidSdk
            buildType = properties.getOrNull(1) ?: buildType
            val query = adb.shell(device.serial, listOf("perfetto", "--query", "--long"), 15.seconds)
            PerfettoDeviceCapabilities(
                androidSdk = androidSdk,
                buildType = buildType,
                dataSourceNames = parsePerfettoDataSourceNames(query.stdout),
            )
        } catch (error: AdbException) {
            unavailable(androidSdk, buildType, error)
        } catch (error: IllegalArgumentException) {
            unavailable(androidSdk, buildType, error)
        }
    }

    private fun unavailable(
        androidSdk: Int,
        buildType: String,
        error: Exception,
    ): PerfettoDeviceCapabilities =
        PerfettoDeviceCapabilities(
            androidSdk = androidSdk,
            buildType = buildType,
            queryError = error.message ?: "Unable to query Perfetto data sources",
        )
}

internal fun parsePerfettoDataSourceNames(output: String): Set<String> =
    output
        .substringAfter("DATA SOURCES REGISTERED:", "")
        .substringBefore("TRACING SESSIONS:")
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith("NAME") && !it.startsWith("===") }
        .map { it.substringBefore(' ') }
        .toSet()
