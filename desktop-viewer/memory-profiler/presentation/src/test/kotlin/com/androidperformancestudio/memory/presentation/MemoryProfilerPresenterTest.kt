package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.memory.model.BitmapInstanceStats
import com.androidperformancestudio.memory.model.ClassStats
import com.androidperformancestudio.memory.model.LeakSuspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryProfilerPresenterTest {
    @Test
    fun `count sort is descending and stable by class name`() {
        val sorted = MemoryProfilerPresenter.sortClasses(sampleClasses(), MemoryHistogramSort.Count)

        assertEquals(
            listOf("java.lang.String", "kotlin.ByteArray", "android.graphics.Bitmap", "com.example.Tiny"),
            sorted.map { it.className },
        )
    }

    @Test
    fun `shallow sort is descending and stable by class name`() {
        val sorted = MemoryProfilerPresenter.sortClasses(sampleClasses(), MemoryHistogramSort.Shallow)

        assertEquals(
            listOf("android.graphics.Bitmap", "kotlin.ByteArray", "java.lang.String", "com.example.Tiny"),
            sorted.map { it.className },
        )
    }

    @Test
    fun `presenter applies selected histogram sort without mutating other state`() {
        val input =
            MemoryProfilerState(
                classes = sampleClasses(),
                sort = MemoryHistogramSort.Shallow,
                cleanupWarning = "rm failed for /data/local/tmp/heap.hprof",
            )

        val presented = MemoryProfilerPresenter.present(input)

        assertEquals(MemoryHistogramSort.Shallow, presented.sort)
        assertEquals("rm failed for /data/local/tmp/heap.hprof", presented.cleanupWarning)
        assertEquals("android.graphics.Bitmap", presented.classes.first().className)
        assertEquals("java.lang.String", input.classes.first().className)
    }

    @Test
    fun `scope filter keeps only project classes`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(heapBaseClasses = sampleClasses(), classScope = MemoryClassScope.PROJECT),
            )

        assertEquals(listOf("com.example.Tiny"), presented.displayedClasses.map { it.className })
    }

    @Test
    fun `scope filter keeps only system classes`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(heapBaseClasses = sampleClasses(), classScope = MemoryClassScope.SYSTEM),
            )

        assertEquals(
            listOf("android.graphics.Bitmap", "java.lang.String", "kotlin.ByteArray"),
            presented.displayedClasses.map { it.className },
        )
    }

    @Test
    fun `leak filter keeps only leak flagged classes`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(
                    heapBaseClasses = sampleClasses(),
                    leakFilter = MemoryLeakFilter.ACTIVITY_FRAGMENT_LEAK,
                    leakSuspects =
                        listOf(
                            LeakSuspect(
                                className = "com.example.Tiny",
                                reason = "",
                                activityOrFragmentLeak = true,
                            ),
                        ),
                ),
            )

        assertEquals(listOf("com.example.Tiny"), presented.displayedClasses.map { it.className })
    }

    @Test
    fun `duplicate bitmaps filter keeps classes with repeated dimensions`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(
                    heapBaseClasses = listOf(ClassStats("android.graphics.Bitmap", instanceCount = 2)),
                    leakFilter = MemoryLeakFilter.DUPLICATE_BITMAPS,
                    bitmapInstances = listOf(bitmap(1), bitmap(2)),
                ),
            )

        assertEquals(listOf("android.graphics.Bitmap"), presented.displayedClasses.map { it.className })
    }

    @Test
    fun `search filters class names case insensitively by default`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(heapBaseClasses = sampleClasses(), searchText = "STRING"),
            )

        assertEquals(listOf("java.lang.String"), presented.displayedClasses.map { it.className })
    }

    @Test
    fun `match case search is case sensitive`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(heapBaseClasses = sampleClasses(), searchText = "string", matchCase = true),
            )

        assertTrue(presented.displayedClasses.isEmpty())
    }

    @Test
    fun `regex search matches patterns`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(heapBaseClasses = sampleClasses(), searchText = "com\\..*", useRegex = true),
            )

        assertEquals(listOf("com.example.Tiny"), presented.displayedClasses.map { it.className })
    }

    @Test
    fun `arrange by class sorts alphabetically`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(heapBaseClasses = sampleClasses(), arrangeBy = MemoryArrangeBy.CLASS),
            )

        assertEquals(
            listOf("android.graphics.Bitmap", "com.example.Tiny", "java.lang.String", "kotlin.ByteArray"),
            presented.displayedClasses.map { it.className },
        )
    }

    @Test
    fun `arrange by package groups by package name`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(heapBaseClasses = sampleClasses(), arrangeBy = MemoryArrangeBy.PACKAGE),
            )

        assertEquals(
            listOf("android.graphics.Bitmap", "com.example.Tiny", "java.lang.String", "kotlin.ByteArray"),
            presented.displayedClasses.map { it.className },
        )
    }

    @Test
    fun `native size is enriched per class from bitmap instances`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(
                    heapBaseClasses = listOf(ClassStats("android.graphics.Bitmap", instanceCount = 1)),
                    bitmapInstances = listOf(bitmap(1, nativeSizeBytes = 40_000L)),
                ),
            )

        assertEquals(40_000L, presented.displayedClasses.single().nativeSize)
    }

    @Test
    fun `summary aggregates counts native shallow retained and leak flags`() {
        val presented =
            MemoryProfilerPresenter.present(
                MemoryProfilerState(
                    heapBaseClasses = sampleClasses(),
                    leakSuspects = listOf(LeakSuspect(className = "com.example.Tiny", reason = "")),
                    bitmapInstances = listOf(bitmap(1, nativeSizeBytes = 40_000L)),
                ),
            )
        val summary = presented.classListSummary

        assertEquals(4, summary.classCount)
        assertEquals(1, summary.leakCount)
        assertEquals(0, summary.duplicateBitmapCount)
        assertEquals(28, summary.totalCount)
        assertEquals(40_000L, summary.totalNativeSize)
        assertEquals(2_048L + 4_096L + 4_096L + 8L, summary.totalShallowSize)
    }

    private fun bitmap(
        objectId: Long,
        nativeSizeBytes: Long? = null,
    ): BitmapInstanceStats =
        BitmapInstanceStats(
            objectId = objectId,
            width = 100,
            height = 100,
            retainedSize = 100L,
            nativeSizeBytes = nativeSizeBytes,
            className = "android.graphics.Bitmap",
        )

    private fun sampleClasses(): List<ClassStats> =
        listOf(
            ClassStats("java.lang.String", instanceCount = 12, shallowSize = 2_048L),
            ClassStats("kotlin.ByteArray", instanceCount = 12, shallowSize = 4_096L),
            ClassStats("android.graphics.Bitmap", instanceCount = 3, shallowSize = 4_096L),
            ClassStats("com.example.Tiny", instanceCount = 1, shallowSize = 8L),
        )
}
