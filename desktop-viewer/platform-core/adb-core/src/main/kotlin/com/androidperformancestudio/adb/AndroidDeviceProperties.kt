package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult

data class AndroidDeviceProperties(
    val serial: String,
    val model: String,
    val abis: List<String>,
    val sdkInt: Int,
    val androidVersion: String,
)

class AndroidGetpropParser {
    fun parse(
        serial: String,
        output: String,
    ): StudioResult<AndroidDeviceProperties> {
        val properties = parseProperties(output)
        val model = properties[MODEL_PROPERTY].orEmpty().trim()
        val abis = parseAbis(properties)
        val sdkInt = properties[SDK_PROPERTY]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val androidVersion = properties[ANDROID_VERSION_PROPERTY].orEmpty().trim()
        val invalidProperties =
            buildList {
                if (model.isEmpty()) add(MODEL_PROPERTY)
                if (abis.isEmpty()) add(ABI_LIST_PROPERTY)
                if (sdkInt == null) add(SDK_PROPERTY)
                if (androidVersion.isEmpty()) add(ANDROID_VERSION_PROPERTY)
            }
        if (invalidProperties.isNotEmpty()) return invalidProperties(serial, invalidProperties)
        return StudioResult.Success(
            AndroidDeviceProperties(
                serial = serial,
                model = model,
                abis = abis,
                sdkInt = requireNotNull(sdkInt),
                androidVersion = androidVersion,
            ),
        )
    }

    private fun parseProperties(output: String): Map<String, String> =
        output
            .lineSequence()
            .mapNotNull { line -> PROPERTY_LINE.matchEntire(line.trim()) }
            .associate { match -> match.groupValues[1] to match.groupValues[2] }

    private fun parseAbis(properties: Map<String, String>): List<String> {
        val rawAbis =
            properties[ABI_LIST_PROPERTY]
                ?.takeIf(String::isNotBlank)
                ?: properties[LEGACY_ABI_PROPERTY].orEmpty()
        return rawAbis
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    private fun invalidProperties(
        serial: String,
        properties: List<String>,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.DATA_VALIDATION,
                code = "ADB_DEVICE_PROPERTIES_INVALID",
                message = "Invalid adb properties for $serial: ${properties.joinToString()}",
            ),
        )

    companion object {
        private const val MODEL_PROPERTY = "ro.product.model"
        private const val ABI_LIST_PROPERTY = "ro.product.cpu.abilist"
        private const val LEGACY_ABI_PROPERTY = "ro.product.cpu.abi"
        private const val SDK_PROPERTY = "ro.build.version.sdk"
        private const val ANDROID_VERSION_PROPERTY = "ro.build.version.release"
        private val PROPERTY_LINE = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
    }
}
