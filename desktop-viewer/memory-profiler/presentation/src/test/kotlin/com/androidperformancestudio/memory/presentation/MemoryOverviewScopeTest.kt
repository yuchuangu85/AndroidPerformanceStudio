package com.androidperformancestudio.memory.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.memory.model.ActivityLeakEntry
import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.HeapDiff
import com.androidperformancestudio.memory.model.HeapDiffEntry
import com.androidperformancestudio.memory.model.HeapSummary
import com.androidperformancestudio.memory.model.LeakSuspect
import com.androidperformancestudio.memory.model.NativeHeapAnalysis
import com.androidperformancestudio.memory.model.NativeHeapTrace
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MemoryOverviewScopeTest {
    @Test
    fun `overview omits overlapping summaries even when all analysis data is populated`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = 1280, height = 720) {
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            MemoryProfilerScreen(populatedOverview(), MemoryProfilerActions(), language)
                        }
                    }
                    onNodeWithText(if (language == UiLanguage.ENGLISH) "Overview" else "概览").assertExists()
                    onNodeWithText(if (language == UiLanguage.ENGLISH) "Class histogram" else "类直方图").assertExists()
                    onNodeWithText("sample.Widget").assertExists()
                    listOf(
                        "Leak Suspects",
                        "泄漏疑点",
                        "Activity leaks",
                        "Activity 泄漏",
                        "Bitmap analysis",
                        "Bitmap 分析",
                        "Native heap",
                        "Native 堆",
                        "Heap diff",
                        "堆对比",
                        "Open Native Heap",
                        "查看 Native 堆",
                    ).forEach { text -> onNodeWithText(text).assertDoesNotExist() }
                    onNodeWithText("Retained by singleton", substring = true).assertDoesNotExist()
                }
            }
        }
    }

    private fun populatedOverview(): MemoryProfilerState =
        MemoryProfilerState(
            viewMode = MemoryProfilerViewMode.Dashboard,
            summary = HeapSummary(objectCount = 4, shallowSize = 128, classCount = 1),
            classes = listOf(ClassStats("sample.Widget", 4, 128, 256)),
            leakSuspects = listOf(LeakSuspect("sample.LeakingActivity", "Retained by singleton")),
            activityLeaks = listOf(ActivityLeakEntry("sample.LeakingActivity", destroyedInstanceCount = 1)),
            bitmapInstances = listOf(BitmapInstanceStats(1, 10, 10, 400)),
            nativeHeapTrace = NativeHeapTrace("sample.pb", "sample.pb", 100),
            nativeHeapAnalysis = NativeHeapAnalysis(totalAllocatedBytes = 100, sampleCount = 1),
            heapDiff =
                HeapDiff(
                    listOf(HeapDiffEntry("sample.Widget", 2, 4, 2, 64, 128, 64)),
                ),
        )
}
