package com.androidperformancestudio.desktop

/** A routed request to show the unified settings window at a specific page. */
public data class SettingsRequest(
    val page: SettingsPage,
    val requestId: Long,
) {
    init {
        require(requestId > 0L) { "Settings request id must be positive" }
    }
}

internal fun shouldOpenSettingsForRequest(request: SettingsRequest?): Boolean = request != null
