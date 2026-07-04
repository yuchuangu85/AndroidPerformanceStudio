package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanguagePreferenceTest {
    @Test
    fun `missing and invalid preferences default to system`() {
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStorage(null))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStorage(""))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStorage("unsupported"))
    }

    @Test
    fun `preferences resolve explicit or system language`() {
        assertEquals(
            ViewerLanguage.SIMPLIFIED_CHINESE,
            LanguagePreference.SYSTEM.resolve("zh-CN"),
        )
        assertEquals(
            ViewerLanguage.ENGLISH,
            LanguagePreference.SYSTEM.resolve("en-US"),
        )
        assertEquals(
            ViewerLanguage.SIMPLIFIED_CHINESE,
            LanguagePreference.SIMPLIFIED_CHINESE.resolve("en-US"),
        )
        assertEquals(
            ViewerLanguage.ENGLISH,
            LanguagePreference.ENGLISH.resolve("zh-CN"),
        )
    }

    @Test
    fun `store persists and restores explicit language`() {
        var storedValue: String? = null
        val store = LanguagePreferenceStore(
            readValue = { storedValue },
            writeValue = { storedValue = it },
        )

        assertEquals(LanguagePreference.SYSTEM, store.load())

        store.save(LanguagePreference.ENGLISH)

        assertEquals("english", storedValue)
        assertEquals(LanguagePreference.ENGLISH, store.load())
    }

    @Test
    fun `strings cover settings menu and known finding messages`() {
        val chinese = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)
        val english = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH)
        val arguments = mapOf("className" to "android.view.ViewStub")

        assertEquals("设置", chinese.settings)
        assertEquals("Settings", english.settings)
        assertEquals("操作", chinese.actions)
        assertEquals("Actions", english.actions)
        assertEquals(
            "android.view.ViewStub 节点存在但当前不可见",
            chinese.findingMessage("layout.invisible-node", arguments, "fallback"),
        )
        assertEquals(
            "android.view.ViewStub exists but is currently invisible",
            english.findingMessage("layout.invisible-node", arguments, "fallback"),
        )
    }
}
