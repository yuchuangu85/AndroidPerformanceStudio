package dev.agentperf.desktop

internal enum class ThemePreference(
    val storageValue: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStorage(value: String?): ThemePreference =
            entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}
