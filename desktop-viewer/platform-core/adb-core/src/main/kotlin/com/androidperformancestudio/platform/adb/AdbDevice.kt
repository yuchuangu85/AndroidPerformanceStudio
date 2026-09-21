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
    val manufacturer: String? = null,
    val model: String? = null,
    val device: String? = null,
    val transportId: Int? = null,
    val attributes: Map<String, String> = emptyMap(),
    val rawState: String = state.defaultRawState,
    val statusDetail: String? = null,
)

typealias DeviceTarget = AdbDevice

/** Parsed display identity from Android system properties. */
data class AdbDeviceIdentity(
    val manufacturer: String? = null,
    val model: String? = null,
)

/**
 * Canonical ADB device object delivered to UI subscribers.
 *
 * [serial] is the stable selection identity. [displayName] is presentation-ready, so pages never
 * reconstruct manufacturer/model/serial strings independently.
 */
data class AndroidDeviceInfo(
    val serial: String,
    val deviceName: String,
    val displayName: String,
    val manufacturer: String?,
    val product: String?,
    val device: String?,
    val state: AdbDeviceState,
    val transportId: Int?,
    val rawState: String,
    val statusDetail: String?,
) {
    val online: Boolean get() = state == AdbDeviceState.ONLINE
}

fun AdbDevice.toDeviceInfo(): AndroidDeviceInfo {
    val deviceName = model?.displayPart(allowUnknown = true) ?: serial
    val manufacturerLabel = manufacturer?.displayPart(allowUnknown = false)
    val visibleIdentity = listOfNotNull(manufacturerLabel, deviceName.takeIf { it != serial }).joinToString(" ")
    return AndroidDeviceInfo(
        serial = serial,
        deviceName = deviceName,
        displayName = if (visibleIdentity.isBlank()) serial else "$visibleIdentity($serial)",
        manufacturer = manufacturerLabel,
        product = product,
        device = device,
        state = state,
        transportId = transportId,
        rawState = rawState,
        statusDetail = statusDetail,
    )
}

/** Parses only device identity fields from `adb shell getprop` output. */
fun parseAdbDeviceIdentity(output: String): AdbDeviceIdentity {
    val properties =
        output
            .lineSequence()
            .mapNotNull { line -> PROPERTY_LINE.matchEntire(line.trim()) }
            .associate { match -> match.groupValues[1] to match.groupValues[2].trim() }
    return AdbDeviceIdentity(
        manufacturer = properties[MANUFACTURER_PROPERTY]?.usableManufacturer(),
        model = properties[MODEL_PROPERTY]?.usableModel(),
    )
}

/** Applies available Android system-property identity fields without losing ADB list metadata. */
fun AdbDevice.withIdentity(identity: AdbDeviceIdentity): AdbDevice =
    copy(
        manufacturer = identity.manufacturer ?: manufacturer,
        model = identity.model ?: model,
    )

private fun String.displayPart(allowUnknown: Boolean): String? =
    trim().takeIf { value ->
        value.isNotEmpty() &&
            value.lowercase() !in UNUSABLE_DEVICE_VALUES &&
            (allowUnknown || !value.equals("unknown", ignoreCase = true))
    }

private fun String.usableManufacturer(): String? =
    takeIf { value ->
        value.isNotBlank() &&
            value.lowercase() !in UNUSABLE_DEVICE_VALUES &&
            !value.equals("unknown", ignoreCase = true)
    }

private fun String.usableModel(): String? =
    takeIf { value -> value.isNotBlank() && value.lowercase() !in UNUSABLE_MODEL_VALUES }

private const val MANUFACTURER_PROPERTY = "ro.product.manufacturer"
private const val MODEL_PROPERTY = "ro.product.model"
private val PROPERTY_LINE = Regex("""^\[([^]]+)]\s*:\s*\[(.*)]$""")
private val UNUSABLE_DEVICE_VALUES = setOf("<unknown>", "null", "n/a", "na")
private val UNUSABLE_MODEL_VALUES = setOf("<unknown>", "null", "n/a", "na")

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
            model =
                attributes["model"].usableDeviceLabel()
                    ?: attributes["product"].usableDeviceLabel()
                    ?: attributes["device"].usableDeviceLabel()
                    ?: serial,
            device = attributes["device"],
            transportId = attributes["transport_id"]?.toIntOrNull(),
            attributes = attributes,
            rawState = rawState,
        )
    }

    private fun String?.usableDeviceLabel(): String? =
        this
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() && value.lowercase() !in UNUSABLE_DEVICE_LABELS }

    private fun parseAttribute(token: String): Pair<String, String>? {
        val delimiter = token.indexOf(':')
        if (delimiter <= 0 || delimiter == token.lastIndex) return null
        return token.substring(0, delimiter) to token.substring(delimiter + 1)
    }

    private companion object {
        const val DEVICES_HEADER = "List of devices attached"
        const val NO_PERMISSIONS_STATE = "no permissions"
        val UNUSABLE_DEVICE_LABELS = setOf("unknown", "<unknown>", "null", "n/a", "na")
        val WHITESPACE = Regex("\\s+")
    }
}
