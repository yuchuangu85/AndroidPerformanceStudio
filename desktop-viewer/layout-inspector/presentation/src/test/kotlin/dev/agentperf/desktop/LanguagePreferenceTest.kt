package dev.agentperf.desktop

import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `strings cover settings menu and known finding messages`() {
        val chinese = ViewerStrings.forLanguage(UiLanguage.SIMPLIFIED_CHINESE)
        val english = ViewerStrings.forLanguage(UiLanguage.ENGLISH)
        val arguments = mapOf("className" to "android.view.ViewStub")

        assertEquals("设置", chinese.settings)
        assertEquals("Settings", english.settings)
        assertEquals("布局检查器设置", chinese.layoutInspectorSettings)
        assertEquals("Layout Inspector Settings", english.layoutInspectorSettings)
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
        assertEquals("显示层级结构中的显示按钮", chinese.showHierarchyLayerVisibilityButtons)
        assertEquals("Show visibility buttons in hierarchy", english.showHierarchyLayerVisibilityButtons)
        assertEquals("显示全部可见视图边缘", chinese.showVisibleViewBounds)
        assertEquals("Show all visible view bounds", english.showVisibleViewBounds)
        assertEquals("文件", chinese.file)
        assertEquals("File", english.file)
        assertEquals("导入归档", chinese.importArchive)
        assertEquals("Import archive", english.importArchive)
        assertEquals("导入截图", chinese.importScreenshot)
        assertEquals("Import screenshot", english.importScreenshot)
        assertEquals("导出", chinese.exportArchive)
        assertEquals("Export", english.exportArchive)
        assertEquals("捕获归档", chinese.captureArchive)
        assertEquals("Capture archive", english.captureArchive)
        assertEquals("目标", chinese.captureTarget)
        assertEquals("Target", english.captureTarget)
        assertEquals("前台应用", chinese.captureTargetLabel(CaptureTargetMode.FOREGROUND_APP))
        assertEquals("Foreground app", english.captureTargetLabel(CaptureTargetMode.FOREGROUND_APP))
        assertEquals("系统界面", chinese.captureTargetLabel(CaptureTargetMode.SYSTEM_UI))
        assertEquals("System UI", english.captureTargetLabel(CaptureTargetMode.SYSTEM_UI))
        assertEquals("布局快照大小上限", chinese.layoutSnapshotArchiveLimit)
        assertEquals("Layout snapshot size limit", english.layoutSnapshotArchiveLimit)
        assertEquals(
            "320 MiB (10×)",
            english.archiveLimitValue(CaptureArchiveLimits(snapshotSizeMultiplier = 10)),
        )
        assertEquals("离线归档", chinese.offlineArchive)
        assertEquals("Offline archive", english.offlineArchive)
        assertEquals("导入成功", chinese.importArchiveSucceededTitle)
        assertEquals("Import succeeded", english.importArchiveSucceededTitle)
        assertEquals("截图已导入", chinese.importScreenshotSucceededTitle)
        assertEquals("Screenshot imported", english.importScreenshotSucceededTitle)
        assertEquals("导出失败", chinese.exportArchiveFailedTitle)
        assertEquals("Export failed", english.exportArchiveFailedTitle)
        assertEquals(
            "归档已导入：\n/tmp/capture.apinspect",
            chinese.archiveImportSucceeded("/tmp/capture.apinspect"),
        )
        assertEquals(
            "已为当前布局导入截图：\n/tmp/manual.png",
            chinese.screenshotImportSucceeded("/tmp/manual.png"),
        )
        assertEquals(
            "Archive exported without raw Visible Window Views attachments:\n/tmp/capture.apinspect",
            english.archiveExportSucceeded(
                path = "/tmp/capture.apinspect",
                rawArtifactsIncluded = false,
            ),
        )
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
            ViewerStrings.forLanguage(UiLanguage.SIMPLIFIED_CHINESE).connectionError(raw),
        )
        assertEquals(
            raw,
            ViewerStrings.forLanguage(UiLanguage.ENGLISH).connectionError(raw),
        )
    }

    @Test
    fun `detail strings use typed resources instead of English lookup maps`() {
        val viewerStrings =
            Files.readString(
                Path.of("src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt"),
            )
        val presenter =
            Files.readString(
                Path.of("src/main/kotlin/dev/agentperf/desktop/NodeDetailsPresenter.kt"),
            )

        assertFalse(viewerStrings.contains("Map<String, StringResource>"))
        assertFalse(viewerStrings.contains("detailSectionResources"))
        assertFalse(viewerStrings.contains("detailLabelResources"))
        assertFalse(presenter.contains("strings.detailSection(\""))
        assertFalse(presenter.contains("strings.detailLabel(\""))
        assertFalse(presenter.contains("row(strings, \""))
    }
}
