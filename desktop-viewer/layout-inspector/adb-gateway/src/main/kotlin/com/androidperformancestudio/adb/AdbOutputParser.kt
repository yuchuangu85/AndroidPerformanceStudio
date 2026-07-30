package com.androidperformancestudio.adb

object AdbOutputParser {
    private val activityComponents = listOf(
        "mFocusedApp",
        "topResumedActivity",
        "mResumedActivity",
    ).map { field ->
        Regex(
            """$field=ActivityRecord\{[^}]*\su\d+\s+([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/""",
        )
    }

    fun parseForegroundPackage(output: String): String? =
        activityComponents.firstNotNullOfOrNull { pattern ->
            pattern.find(output)?.groupValues?.get(1)
        }
}
