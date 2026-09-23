package com.androidperformancestudio.platform.adb

/** Validated argument vectors for generic ADB operations shared by profiler feature adapters. */
public object AdbCommandArguments {
    public fun forward(
        serial: String,
        local: String,
        remote: String,
    ): List<String> =
        devicePrefix(serial) + listOf(
            "forward",
            AdbInputValidator.requireForwardEndpoint(local),
            AdbInputValidator.requireForwardEndpoint(remote),
        )

    public fun removeForward(
        serial: String,
        local: String,
    ): List<String> =
        devicePrefix(serial) + listOf("forward", "--remove", AdbInputValidator.requireForwardEndpoint(local))

    public fun shell(
        serial: String,
        arguments: List<String>,
    ): List<String> {
        require(arguments.isNotEmpty()) { "ADB shell command must not be empty" }
        return devicePrefix(serial) + listOf("shell") + arguments
    }

    public fun execOut(
        serial: String,
        arguments: List<String>,
    ): List<String> {
        require(arguments.isNotEmpty()) { "ADB exec-out command must not be empty" }
        return devicePrefix(serial) + listOf("exec-out") + arguments
    }

    public fun property(serial: String, property: String): List<String> {
        require(PROPERTY_PATTERN.matches(property)) { "Invalid Android property name" }
        return shell(serial, listOf("getprop", property))
    }

    public fun pidOf(serial: String, packageName: String): List<String> =
        shell(serial, listOf("pidof", requirePackageName(packageName)))

    public fun screenshot(serial: String): List<String> = execOut(serial, listOf("screencap", "-p"))

    public fun foregroundActivity(serial: String): List<String> =
        shell(serial, listOf("dumpsys", "activity", "activities"))

    public fun requirePackageName(packageName: String): String =
        packageName.takeIf(PACKAGE_PATTERN::matches) ?: throw AdbInputException("Invalid Android package name")

    public fun devicePrefix(serial: String): List<String> = listOf("-s", AdbInputValidator.requireSerial(serial))

    private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val PROPERTY_PATTERN = Regex("[A-Za-z0-9_.-]+")
}
