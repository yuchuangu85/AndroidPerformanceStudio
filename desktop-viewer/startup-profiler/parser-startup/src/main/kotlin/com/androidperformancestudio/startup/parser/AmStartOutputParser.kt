@file:Suppress("MagicNumber")

package com.androidperformancestudio.startup.parser

import com.androidperformancestudio.startup.model.PlatformLaunchMetrics

public data class AmStartParseResult(
    val metrics: PlatformLaunchMetrics,
    val warnings: List<String>,
)

public class AmStartOutputParser {
    public fun parse(output: String): AmStartParseResult {
        val values = linkedMapOf<String, String>()
        val warnings = mutableListOf<String>()
        var complete = false
        output.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val delimiter = line.indexOf(':')
            if (delimiter > 0) {
                val key = line.substring(0, delimiter).trim()
                val value = line.substring(delimiter + 1).trim()
                values[key] = value
                if (key.equals("Warning", ignoreCase = true) || key.equals("Error", ignoreCase = true)) {
                    warnings += "$key: $value"
                }
            } else if (line.startsWith("Warning", ignoreCase = true) || line.startsWith("Error", ignoreCase = true)) {
                warnings += line
            } else if (line.equals("Complete", ignoreCase = true)) {
                complete = true
            }
        }
        val metrics =
            PlatformLaunchMetrics(
                status = values["Status"],
                launchState = values["LaunchState"],
                activity = values["Activity"],
                thisTimeMs = values["ThisTime"].durationMillis(),
                totalTimeMs = values["TotalTime"].durationMillis(),
                waitTimeMs = values["WaitTime"].durationMillis(),
                complete = complete || values["Complete"]?.equals("true", ignoreCase = true) == true,
            )
        if (metrics.totalTimeMs == null) warnings += "am start -W did not report TotalTime."
        if (metrics.status != null && !metrics.status.equals("ok", ignoreCase = true)) {
            warnings += "Activity Manager returned status ${metrics.status}."
        }
        return AmStartParseResult(metrics, warnings.distinct())
    }

    private fun String?.durationMillis(): Long? {
        val value = this?.trim()?.removeSuffix("ms")?.trim() ?: return null
        return value.toLongOrNull()?.takeIf { it >= 0L }
    }
}
