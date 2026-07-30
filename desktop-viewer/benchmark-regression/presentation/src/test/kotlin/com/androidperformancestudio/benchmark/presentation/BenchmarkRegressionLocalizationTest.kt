package com.androidperformancestudio.benchmark.presentation

import com.androidperformancestudio.benchmark.presentation.generated.resources.Res
import com.androidperformancestudio.benchmark.presentation.generated.resources.classification_regressed
import com.androidperformancestudio.benchmark.presentation.generated.resources.confidence_exact
import com.androidperformancestudio.benchmark.presentation.generated.resources.current
import com.androidperformancestudio.benchmark.presentation.generated.resources.metric_comparisons
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkRegressionLocalizationTest {
    private val source =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkRegressionScreen.kt",
            ),
        )

    @Test
    fun `benchmark screen resolves labels with the application language`() {
        assertTrue(source.contains("localizedStringResource"))
        assertFalse(source.contains("stringResource("))
        assertTrue(source.contains("Res.string.current, language"))
        assertTrue(source.contains("Res.string.metric_comparisons, language"))
        assertTrue(source.contains("comparison.classification.displayName(language)"))
        assertTrue(source.contains("comparison.confidence.displayName(language)"))
        assertTrue(source.contains("localizedReason(reason, language)"))
    }

    @Test
    fun `benchmark presentation resources resolve in Chinese`() {
        assertEquals(
            "当前结果",
            localizedStringResource(Res.string.current, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "指标比较",
            localizedStringResource(Res.string.metric_comparisons, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "性能回退",
            localizedStringResource(Res.string.classification_regressed, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "精确",
            localizedStringResource(Res.string.confidence_exact, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
