package com.androidperformancestudio.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfLocalizationTest {
    @Test
    fun `Chinese localization covers primary workflow labels`() {
        assertEquals(
            "设备与目标",
            translateSimpleperfText("Device & Target", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "继续采集",
            translateSimpleperfText("Continue to Capture", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "获取数据",
            translateSimpleperfText("Get data", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "丢失样本：12",
            translateSimpleperfText("Lost samples: 12", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `English localization keeps source text`() {
        assertEquals(
            "Device & Target",
            translateSimpleperfText("Device & Target", SimpleperfLanguage.ENGLISH),
        )
    }

    @Test
    fun `Chinese localization composes flame tooltip labels from structured keys`() {
        assertEquals(
            "类别：Rendering",
            translateSimpleperfText("Category: Rendering", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "包含 12 · 独占 3",
            translateSimpleperfText("Inclusive 12 · Self 3", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "样本 4 · 25.00%",
            translateSimpleperfText("Samples 4 · 25.00%", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "预览范围权重：7",
            translateSimpleperfText("Preview range weight: 7", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "火焰图调用栈",
            translateSimpleperfText("Flame graph call stacks", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "火焰图搜索",
            translateSimpleperfText("Flame graph search", SimpleperfLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "上下文操作尚不可用。按 Escape 关闭提示。",
            translateSimpleperfText(
                "Context actions are not available yet. Press Escape to dismiss.",
                SimpleperfLanguage.SIMPLIFIED_CHINESE,
            ),
        )
        assertEquals(
            "源码和反汇编详情尚不可用。按 Escape 关闭提示。",
            translateSimpleperfText(
                "Source and disassembly details are not available yet. Press Escape to dismiss.",
                SimpleperfLanguage.SIMPLIFIED_CHINESE,
            ),
        )
        assertEquals(
            "单击帧将其选中。火焰图宽度始终表示完整分析样本集。",
            translateSimpleperfText(
                "Click a frame to select it. Flame widths always represent the full analyzed sample set.",
                SimpleperfLanguage.SIMPLIFIED_CHINESE,
            ),
        )
    }

    @Test
    fun `Chinese localization covers reason specific flame empty states and recoveries`() {
        val expected =
            mapOf(
                "The selected thread has no samples." to "所选线程没有样本。",
                "The selected time range contains no samples." to "所选时间范围内没有样本。",
                "The preview range contains no samples." to "预览范围内没有样本。",
                "Search removed all samples." to "搜索条件排除了所有样本。",
                "The implementation filter removed all samples." to "实现类型筛选排除了所有样本。",
                "Stack transforms removed all samples." to "调用栈变换排除了所有样本。",
                "The profile does not contain complete call stacks." to "性能数据中没有完整的调用栈。",
                "The flame graph could not be projected." to "无法生成火焰图。",
                "Show all threads" to "显示所有线程",
                "Reset time range" to "重置时间范围",
                "Cancel preview" to "取消预览",
                "Clear search" to "清除搜索",
                "Show all implementations" to "显示所有实现类型",
                "Undo transform" to "撤销变换",
                "Review data quality" to "查看数据质量",
                "Retry projection" to "重试生成",
            )

        expected.forEach { (english, chinese) ->
            assertEquals(chinese, translateSimpleperfText(english, SimpleperfLanguage.SIMPLIFIED_CHINESE))
        }
    }

    @Test
    fun `workspace pages do not expose language or theme controls`() {
        val homeScreen =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/HomeScreen.kt"),
            )

        assertFalse(homeScreen.contains("SimpleperfSettingsBar"))
        assertFalse(homeScreen.contains("onThemePreferenceChanged"))
        assertFalse(homeScreen.contains("onLanguagePreferenceChanged"))
    }

    @Test
    fun `flame accessibility semantics resolve through the active localization`() {
        val panel =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphPanel.kt"),
            )
        val toolbar =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphToolbar.kt"),
            )

        assertTrue(panel.contains("localizedSimpleperfText(\"Flame graph call stacks\")"))
        assertTrue(toolbar.contains("localizedSimpleperfText(\"Flame graph search\")"))
    }
}
