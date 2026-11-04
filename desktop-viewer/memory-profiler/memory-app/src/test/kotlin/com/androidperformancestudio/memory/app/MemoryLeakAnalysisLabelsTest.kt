package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.memory_app.generated.resources.Res
import com.androidperformancestudio.memory.memory_app.generated.resources.memory_leaks_view
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryLeakAnalysisLabelsTest {
    @Test
    fun `memory toolbar names the diagnostic view leak analysis in both languages`() {
        assertEquals("Leak Analysis", localizedStringResource(Res.string.memory_leaks_view, UiLanguage.ENGLISH))
        assertEquals(
            "\u6cc4\u6f0f\u5206\u6790",
            localizedStringResource(Res.string.memory_leaks_view, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }
}
