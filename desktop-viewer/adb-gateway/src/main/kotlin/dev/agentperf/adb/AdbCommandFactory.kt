package dev.agentperf.adb

object AdbCommandFactory {
    private val safePackageName = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val safeDeviceValue = Regex("[A-Za-z0-9_.:-]+")
    private val safeSocketName = Regex("[A-Za-z0-9_.-]+")

    fun forward(
        serial: String,
        hostPort: Int,
        socketName: String,
    ): List<String> {
        require(hostPort in 1..65535) { "Host port must be between 1 and 65535" }
        require(safeSocketName.matches(socketName)) { "Unsafe local abstract socket name" }
        return devicePrefix(serial) + listOf(
            "forward",
            "tcp:$hostPort",
            "localabstract:$socketName",
        )
    }

    fun readSession(
        serial: String,
        packageName: String,
    ): List<String> {
        require(safePackageName.matches(packageName)) { "Invalid Android package name" }
        return devicePrefix(serial) + listOf(
            "shell",
            "run-as",
            packageName,
            "cat",
            "files/agentperf/session.json",
        )
    }

    fun foregroundActivity(serial: String): List<String> =
        devicePrefix(serial) + listOf(
            "shell",
            "dumpsys",
            "activity",
            "activities",
        )

    fun dumpHierarchy(serial: String): List<String> =
        devicePrefix(serial) + listOf(
            "exec-out",
            "uiautomator",
            "dump",
            "/dev/tty",
        )

    fun captureScreenshot(serial: String): List<String> =
        devicePrefix(serial) + listOf(
            "exec-out",
            "screencap",
            "-p",
        )

    fun removeForward(
        serial: String,
        hostPort: Int,
    ): List<String> {
        require(hostPort in 1..65535) { "Host port must be between 1 and 65535" }
        return devicePrefix(serial) + listOf(
            "forward",
            "--remove",
            "tcp:$hostPort",
        )
    }

    private fun devicePrefix(serial: String): List<String> {
        require(safeDeviceValue.matches(serial)) { "Invalid device serial" }
        return listOf("-s", serial)
    }
}
