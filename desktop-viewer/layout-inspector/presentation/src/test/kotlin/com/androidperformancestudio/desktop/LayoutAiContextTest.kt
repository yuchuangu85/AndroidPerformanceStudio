package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayoutAiContextTest {
    @Test
    fun `layout selection is redacted and evidence identity binds snapshot`() {
        val root = ViewNode("root", "android.widget.TextView", Bounds(0, 0, 100, 100), text = "private runtime value")
        val snapshot = SampleSnapshots.dashboard.copy(root = root)
        val input = AiAnalysisInputBuilder().build(snapshot, root, AnalysisReport(LayoutMetrics(1, 1, 1), emptyList()), false, root)
        val first = layoutUiAiContext("snapshot-one", input)
        val next = layoutUiAiContext("snapshot-two", input)
        assertNotNull(first)
        assertEquals("layout-inspector", first!!.key)
        assertTrue(first.currentSelection)
        assertEquals("layout-snapshot", first.evidence.single().kind)
        assertFalse(first.evidence.single().payloadJson.contains("private runtime value"))
        assertNotEquals(first.evidence.single().id, next!!.evidence.single().id)
    }

    @Test
    fun `missing truncated or over budget contexts never publish partial data`() {
        val input = AiAnalysisInput("{}")
        assertNull(layoutUiAiContext("snapshot", null))
        assertNull(layoutUiAiContext("snapshot", input.copy(treeTruncated = true)))
        assertNull(layoutUiAiContext("snapshot", input.copy(omittedSourceEvidenceCount = 1)))
        assertNull(layoutUiAiContext("snapshot", input.copy(json = " ".repeat(65_537))))
        assertFalse(layoutUiAiContext("snapshot", input)!!.currentSelection)
    }
}
