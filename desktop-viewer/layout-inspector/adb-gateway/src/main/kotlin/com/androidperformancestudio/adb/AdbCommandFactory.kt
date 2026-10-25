package com.androidperformancestudio.adb

import com.androidperformancestudio.platform.adb.AdbCommandArguments
import com.androidperformancestudio.platform.adb.AdbInputException

/** Layout Inspector-only ADB command construction; generic operations delegate to adb-core. */
object AdbCommandFactory {
    private val safeSocketName = Regex("[A-Za-z0-9_.-]+")

    fun forward(
        serial: String,
        hostPort: Int,
        socketName: String,
    ): List<String> {
        require(hostPort in 1..65535) { "Host port must be between 1 and 65535" }
        require(safeSocketName.matches(socketName)) { "Unsafe local abstract socket name" }
        return AdbCommandArguments.forward(serial, "tcp:$hostPort", "localabstract:$socketName")
    }


    fun readSession(
        serial: String,
        packageName: String,
    ): List<String> {
        requirePackageName(packageName)
        return devicePrefix(serial) + listOf(
            "shell",
            "run-as",
            packageName,
            "cat",
            "files/agentperf/session.json",
        )
    }

    fun foregroundActivity(serial: String): List<String> = AdbCommandArguments.foregroundActivity(serial)

    fun dumpHierarchy(serial: String): List<String> =
        devicePrefix(serial) + listOf(
            "exec-out",
            "uiautomator",
            "dump",
            "/dev/tty",
        )

    fun dumpVisibleWindowViews(serial: String): List<String> =
        devicePrefix(serial) + listOf(
            "exec-out",
            "cmd",
            "window",
            "dump-visible-window-views",
        )

    fun captureScreenshot(serial: String): List<String> = AdbCommandArguments.screenshot(serial)

    fun removeForward(
        serial: String,
        hostPort: Int,
    ): List<String> {
        require(hostPort in 1..65535) { "Host port must be between 1 and 65535" }
        return AdbCommandArguments.removeForward(serial, "tcp:$hostPort")
    }


    fun getProperty(serial: String, property: String): List<String> =
        AdbCommandArguments.property(serial, property)

    fun pidOf(serial: String, packageName: String): List<String> =
        AdbCommandArguments.pidOf(serial, packageName)


    fun packagePaths(serial: String, packageName: String): List<String> {
        requirePackageName(packageName)
        return devicePrefix(serial) + listOf("shell", "pm", "path", packageName)
    }

    fun pullPackageApk(serial: String, remotePath: String, localPath: String): List<String> {
        require(
            remotePath.startsWith("/data/app/") && remotePath.endsWith(".apk") &&
                safePackageApkPath.matches(remotePath) && remotePath.split('/').none { it == ".." },
        ) { "Unsafe package APK path" }
        require(localPath.isNotBlank())
        return devicePrefix(serial) + listOf("pull", remotePath, localPath)
    }

    fun runAsPwd(serial: String, packageName: String): List<String> =
        runAs(serial, packageName, "pwd")

    fun readUnixSockets(serial: String): List<String> =
        devicePrefix(serial) + listOf("shell", "cat", "/proc/net/unix")

    fun push(serial: String, localPath: String, remotePath: String): List<String> {
        require(localPath.isNotBlank())
        requireSafeRemotePath(remotePath)
        return devicePrefix(serial) + listOf("push", localPath, remotePath)
    }

    fun runAsCopy(serial: String, packageName: String, from: String, to: String): List<String> {
        requireSafeRemotePath(from)
        requireSafeRemotePath(to)
        return runAs(serial, packageName, "cp", from, to)
    }

    fun runAsCopyToAppDataRoot(serial: String, packageName: String, from: String, fileName: String): List<String> {
        requireSafeRemotePath(from)
        requireSafeAppDataFileName(fileName)
        return runAs(serial, packageName, "cp", from, fileName)
    }

    fun runAsMkdir(serial: String, packageName: String, path: String): List<String> {
        requireSafeRemotePath(path)
        return runAs(serial, packageName, "mkdir", "-p", path)
    }

    fun runAsChmod(serial: String, packageName: String, mode: String, path: String): List<String> {
        require(mode in setOf("400", "444", "500", "700")) { "Unsafe file mode" }
        requireSafeRemotePath(path)
        return runAs(serial, packageName, "chmod", mode, path)
    }

    fun runAsChmodAppDataRootFile(serial: String, packageName: String, mode: String, fileName: String): List<String> {
        require(mode in setOf("400", "444", "500", "700")) { "Unsafe file mode" }
        requireSafeAppDataFileName(fileName)
        return runAs(serial, packageName, "chmod", mode, fileName)
    }

    /**
     * Removes a directory tree owned by the session.
     *
     * A plain rmdir cannot be used for a Compose session directory: ART dexopts the deployed jars into
     * an oat directory of its own, so the deployment is never empty when the files are gone.
     */
    fun runAsRemoveTree(serial: String, packageName: String, path: String): List<String> {
        requireSafeRemotePath(path)
        return runAs(serial, packageName, "rm", "-rf", path)
    }

    fun removeRemote(serial: String, paths: List<String>): List<String> {
        require(paths.isNotEmpty())
        paths.forEach(::requireSafeRemotePath)
        return devicePrefix(serial) + listOf("shell", "rm", "-f") + paths
    }

    /** Reads the most recent device crash entries so a dying target app can be explained. */
    fun readCrashLog(serial: String, maxLines: Int = 400): List<String> {
        require(maxLines in 1..2_000) { "Unsafe crash log line count" }
        return devicePrefix(serial) + listOf("shell", "logcat", "-d", "-b", "crash", "-t", maxLines.toString())
    }

    fun attachAgent(serial: String, pid: Int, agentPath: String, options: String): List<String> {
        require(pid > 0)
        requireSafeRemotePath(agentPath)
        require(options.isNotBlank() && !options.any { it == '\n' || it == '\r' }) { "Unsafe agent options" }
        // adb shell joins its arguments and hands them to the device shell, so the agent options
        // (service.jar;payload.jar;pid;token) must be quoted or the shell splits on ';'.
        val attachArgument = "$agentPath=$options"
        require(!attachArgument.contains('\'')) { "Unsafe agent options" }
        return devicePrefix(serial) + listOf(
            "shell", "cmd", "activity", "attach-agent", pid.toString(), "'" + attachArgument + "'",
        )
    }

    private fun runAs(serial: String, packageName: String, vararg arguments: String): List<String> {
        requirePackageName(packageName)
        return devicePrefix(serial) + listOf("shell", "run-as", packageName) + arguments
    }

    private fun requirePackageName(packageName: String) {
        try {
            AdbCommandArguments.requirePackageName(packageName)
        } catch (error: AdbInputException) {
            throw IllegalArgumentException("Invalid Android package name", error)
        }
    }

    private fun devicePrefix(serial: String): List<String> = AdbCommandArguments.devicePrefix(serial)

    private val safeRemotePath = Regex("/[A-Za-z0-9_./-]+")
    private val safePackageApkPath = Regex("/[A-Za-z0-9_./~+=-]+")
    private val safeAppDataFileName = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")

    private fun requireSafeRemotePath(path: String) {
        require(safeRemotePath.matches(path) && path.split('/').none { it == ".." }) { "Unsafe remote path" }
    }

    private fun requireSafeAppDataFileName(fileName: String) {
        require(safeAppDataFileName.matches(fileName)) { "Unsafe app data file name" }
    }
}
