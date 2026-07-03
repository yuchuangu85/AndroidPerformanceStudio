package dev.agentperf.desktop

import dev.agentperf.application.InspectorState
import dev.agentperf.application.InspectorStore
import dev.agentperf.application.ConnectionStatus
import dev.agentperf.fixtures.SampleSnapshots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorPresenterTest {
    @Test
    fun `flattens tree rows in depth first display order`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }

        val model = InspectorPresenter.present(store.state)

        assertEquals(listOf("root", "title", "cards", "score", "legacy-placeholder"), model.rows.map { it.id })
        assertEquals(listOf(0, 1, 1, 2, 2), model.rows.map { it.depth })
    }

    @Test
    fun `presents selected node details and finding counts`() {
        val store = InspectorStore().apply {
            load(SampleSnapshots.dashboard)
            selectNode("title")
        }

        val model = InspectorPresenter.present(store.state)

        assertEquals("android.widget.TextView", model.details.className)
        assertEquals("Dashboard", model.details.text)
        assertEquals("title", model.rows.single { it.selected }.id)
        assertEquals(1, model.severitySummary.info)
        assertEquals("不可见节点", model.findings.single().title)
        assertEquals("android.view.ViewStub 节点存在但当前不可见", model.findings.single().message)
    }

    @Test
    fun `empty state explains how to begin`() {
        val model = InspectorPresenter.present(InspectorState())

        assertTrue(model.rows.isEmpty())
        assertEquals("No snapshot loaded", model.emptyMessage)
    }

    @Test
    fun `connection status distinguishes progress success and failure`() {
        val connecting = InspectorPresenter.present(
            InspectorState(connectionStatus = ConnectionStatus.CONNECTING),
        )
        val connected = InspectorPresenter.present(
            InspectorState(connectionStatus = ConnectionStatus.CONNECTED),
        )
        val failed = InspectorPresenter.present(
            InspectorState(
                connectionStatus = ConnectionStatus.ERROR,
                connectionError = "No resumed activity",
            ),
        )

        assertEquals("Connecting", connecting.connectionLabel)
        assertEquals(ConnectionTone.NEUTRAL, connecting.connectionTone)
        assertEquals("Live", connected.connectionLabel)
        assertEquals(ConnectionTone.SUCCESS, connected.connectionTone)
        assertEquals("No resumed activity", failed.connectionLabel)
        assertEquals(ConnectionTone.ERROR, failed.connectionTone)
    }
}
