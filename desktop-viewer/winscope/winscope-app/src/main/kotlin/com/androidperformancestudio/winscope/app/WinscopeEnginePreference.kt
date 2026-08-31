package com.androidperformancestudio.winscope.app

/** Selects the integrated APS workspace or the packaged upstream Winscope viewer. */
enum class WinscopeEnginePreference(
    val storageValue: String,
) {
    NEW("new"),
    NATIVE("native"),
    ;

    companion object {
        fun parse(value: String?): WinscopeEnginePreference = entries.firstOrNull { it.storageValue == value?.lowercase() } ?: NATIVE
    }
}
