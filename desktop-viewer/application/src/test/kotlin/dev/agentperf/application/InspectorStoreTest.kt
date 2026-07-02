package dev.agentperf.application

import dev.agentperf.fixtures.SampleSnapshots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorStoreTest {
    @Test
    fun `loading a snapshot selects its root and publishes analysis`() {
        val store = InspectorStore()

        store.load(SampleSnapshots.dashboard)

        assertEquals("root", store.state.selectedNodeId)
        assertEquals(SampleSnapshots.dashboard, store.state.snapshot)
        assertTrue(store.state.analysis.metrics.nodeCount > 1)
    }

    @Test
    fun `selecting an existing node updates details`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }

        val changed = store.selectNode("title")

        assertTrue(changed)
        assertEquals("title", store.state.selectedNodeId)
        assertEquals("Dashboard", store.state.selectedNode?.textContent)
    }

    @Test
    fun `selecting a missing node leaves current selection intact`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }
        val previous = store.state

        val changed = store.selectNode("missing")

        assertFalse(changed)
        assertEquals(previous, store.state)
        assertNotNull(store.state.selectedNode)
    }
}
