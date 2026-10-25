package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.AdbCommandFactory
import com.androidperformancestudio.adb.ProcessRunner

/**
 * Reads the last device crash that belongs to [packageName].
 *
 * When the injected Compose process disappears mid-session the user needs to know whether the app
 * crashed or the inspector failed, so the failure path quotes the crash's exception line.
 */
fun ProcessRunner.lastTargetCrashReason(serial: String, packageName: String): String? {
    val result = run(AdbCommandFactory.readCrashLog(serial))
    if (result.exitCode != 0) return null
    return lastTargetCrashReasonFromLog(result.stdout, packageName)
}

internal fun lastTargetCrashReasonFromLog(log: String, packageName: String): String? {
    val lines = log.lineSequence().toList()
    val processLine = lines.indexOfLast { line -> line.isProcessHeader(packageName) }
    if (processLine < 0) return null
    return lines
        .drop(processLine + 1)
        .mapNotNull { line -> line.substringAfter("AndroidRuntime:", "").trim().takeIf(String::isNotEmpty) }
        .firstOrNull { message ->
            !message.startsWith("at ") && (message.contains("Exception") || message.contains("Error"))
        }
}

private fun String.isProcessHeader(packageName: String): Boolean {
    val marker = "Process: $packageName"
    val index = indexOf(marker)
    if (index < 0) return false
    val following = getOrNull(index + marker.length)
    return following == null || following == ',' || following.isWhitespace()
}
