package dev.agentperf.desktop

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
