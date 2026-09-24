package com.androidperformancestudio.startup.presentation

import com.androidperformancestudio.startup.model.StartupStatistics
import com.androidperformancestudio.startup.presentation.generated.resources.Res
import com.androidperformancestudio.startup.presentation.generated.resources.cold
import com.androidperformancestudio.startup.presentation.generated.resources.exact
import com.androidperformancestudio.startup.presentation.generated.resources.phase_first_frame_to_fully_drawn
import com.androidperformancestudio.startup.presentation.generated.resources.phase_range
import com.androidperformancestudio.startup.presentation.generated.resources.requested
import com.androidperformancestudio.startup.presentation.generated.resources.run_detail
import com.androidperformancestudio.startup.presentation.generated.resources.startup_profiler
import com.androidperformancestudio.startup.presentation.generated.resources.total
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerLocalizationTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/startup/presentation/StartupProfilerScreen.kt"),
        )

    @Test
    fun `startup profiler screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("run.observedType.localizedLabel(language)"))
        assertTrue(source.contains("milestone.kind.localizedLabel(language)"))
        assertTrue(source.contains("milestone.confidence.localizedLabel(language)"))
        assertTrue(source.contains("phase.localizedName(language)"))
        assertFalse(source.contains("else -> return name"))
    }

    @Test
    fun `startup profiler presentation labels are translated to Chinese`() {
        assertEquals(
            "启动性能分析器",
            localizedStringResource(Res.string.startup_profiler, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "总耗时",
            localizedStringResource(Res.string.total, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "冷启动",
            localizedStringResource(Res.string.cold, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "精确",
            localizedStringResource(Res.string.exact, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "首帧到完全绘制",
            localizedStringResource(Res.string.phase_first_frame_to_fully_drawn, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "进程启动到首帧",
            localizedStringResource(
                Res.string.phase_range,
                UiLanguage.SIMPLIFIED_CHINESE,
                "进程启动",
                "首帧",
            ),
        )
    }

    @Test
    fun `run presentation keeps requested and observed startup types distinct`() {
        assertTrue(source.contains("StudioTableColumn(localizedStringResource(Res.string.requested, language)"))
        assertTrue(source.contains("run.requestedType.localizedLabel(language)"))
        assertTrue(source.contains("run.observedType.localizedLabel(language)"))
        assertEquals("请求类型", localizedStringResource(Res.string.requested, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals(
            "第 2 轮详情 · 请求 冷启动 · 实际 热启动",
            localizedStringResource(Res.string.run_detail, UiLanguage.SIMPLIFIED_CHINESE, 2, "冷启动", "热启动"),
        )
    }

    @Test
    fun `measured runs table keeps selection and horizontal access to real run values`() {
        assertTrue(source.contains("StudioDataTable("))
        assertTrue(source.contains(".horizontalScroll(rememberScrollState())"))
        assertTrue(source.contains(".selectable(selected = selected, onClick = { onSelect(run.id) }"))
        assertTrue(source.contains("TableCell(run.iteration.toString(), RUN_COLUMN_WEIGHT, emphasized = selected)"))
        assertTrue(source.contains("run.platform.totalTimeMs.formatMs()"))
        assertTrue(source.contains("run.platform.displayedTimeMs.formatMs()"))
        assertTrue(source.contains("run.platform.fullyDrawnTimeMs.formatMs()"))
    }

    @Test
    fun `selected run waterfall appears before the runs table and detail`() {
        val results = source.substringAfter("private fun ResultsPane(").substringBefore("private fun MetricCard(")

        assertTrue(results.indexOf("StartupWaterfallChart(selected, language)") < results.indexOf("StudioDataTable("))
        assertTrue(results.indexOf("StudioDataTable(") < results.indexOf("RunDetail(selected, actions, language)"))
        assertTrue(results.indexOf("RunDetail(selected, actions, language)") < results.indexOf("BaselineComparison(comparison, language)"))
    }

    @Test
    fun `commercial metric cards retain sample count and low tail resolution warning`() {
        val statistics =
            StartupStatistics(
                count = 3,
                missingCount = 0,
                minimumMs = 10.0,
                maximumMs = 30.0,
                medianMs = 20.0,
                meanMs = 20.0,
                p90Ms = 30.0,
                p95Ms = 30.0,
                standardDeviationMs = 10.0,
                medianAbsoluteDeviationMs = 10.0,
                p90LowResolution = true,
            )

        assertTrue(source.contains("StudioMetricCard("))
        assertTrue(source.contains("statistics.metricSupportingText(language)"))
        assertEquals(
            "p90 30.0 ms · n=3\np95 30.0 ms · n=3\nTail percentile resolution is low for this sample count.",
            statistics.metricSupportingText(UiLanguage.ENGLISH),
        )
    }
}
