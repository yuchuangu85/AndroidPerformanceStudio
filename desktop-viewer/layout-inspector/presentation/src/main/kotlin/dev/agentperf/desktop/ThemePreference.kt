package dev.agentperf.desktop

import java.util.prefs.Preferences

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

internal class ThemePreferenceStore(
    private val readValue: () -> String?,
    private val writeValue: (String) -> Unit,
) {
    fun load(): ThemePreference = ThemePreference.fromStorage(readValue())

    fun save(preference: ThemePreference) {
        writeValue(preference.storageValue)
    }

    companion object {
        private const val KEY = "theme"

        fun desktop(): ThemePreferenceStore {
            val preferences = runCatching {
                Preferences.userNodeForPackage(ThemePreferenceStore::class.java)
            }.getOrNull()
            return ThemePreferenceStore(
                readValue = {
                    runCatching { preferences?.get(KEY, null) }.getOrNull()
                },
                writeValue = { value ->
                    runCatching { preferences?.put(KEY, value) }
                    Unit
                },
            )
        }
    }
}
