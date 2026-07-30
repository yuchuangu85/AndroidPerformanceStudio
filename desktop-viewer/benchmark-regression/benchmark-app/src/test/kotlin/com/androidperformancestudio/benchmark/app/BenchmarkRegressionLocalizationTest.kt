package com.androidperformancestudio.benchmark.app

import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.Res
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.import_androidx_benchmark_json
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.import_failed
import com.androidperformancestudio.benchmark.benchmark_app.generated.resources.imported_current
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.test.Test
import kotlin.test.assertEquals

class BenchmarkRegressionLocalizationTest {
    @Test
    fun `benchmark application messages are translated to Chinese`() {
        assertEquals(
            "导入 AndroidX Benchmark JSON",
            localizedStringResource(
                Res.string.import_androidx_benchmark_json,
                UiLanguage.SIMPLIFIED_CHINESE,
            ),
        )
        assertEquals(
            "已导入当前结果 result.json",
            localizedStringResource(
                Res.string.imported_current,
                UiLanguage.SIMPLIFIED_CHINESE,
                "result.json",
            ),
        )
        assertEquals(
            "导入失败",
            localizedStringResource(Res.string.import_failed, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
