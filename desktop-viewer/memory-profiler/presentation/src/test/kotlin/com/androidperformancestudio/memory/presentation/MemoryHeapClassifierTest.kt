package com.androidperformancestudio.memory.presentation

import com.androidperformancestudio.memory.model.ClassStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryHeapClassifierTest {
    @Test
    fun `all heaps show separate canonical roots and do not merge shared classes`() {
        val presented = MemoryProfilerPresenter.present(fixture())
        val roots = presented.classifierRows
        assertEquals(listOf("App heap", "Image heap", "Zygote heap"), roots.map { it.label })
        assertEquals(listOf("App", "Image", "Zygote"), roots.map { it.heapName })
        val app = roots[0].children.single { it.className == "sample.Shared" }
        val zygote = roots[2].children.single { it.className == "sample.Shared" }
        assertEquals(2L, app.totalCount)
        assertEquals(5L, zygote.totalCount)
        assertEquals("heap:App/class:sample.Shared", app.id)
        assertEquals("heap:Zygote/class:sample.Shared", zygote.id)
        assertEquals(5L, roots[0].totalCount)
        assertEquals(110L, roots[0].shallowSize)
        assertEquals(11, presented.classListSummary.totalCount)
        // Retained sizes of unrelated classes may overlap; heap totals cannot sum them.
        assertNull(roots[0].retainedSize)
    }

    @Test
    fun `selected App keeps its heap root and exact scoped class metrics`() {
        val state = fixture()
        val presented =
            MemoryProfilerPresenter.present(
                state.copy(heapFilter = "App", heapBaseClasses = state.perHeapClasses.getValue("App")),
            )
        assertEquals(listOf("App heap"), presented.classifierRows.map { it.label })
        assertEquals(
            2L,
            presented.classifierRows
                .single()
                .children
                .single { it.className == "sample.Shared" }
                .totalCount,
        )
        assertEquals(5, presented.classListSummary.totalCount)
    }

    @Test
    fun `sort applies within each heap with unknown values last and stable ids`() {
        val byName = MemoryProfilerPresenter.present(fixture())
        val byCount =
            MemoryProfilerPresenter.present(
                fixture().copy(
                    classifierSortColumn = MemoryClassifierColumn.TOTAL_COUNT,
                    classifierSortDirection = MemorySortDirection.DESCENDING,
                ),
            )
        assertEquals(
            listOf("sample.Other", "sample.Shared"),
            byCount.classifierRows
                .first()
                .children
                .map { it.className },
        )
        assertEquals(listOf("App", "Zygote", "Image"), byCount.classifierRows.map { it.heapName })
        assertEquals(byName.classifierRows.map { it.id }.toSet(), byCount.classifierRows.map { it.id }.toSet())
        assertEquals(
            byName.classifierRows
                .first()
                .children
                .map { it.id }
                .toSet(),
            byCount.classifierRows
                .first()
                .children
                .map { it.id }
                .toSet(),
        )
        val byNative =
            MemoryProfilerPresenter.present(
                fixture().copy(
                    classifierSortColumn = MemoryClassifierColumn.NATIVE_SIZE,
                    classifierSortDirection = MemorySortDirection.DESCENDING,
                ),
            )
        assertNull(
            byNative.classifierRows
                .first()
                .children
                .last()
                .nativeSize,
        )
    }

    @Test
    fun `heap dump defaults to retained descending and name sort uses case sensitive simple names`() {
        assertEquals(MemoryClassifierColumn.RETAINED_SIZE, MemoryProfilerState().classifierSortColumn)
        assertEquals(MemorySortDirection.DESCENDING, MemoryProfilerState().classifierSortDirection)
        val classes =
            listOf(
                ClassStats("a.zebra", retainedSize = 1),
                ClassStats("z.Alpha", retainedSize = 3),
                ClassStats("a.Class2", retainedSize = 2),
                ClassStats("z.Class10", retainedSize = 2),
            )
        val byName = MemoryProfilerPresenter.buildClassifierRows(classes, MemoryArrangeBy.CLASS)
        assertEquals(listOf("Alpha (z)", "Class10 (z)", "Class2 (a)", "zebra (a)"), byName.map { it.label })
        val byRetained =
            MemoryProfilerPresenter
                .present(
                    MemoryProfilerState(heapFilter = "App", heapBaseClasses = classes),
                ).classifierRows
                .single()
                .children
        assertEquals(listOf("z.Alpha", "a.Class2", "z.Class10", "a.zebra"), byRetained.map { it.className })
    }

    @Test
    fun `package rows remain inside their own heap and search removes unmatched classes`() {
        val presented =
            MemoryProfilerPresenter.present(fixture().copy(arrangeBy = MemoryArrangeBy.PACKAGE, searchText = "Shared"))
        val app = presented.classifierRows.single { it.heapName == "App" }
        val group = app.children.single()
        assertEquals("sample", group.label)
        assertEquals("App", group.heapName)
        assertEquals("heap:App/group:sample", group.id)
        assertEquals("sample.Shared", group.children.single().className)
        assertTrue(
            group.children
                .single()
                .id
                .startsWith("heap:App/"),
        )
        assertEquals(2L, app.totalCount)
    }

    private fun fixture(): MemoryProfilerState {
        val app =
            listOf(
                ClassStats("sample.Shared", 2, 20, 80, heapNames = setOf("App")),
                ClassStats("sample.Other", 3, 90, 100, nativeSize = 7, heapNames = setOf("App")),
            )
        val image = listOf(ClassStats("sample.Image", 1, 12, heapNames = setOf("Image")))
        val zygote = listOf(ClassStats("sample.Shared", 5, 50, heapNames = setOf("Zygote")))
        return MemoryProfilerState(
            perHeapClasses = linkedMapOf("Zygote" to zygote, "Image" to image, "App" to app),
            availableHeaps = listOf("App", "Image", "Zygote"),
            classes = app + image + zygote,
            heapBaseClasses = app + image + zygote,
        )
    }
}
