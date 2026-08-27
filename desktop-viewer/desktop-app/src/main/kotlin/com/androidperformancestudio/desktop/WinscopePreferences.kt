package com.androidperformancestudio.desktop

import com.androidperformancestudio.winscope.app.WinscopeEnginePreference
import java.util.prefs.Preferences

internal data class WinscopeUiSettings(
    val engine: WinscopeEnginePreference = WinscopeEnginePreference.NATIVE,
)

internal class WinscopePreferencesStore(
    private val readValue: (String) -> String?,
    private val writeValue: (String, String) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun load(): WinscopeUiSettings =
        WinscopeUiSettings(
            engine = WinscopeEnginePreference.parse(readValue(ENGINE_KEY)),
        )

    fun save(settings: WinscopeUiSettings): Boolean =
        runCatching {
            writeValue(ENGINE_KEY, settings.engine.storageValue)
            flush()
        }.isSuccess

    companion object {
        private const val ENGINE_KEY = "winscope.engine"

        fun desktop(): WinscopePreferencesStore {
            val preferences =
                runCatching {
                    Preferences.userNodeForPackage(WinscopePreferencesStore::class.java)
                }.getOrNull()
            return WinscopePreferencesStore(
                readValue = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                writeValue = { key, value ->
                    checkNotNull(preferences) { "Winscope preferences are unavailable" }
                    preferences.put(key, value)
                },
                flush = { checkNotNull(preferences).flush() },
            )
        }
    }
}
