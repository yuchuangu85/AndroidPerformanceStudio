package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
            UiLanguage.SIMPLIFIED_CHINESE,
            LanguagePreference.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            UiLanguage.ENGLISH,
            LanguagePreference.SYSTEM.resolve(Locale.ENGLISH),
        )
        assertEquals(
            UiLanguage.SIMPLIFIED_CHINESE,
            LanguagePreference.SIMPLIFIED_CHINESE.resolve(Locale.ENGLISH),
        )
        assertEquals(
            UiLanguage.ENGLISH,
            LanguagePreference.ENGLISH.resolve(Locale.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `compose resources cover menus capture targets and formatted values`() {
        val chinese = UiLanguage.SIMPLIFIED_CHINESE
        val english = UiLanguage.ENGLISH

        assertEquals("设置", localizedStringResource(Res.string.settings, chinese))
        assertEquals("Settings", localizedStringResource(Res.string.settings, english))
        assertEquals("布局检查器设置", localizedStringResource(Res.string.layout_inspector_settings, chinese))
        assertEquals("Actions", localizedStringResource(Res.string.actions, english))
        assertEquals("显示全部可见视图边缘", localizedStringResource(Res.string.show_visible_view_bounds, chinese))
        assertEquals("Import screenshot", localizedStringResource(Res.string.import_screenshot, english))
        assertEquals("前台应用", localizedStringResource(Res.string.foreground_app, chinese))
        assertEquals("System UI", localizedStringResource(Res.string.system_ui, english))
        assertEquals(
            "320 MiB (10×)",
            localizedStringResource(Res.string.archive_limit_value, english, 320, 10),
        )
        assertEquals(
            "归档已导入：\n/tmp/capture.apinspect",
            localizedStringResource(
                Res.string.archive_import_succeeded,
                chinese,
                "/tmp/capture.apinspect",
            ),
        )
        assertEquals(
            "Archive exported without raw Visible Window Views attachments:\n/tmp/capture.apinspect",
            localizedStringResource(
                Res.string.archive_export_succeeded_no_attachments,
                english,
                "/tmp/capture.apinspect",
            ),
        )
        assertEquals(
            "android.view.ViewStub 节点存在但当前不可见",
            localizedStringResource(
                Res.string.finding_invisible_node_message,
                chinese,
                "android.view.ViewStub",
            ),
        )
    }

    @Test
    fun `layout inspector code uses generated resources without string facades`() {
        val sourceRoot = Path.of("src/main/kotlin")
        val kotlinSources =
            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .toList()
            }

        assertTrue(kotlinSources.any { Files.readString(it).contains("Res.string.") })
    }
}
