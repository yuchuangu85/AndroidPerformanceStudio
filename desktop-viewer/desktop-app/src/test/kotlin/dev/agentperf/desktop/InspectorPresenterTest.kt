package dev.agentperf.desktop

import dev.agentperf.analysis.AnalysisReport
import dev.agentperf.analysis.Finding
import dev.agentperf.analysis.LayoutMetrics
import dev.agentperf.analysis.Severity
import dev.agentperf.application.ConnectionStatus
import dev.agentperf.application.InspectorState
import dev.agentperf.application.InspectorStore
import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.ViewNode
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
        assertEquals(
            listOf("0-0", "1-0", "1-1", "2-0", "2-1"),
            model.rows.map { it.number },
        )
    }

    @Test
    fun `uses one global index sequence for each depth`() {
        val bounds = SampleSnapshots.dashboard.root.bounds
        val snapshot = SampleSnapshots.dashboard.copy(
            root = ViewNode(
                id = "root",
                className = "Root",
                bounds = bounds,
                children = listOf(
                    ViewNode(
                        id = "left",
                        className = "Left",
                        bounds = bounds,
                        children = listOf(
                            ViewNode(id = "left-leaf", className = "Leaf", bounds = bounds),
                        ),
                    ),
                    ViewNode(
                        id = "right",
                        className = "Right",
                        bounds = bounds,
                        children = listOf(
                            ViewNode(id = "right-leaf", className = "Leaf", bounds = bounds),
                        ),
                    ),
                ),
            ),
        )

        val model = InspectorPresenter.present(InspectorState(snapshot = snapshot))

        assertEquals(
            listOf("0-0", "1-0", "2-0", "1-1", "2-1"),
            model.rows.map { it.number },
        )
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
        assertEquals("2-1", model.findings.single().nodeNumber)
        assertEquals(
            model.rows.single { it.id == "legacy-placeholder" }.number,
            model.findings.single().nodeNumber,
        )
    }

    @Test
    fun `uses a placeholder when a finding node is absent from the snapshot`() {
        val state = InspectorState(
            snapshot = SampleSnapshots.dashboard,
            analysis = AnalysisReport(
                metrics = LayoutMetrics(nodeCount = 5, maxDepth = 3, widestLevel = 2),
                findings = listOf(
                    Finding(
                        ruleId = "layout.test",
                        severity = Severity.INFO,
                        nodeId = "missing",
                        message = "测试问题",
                    ),
                ),
            ),
        )

        assertEquals("—", InspectorPresenter.present(state).findings.single().nodeNumber)
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

    @Test
    fun `header starts with package name then separates connection status with a vertical bar`() {
        val model = InspectorPresenter.present(
            InspectorState(
                snapshot = SampleSnapshots.dashboard,
                connectionStatus = ConnectionStatus.CONNECTED,
            ),
        )

        assertEquals(
            listOf("dev.agentperf.sample", "|", "Live"),
            headerTextSegments(model),
        )
    }
}
