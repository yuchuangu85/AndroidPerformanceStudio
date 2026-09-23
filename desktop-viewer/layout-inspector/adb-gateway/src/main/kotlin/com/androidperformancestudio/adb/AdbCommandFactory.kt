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

    fun runAsMkdir(serial: String, packageName: String, path: String): List<String> {
        requireSafeRemotePath(path)
        return runAs(serial, packageName, "mkdir", "-p", path)
    }

    fun runAsChmod(serial: String, packageName: String, mode: String, path: String): List<String> {
        require(mode in setOf("400", "444", "500", "700")) { "Unsafe file mode" }
        requireSafeRemotePath(path)
        return runAs(serial, packageName, "chmod", mode, path)
    }

    fun runAsRemove(serial: String, packageName: String, paths: List<String>): List<String> {
        require(paths.isNotEmpty())
        paths.forEach(::requireSafeRemotePath)
        return runAs(serial, packageName, "rm", "-f") + paths
    }

    fun runAsRmdir(serial: String, packageName: String, path: String): List<String> {
        requireSafeRemotePath(path)
        return runAs(serial, packageName, "rmdir", path)
    }

    fun removeRemote(serial: String, paths: List<String>): List<String> {
        require(paths.isNotEmpty())
        paths.forEach(::requireSafeRemotePath)
        return devicePrefix(serial) + listOf("shell", "rm", "-f") + paths
    }

    fun attachAgent(serial: String, pid: Int, agentPath: String, options: String): List<String> {
        require(pid > 0)
        requireSafeRemotePath(agentPath)
        require(options.isNotBlank() && !options.any { it == '\n' || it == '\r' }) { "Unsafe agent options" }
        return devicePrefix(serial) + listOf(
            "shell", "cmd", "activity", "attach-agent", pid.toString(), "$agentPath=$options",
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

    private fun requireSafeRemotePath(path: String) {
        require(safeRemotePath.matches(path) && path.split('/').none { it == ".." }) { "Unsafe remote path" }
    }
}
