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
        assertEquals("视图", chinese.view)
        assertEquals("View", english.view)
        assertEquals("隐藏层级结构中的不可见视图", chinese.hideInvisibleHierarchyViews)
        assertEquals("Hide invisible views in hierarchy", english.hideInvisibleHierarchyViews)
        assertEquals("隐藏问题列表中的不可见视图内容", chinese.hideInvisibleFindings)
        assertEquals("Hide invisible-view findings", english.hideInvisibleFindings)
        assertEquals("隐藏层级索引", chinese.hideHierarchyIndices)
        assertEquals("Hide hierarchy indices", english.hideHierarchyIndices)
        assertEquals("显示全部可见视图边缘", chinese.showVisibleViewBounds)
        assertEquals("Show all visible view bounds", english.showVisibleViewBounds)
        assertEquals("文件", chinese.file)
        assertEquals("File", english.file)
        assertEquals("导入", chinese.importArchive)
        assertEquals("Import", english.importArchive)
        assertEquals("导出", chinese.exportArchive)
        assertEquals("Export", english.exportArchive)
        assertEquals("离线归档", chinese.offlineArchive)
        assertEquals("Offline archive", english.offlineArchive)
        assertEquals(
            "android.view.ViewStub 节点存在但当前不可见",
            chinese.findingMessage("layout.invisible-node", arguments, "fallback"),
        )
        assertEquals(
            "android.view.ViewStub exists but is currently invisible",
            english.findingMessage("layout.invisible-node", arguments, "fallback"),
        )
    }

    @Test
    fun `device selection errors follow the selected language`() {
        val raw = "Expected exactly one authorized device, found 2"

        assertEquals(
            "需要且只能连接一台已授权设备，当前检测到 2 台",
            ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE).connectionError(raw),
        )
        assertEquals(
            raw,
            ViewerStrings.forLanguage(ViewerLanguage.ENGLISH).connectionError(raw),
        )
    }
}
