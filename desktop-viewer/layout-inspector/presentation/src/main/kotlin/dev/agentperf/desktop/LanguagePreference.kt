package dev.agentperf.desktop

import java.util.prefs.Preferences

internal enum class ViewerLanguage {
    SIMPLIFIED_CHINESE,
    ENGLISH,
}

internal enum class LanguagePreference(
    val storageValue: String,
) {
    SYSTEM("system"),
    SIMPLIFIED_CHINESE("simplified_chinese"),
    ENGLISH("english"),
    ;

    fun resolve(systemLanguageTag: String): ViewerLanguage = when (this) {
        SYSTEM -> if (systemLanguageTag.startsWith("zh", ignoreCase = true)) {
            ViewerLanguage.SIMPLIFIED_CHINESE
        } else {
            ViewerLanguage.ENGLISH
        }
        SIMPLIFIED_CHINESE -> ViewerLanguage.SIMPLIFIED_CHINESE
        ENGLISH -> ViewerLanguage.ENGLISH
    }

    companion object {
        fun fromStorage(value: String?): LanguagePreference =
            entries.firstOrNull { it.storageValue == value?.lowercase() } ?: SYSTEM
    }
}

internal class LanguagePreferenceStore(
    private val readValue: () -> String?,
    private val writeValue: (String) -> Unit,
) {
    fun load(): LanguagePreference = LanguagePreference.fromStorage(readValue())

    fun save(preference: LanguagePreference) {
        writeValue(preference.storageValue)
    }

    companion object {
        private const val KEY = "language"

        fun desktop(): LanguagePreferenceStore {
            val preferences = runCatching {
                Preferences.userNodeForPackage(LanguagePreferenceStore::class.java)
            }.getOrNull()
            return LanguagePreferenceStore(
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
