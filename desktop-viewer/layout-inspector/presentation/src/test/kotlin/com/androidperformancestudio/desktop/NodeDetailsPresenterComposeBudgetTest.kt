package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.ComposeParameterReference
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.ui.UiLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeDetailsPresenterComposeBudgetTest {
    @Test
    fun `parameter projection preserves depth first ordering and expandable references`() {
        val reference = reference()
        val parameters = listOf(
            ComposeValue(
                "items", "LIST", elements = listOf(
                    ComposeValue("", "STRING", "first", reference = reference, truncated = true),
                    ComposeValue("named", "STRING", "second"),
                ),
                originalSize = 2,
            ),
            ComposeValue("tail", "INT", "3"),
        )

        val rows = parameterSection(parameters).rows

        assertEquals(listOf("items", "items.0", "items.named", "tail"), rows.map { it.label })
        assertSame(reference, rows[1].composeReference)
        assertEquals(DetailTone.INFO, rows[1].tone)
        assertTrue(rows[1].value.contains("<truncated>"))
        assertTrue(rows[0].value.contains("size=2"))
    }

    @Test
    fun `wide parameter trees stop traversal at the display row budget without deleting evidence`() {
        var visited = 0
        val children = object : AbstractList<ComposeValue>() {
            override val size = 100_000

            override fun get(index: Int): ComposeValue {
                visited += 1
                check(visited <= 512) { "Projection must not enumerate omitted evidence" }
                return ComposeValue("item$index", "INT", index.toString())
            }
        }
        val root = ComposeValue("items", "LIST", elements = children, reference = reference())

        val rows = parameterSection(listOf(root)).rows

        assertEquals(513, rows.size)
        assertEquals(511, visited)
        assertEquals("items.item510", rows[511].label)
        assertSame(root.reference, rows.first().composeReference)
        assertTrue(rows.last().value.contains("Original captured evidence has not been deleted"))
        assertEquals(DetailTone.INFO, rows.last().tone)
        assertSame(children, root.elements)
        assertEquals(100_000, root.elements.size)
    }

    @Test
    fun `a section exactly at the row budget is not reported as partial`() {
        val parameters = List(512) { ComposeValue("item$it", "INT", it.toString()) }

        val rows = parameterSection(parameters).rows

        assertEquals(512, rows.size)
        assertTrue(rows.all { it.tone == DetailTone.NORMAL })
    }

    @Test
    fun `deep parameter trees are projected iteratively with an explicit display only notice`() {
        var parameter = ComposeValue("leaf", "STRING", "original leaf")
        repeat(10_000) { parameter = ComposeValue("level", "OBJECT", elements = listOf(parameter)) }

        val rows = parameterSection(listOf(parameter)).rows

        assertEquals(17, rows.size)
        assertEquals(DetailTone.INFO, rows.last().tone)
        assertTrue(rows.last().value.contains("Only part of this section is displayed"))
        var originalLeaf = parameter
        repeat(10_000) { originalLeaf = originalLeaf.elements.single() }
        assertEquals("original leaf", originalLeaf.value)
    }

    @Test
    fun `long parameter labels and values are bounded and disclose partial display in Chinese`() {
        val reference = reference()
        val parameter = ComposeValue("n".repeat(20_000), "STRING", "v".repeat(20_000), reference = reference)

        val section = parameterSection(listOf(parameter), UiLanguage.SIMPLIFIED_CHINESE)

        assertEquals(2, section.rows.size)
        assertTrue(section.rows.first().label.length <= 512)
        assertTrue(section.rows.first().value.length <= 4_096)
        assertSame(reference, section.rows.first().composeReference)
        assertTrue(section.rows.last().value.contains("原始采集证据未删除"))
        assertEquals(20_000, parameter.value?.length)
    }

    private fun parameterSection(
        parameters: List<ComposeValue>,
        language: UiLanguage = UiLanguage.ENGLISH,
    ): DetailSectionModel {
        val bounds = Bounds(0, 0, 10, 10)
        val document = ComposeInspectionDocument(
            packageName = "sample",
            capturedAtEpochMillis = 1,
            frame = ComposeInspectionFrame(
                frameId = "frame",
                generation = 1,
                mode = ComposeInspectionMode.FULL,
                capabilities = emptyList(),
                roots = listOf(
                    ComposableRoot(1, listOf(ComposableNode(7, 70, "Content", bounds))),
                ),
                details = mapOf(7L to ComposableDetail(7, 70, parameters = parameters)),
            ),
        )
        return NodeDetailsPresenter.present(
            node = ComposeNode("compose-inspection:7", "Content", bounds),
            treeDepth = 1,
            language = language,
            composeInspection = document,
        ).single { it.title == if (language == UiLanguage.ENGLISH) "PARAMETERS" else "参数" }
    }

    private fun reference() = ComposeParameterReference(
        composableId = 7,
        parameterIndex = 0,
        kind = "NORMAL",
        anchorHash = 70,
    )
}
