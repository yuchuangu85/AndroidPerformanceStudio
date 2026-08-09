package com.androidperformancestudio.platform.adb

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
    val product: String? = null,
    val model: String? = null,
    val device: String? = null,
    val transportId: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
    val rawState: String = state.defaultRawState,
    val statusDetail: String? = null,
)

private val AdbDeviceState.defaultRawState: String
    get() =
        when (this) {
            AdbDeviceState.ONLINE -> "device"
            AdbDeviceState.OFFLINE -> "offline"
            AdbDeviceState.UNAUTHORIZED -> "unauthorized"
            AdbDeviceState.NO_PERMISSIONS -> "no permissions"
            AdbDeviceState.UNKNOWN -> "unknown"
        }

class AdbDevicesParser {
    fun parse(output: String): List<AdbDevice> =
        output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(::isNoise)
            .mapIndexed(::parseLine)
            .toList()

    private fun isNoise(line: String): Boolean =
        line == DEVICES_HEADER ||
            line.startsWith("* daemon") ||
            line.startsWith("adb server version") ||
            line.startsWith("ADB server didn't ACK")

    private fun parseLine(
        index: Int,
        line: String,
    ): AdbDevice {
        val serial = line.takeWhile { !it.isWhitespace() }
        val details = line.removePrefix(serial).trim()
        if (serial.isBlank() || details.isBlank()) {
            throw AdbOutputParseException("Malformed adb devices line ${index + 1}: $line")
        }
        return if (details.startsWith(NO_PERMISSIONS_STATE)) {
            parseNoPermissions(serial, details)
        } else {
            parseRegularDevice(serial, details)
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
        val attributes = tokens.drop(1).mapNotNull(::parseAttribute).toMap()
        return AdbDevice(
            serial = serial,
            state =
                when (rawState) {
                    "device" -> AdbDeviceState.ONLINE
                    "offline" -> AdbDeviceState.OFFLINE
                    "unauthorized" -> AdbDeviceState.UNAUTHORIZED
                    else -> AdbDeviceState.UNKNOWN
                },
            product = attributes["product"],
            model = attributes["model"],
            device = attributes["device"],
            transportId = attributes["transport_id"]?.toIntOrNull(),
            attributes = attributes,
            rawState = rawState,
        )
    }

    private fun parseAttribute(token: String): Pair<String, String>? {
        val delimiter = token.indexOf(':')
        if (delimiter <= 0 || delimiter == token.lastIndex) return null
        return token.substring(0, delimiter) to token.substring(delimiter + 1)
    }

    private companion object {
        const val DEVICES_HEADER = "List of devices attached"
        const val NO_PERMISSIONS_STATE = "no permissions"
        val WHITESPACE = Regex("\\s+")
    }
}
