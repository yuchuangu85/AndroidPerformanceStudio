package com.androidperformancestudio.platform.adb

object AdbInputValidator {
    private val serialPattern = Regex("[A-Za-z0-9_.:\\[\\]-]+")
    private val endpointPattern = Regex("[A-Za-z0-9_./:\\[\\]-]+")

    fun requireSerial(serial: String): String =
        serial.takeIf { serialPattern.matches(it) && !it.startsWith('-') }
            ?: throw AdbInputException("Invalid ADB device serial")

    fun requireRemotePath(path: String): String =
        path.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?: throw AdbInputException("Invalid remote path")

    fun requireForwardEndpoint(endpoint: String): String =
        endpoint.takeIf(endpointPattern::matches)
            ?: throw AdbInputException("Invalid ADB forward endpoint")
}
