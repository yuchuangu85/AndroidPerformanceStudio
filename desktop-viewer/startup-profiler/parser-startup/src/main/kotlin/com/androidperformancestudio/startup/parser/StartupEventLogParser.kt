@file:Suppress("MagicNumber", "MaxLineLength")

package com.androidperformancestudio.startup.parser

public data class StartupEventMetrics(
    val displayedTimeMs: Long? = null,
    val fullyDrawnTimeMs: Long? = null,
    val warnings: List<String> = emptyList(),
)

public class StartupEventLogParser {
    public fun parse(
        output: String,
        packageName: String,
    ): StartupEventMetrics {
        var displayed: Long? = null
        var fullyDrawn: Long? = null
        output.lineSequence().filter { it.contains(packageName) }.forEach { line ->
            when {
                line.contains("wm_fully_drawn_time", ignoreCase = true) ||
                    line.contains("Fully drawn", ignoreCase = true) -> fullyDrawn = line.lastDurationMillis() ?: fullyDrawn
                line.contains("wm_activity_launch_time", ignoreCase = true) ||
                    line.contains("Displayed", ignoreCase = true) -> displayed = line.lastDurationMillis() ?: displayed
            }
        }
        return StartupEventMetrics(displayedTimeMs = displayed, fullyDrawnTimeMs = fullyDrawn)
    }

    private fun String.lastDurationMillis(): Long? {
        val explicit =
            DURATION
                .findAll(this)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        if (explicit != null) return explicit
        val payload = substringAfterLast('[').substringBefore(']')
        return NUMBER.findAll(payload).mapNotNull { it.value.toLongOrNull() }.lastOrNull()
    }

    private companion object {
        val DURATION = Regex("(\\d+)ms", RegexOption.IGNORE_CASE)
        val NUMBER = Regex("\\d+")
    }
}
