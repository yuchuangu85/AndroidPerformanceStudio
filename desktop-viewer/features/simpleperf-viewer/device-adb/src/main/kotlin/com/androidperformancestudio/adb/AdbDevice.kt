package com.androidperformancestudio.adb

import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult

enum class AdbDeviceState {
    ONLINE,
    OFFLINE,
    UNAUTHORIZED,
    NO_PERMISSIONS,
    UNKNOWN,
}

data class AdbDevice(
    val serial: String,
    val state: AdbDeviceState,
    val rawState: String,
    val properties: Map<String, String> = emptyMap(),
    val statusDetail: String? = null,
) {
    val model: String?
        get() = properties["model"]
}

class AdbDevicesParser {
    fun parse(output: String): StudioResult<List<AdbDevice>> {
        val devices = mutableListOf<AdbDevice>()
        output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it == DEVICES_HEADER }
            .forEachIndexed { index, line ->
                val parsed = parseLine(line, index + 1)
                if (parsed is StudioResult.Failure) return parsed
                devices += (parsed as StudioResult.Success).value
            }
        return StudioResult.Success(devices)
    }

    private fun parseLine(
        line: String,
        lineNumber: Int,
    ): StudioResult<AdbDevice> {
        val serial = line.substringBeforeWhitespace()
        val details = line.removePrefix(serial).trim()
        if (serial.isEmpty() || details.isEmpty()) return malformedLine(lineNumber, line)
        return if (details.startsWith(NO_PERMISSIONS_STATE)) {
            StudioResult.Success(parseNoPermissions(serial, details))
        } else {
            StudioResult.Success(parseRegularDevice(serial, details))
        }
    }

    private fun parseNoPermissions(
        serial: String,
        details: String,
    ): AdbDevice =
        AdbDevice(
            serial = serial,
            state = AdbDeviceState.NO_PERMISSIONS,
            rawState = NO_PERMISSIONS_STATE,
            statusDetail =
                details
                    .removePrefix(NO_PERMISSIONS_STATE)
                    .trim()
                    .removePrefix("(")
                    .substringBefore("); see")
                    .removeSuffix(")")
                    .trim()
                    .ifEmpty { null },
        )

    private fun parseRegularDevice(
        serial: String,
        details: String,
    ): AdbDevice {
        val tokens = details.split(WHITESPACE)
        val rawState = tokens.first()
        val properties =
            tokens.drop(1).mapNotNull(::parseProperty).toMap()
        return AdbDevice(
            serial = serial,
            state = rawState.toDeviceState(),
            rawState = rawState,
            properties = properties,
        )
    }

    private fun parseProperty(token: String): Pair<String, String>? {
        val delimiter = token.indexOf(':')
        if (delimiter <= 0 || delimiter == token.lastIndex) return null
        return token.substring(0, delimiter) to token.substring(delimiter + 1)
    }

    private fun malformedLine(
        lineNumber: Int,
        line: String,
    ): StudioResult.Failure =
        StudioResult.Failure(
            StudioError(
                category = ErrorCategory.DATA_VALIDATION,
                code = "ADB_DEVICES_LINE_MALFORMED",
                message = "Malformed adb devices line $lineNumber: $line",
            ),
        )

    private fun String.substringBeforeWhitespace(): String = takeWhile { !it.isWhitespace() }

    private fun String.toDeviceState(): AdbDeviceState =
        when (this) {
            "device" -> AdbDeviceState.ONLINE
            "offline" -> AdbDeviceState.OFFLINE
            "unauthorized" -> AdbDeviceState.UNAUTHORIZED
            else -> AdbDeviceState.UNKNOWN
        }

    companion object {
        private const val DEVICES_HEADER = "List of devices attached"
        private const val NO_PERMISSIONS_STATE = "no permissions"
        private val WHITESPACE = Regex("\\s+")
    }
}
