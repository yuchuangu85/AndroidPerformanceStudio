package dev.agentperf.adb

object AdbOutputParser {
    fun parseDevices(output: String): List<AdbDevice> =
        output.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull(::parseDevice)
            .toList()

    private fun parseDevice(line: String): AdbDevice? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 2) return null
        val properties = parts.drop(2)
            .mapNotNull { token ->
                val separator = token.indexOf(':')
                if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
            }
            .toMap()

        return AdbDevice(
            serial = parts[0],
            state = when (parts[1]) {
                "device" -> DeviceState.DEVICE
                "offline" -> DeviceState.OFFLINE
                "unauthorized" -> DeviceState.UNAUTHORIZED
                else -> DeviceState.UNKNOWN
            },
            product = properties["product"],
            model = properties["model"],
            transportId = properties["transport_id"]?.toIntOrNull(),
        )
    }
}
