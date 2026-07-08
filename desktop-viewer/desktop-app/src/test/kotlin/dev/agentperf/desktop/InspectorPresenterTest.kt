package dev.agentperf.desktop

import dev.agentperf.analysis.AnalysisReport
import dev.agentperf.analysis.Finding
import dev.agentperf.analysis.LayoutMetrics
import dev.agentperf.analysis.Severity
import dev.agentperf.application.ConnectionStatus
import dev.agentperf.application.InspectorState
import dev.agentperf.application.TimelineDiff
import dev.agentperf.application.TimelineFrame
import dev.agentperf.application.InspectorStore
import dev.agentperf.fixtures.SampleSnapshots
import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ComposeNode
import dev.agentperf.protocol.EdgeInsets
import dev.agentperf.protocol.ViewAttributes
import dev.agentperf.protocol.WindowSnapshot
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
        assertEquals(listOf(true, false, true, false, false), model.rows.map { it.hasChildren })
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

        val model = InspectorPresenter.present(
            store.state,
            ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE),
        )

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
    fun `presents rendering risks and comprehensive layout inspector sections`() {
        val root = ViewNode(
            id = "root",
            className = "dev.sample.RealRootLayout",
            bounds = Bounds(0, 0, 100, 100),
            alpha = 0.8f,
            attributes = ViewAttributes(
                visibility = "VISIBLE",
                layoutBounds = Bounds(0, 0, 100, 100),
                elevation = 8f,
                z = 10f,
                padding = EdgeInsets(4, 8, 4, 8),
                layoutParamsClass = "android.widget.FrameLayout.LayoutParams",
                background = "android.graphics.drawable.ColorDrawable",
                backgroundColor = "#FF101820",
                clipChildren = true,
                clipToPadding = false,
                layerType = "SOFTWARE",
                hardwareAccelerated = true,
                enabled = true,
                clickable = true,
                rawProperties = mapOf(
                    "layout:left" to "0",
                    "layout:right" to "100",
                    "layoutParams:class" to "android.widget.FrameLayout.LayoutParams",
                ),
            ),
            children = listOf(
                ViewNode(
                    id = "first",
                    className = "View",
                    bounds = Bounds(10, 10, 90, 90),
                ),
                ViewNode(
                    id = "second",
                    className = "View",
                    bounds = Bounds(10, 10, 90, 90),
                    visible = false,
                ),
            ),
        )

        val details = InspectorPresenter.present(
            InspectorState(
                snapshot = SampleSnapshots.dashboard.copy(root = root),
                selectedNodeId = "root",
            ),
        ).details

        assertEquals(
            listOf("RENDER RISKS", "IDENTITY", "LAYOUT", "DRAWING", "INTERACTION", "RAW PROPERTIES"),
            details.sections.map { it.title },
        )
        assertEquals(
            "1 pair · max 100% · structural",
            details.row("Overdraw estimate").value,
        )
        assertEquals(DetailTone.WARNING, details.row("Overdraw estimate").tone)
        assertEquals("2 descendants · depth 2", details.row("Subtree complexity").value)
        assertEquals("0, 0, 100, 100", details.row("Local layout bounds").value)
        assertEquals("100 × 100", details.row("Local layout size").value)
        assertEquals("android.widget.FrameLayout.LayoutParams", details.row("Layout params class").value)
        assertEquals("4, 8, 4, 8", details.row("Padding").value)
        assertEquals("8.0", details.row("Elevation").value)
        assertEquals("SOFTWARE", details.row("Layer type").value)
        assertEquals("true", details.row("Clickable").value)
        assertEquals("0", details.row("layout:left").value)
        assertEquals("android.widget.FrameLayout.LayoutParams", details.row("layoutParams:class").value)
    }


    @Test
    fun `presents compose semantics properties in selected node details`() {
        val composeNode = ComposeNode(
            id = "compose-save",
            className = "ComposeSemantics",
            bounds = Bounds(10, 20, 110, 60),
            semanticsRole = "Button",
            text = "Save",
            semanticProperties = mapOf(
                "Role" to "Button",
                "Text" to "Save",
                "TestTag" to "save_button",
            ),
        )
        val root = ViewNode(
            id = "root",
            className = "androidx.compose.ui.platform.AndroidComposeView",
            bounds = Bounds(0, 0, 120, 80),
            children = listOf(composeNode),
        )

        val details = InspectorPresenter.present(
            InspectorState(
                snapshot = SampleSnapshots.dashboard.copy(
                    root = root,
                    windows = listOf(
                        WindowSnapshot(
                            id = "window:main",
                            title = "Main",
                            bounds = root.bounds,
                            root = root,
                        ),
                    ),
                    defaultWindowId = "window:main",
                ),
                selectedNodeId = "compose-save",
            ),
        ).details

        val identityRows = details.sections.single { it.title == "IDENTITY" }.rows
        assertEquals("Button", identityRows.single { it.label == "Semantics role" }.value)
        assertEquals("Save", identityRows.single { it.label == "Text" }.value)
        assertEquals("save_button", details.row("TestTag").value)
    }

    @Test
    fun `localizes detail sections and fields`() {
        val details = InspectorPresenter.present(
            InspectorState(
                snapshot = SampleSnapshots.dashboard,
                selectedNodeId = "root",
            ),
            ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE),
        ).details

        assertEquals(
            listOf("渲染风险", "标识", "布局", "绘制", "交互"),
            details.sections.map { it.title },
        )
        assertEquals("过度绘制估算", details.sections.first().rows.first().label)
        assertEquals("类", details.sections[1].rows.first().label)
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
    fun `maps finding severity to display tone`() {
        val state = InspectorState(
            snapshot = SampleSnapshots.dashboard,
            analysis = AnalysisReport(
                metrics = LayoutMetrics(nodeCount = 5, maxDepth = 3, widestLevel = 2),
                findings = listOf(
                    Finding("info", Severity.INFO, "root", "info"),
                    Finding("warning", Severity.WARNING, "root", "warning"),
                    Finding("error", Severity.ERROR, "root", "error"),
                ),
            ),
        )

        assertEquals(
            listOf(FindingTone.INFO, FindingTone.WARNING, FindingTone.ERROR),
            InspectorPresenter.present(state).findings.map { it.tone },
        )
        assertEquals(
            listOf("info:root:0", "warning:root:1", "error:root:2"),
            InspectorPresenter.present(state).findings.map { it.key },
        )
    }

    @Test
    fun `presents timeline diff summary when available`() {
        val model = InspectorPresenter.present(
            InspectorState(
                snapshot = SampleSnapshots.dashboard,
                timelineDiff = TimelineDiff(
                    previousCapturedAtEpochMillis = 1,
                    currentCapturedAtEpochMillis = 2,
                    addedNodes = 3,
                    removedNodes = 1,
                    boundsChangedNodes = 2,
                ),
            ),
        )

        assertEquals("Δ +3 -1 moved 2", model.timelineText)
    }


    @Test
    fun `presents timeline frame history with selected frame`() {
        val first = SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 1_000)
        val second = first.copy(capturedAtEpochMillis = 2_000)
        val model = InspectorPresenter.present(
            InspectorState(
                snapshot = second,
                timelineFrames = listOf(
                    TimelineFrame(
                        index = 0,
                        snapshot = first,
                        screenshotPng = byteArrayOf(1),
                        diffFromPrevious = null,
                    ),
                    TimelineFrame(
                        index = 1,
                        snapshot = second,
                        screenshotPng = byteArrayOf(2),
                        diffFromPrevious = TimelineDiff(
                            previousCapturedAtEpochMillis = 1_000,
                            currentCapturedAtEpochMillis = 2_000,
                            addedNodes = 2,
                            removedNodes = 1,
                            boundsChangedNodes = 3,
                        ),
                    ),
                ),
                selectedTimelineFrameIndex = 1,
            ),
        )

        assertEquals(listOf(0, 1), model.timelineFrames.map { it.index })
        assertEquals(listOf("#0", "#1"), model.timelineFrames.map { it.label })
        assertEquals(listOf("baseline", "+2 -1 moved 3"), model.timelineFrames.map { it.summary })
        assertEquals(listOf(false, true), model.timelineFrames.map { it.selected })
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
    fun `offline archive has a neutral localized connection status`() {
        val state = InspectorState(connectionStatus = ConnectionStatus.ARCHIVE)

        val english = InspectorPresenter.present(state, ViewerStrings.English)
        val chinese = InspectorPresenter.present(
            state,
            ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE),
        )

        assertEquals("Offline archive", english.connectionLabel)
        assertEquals("离线归档", chinese.connectionLabel)
        assertEquals(ConnectionTone.NEUTRAL, english.connectionTone)
    }

    @Test
    fun `localizes the authorized device count error in the header`() {
        val model = InspectorPresenter.present(
            InspectorState(
                connectionStatus = ConnectionStatus.ERROR,
                connectionError = "Expected exactly one authorized device, found 0",
            ),
            ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE),
        )

        assertEquals(
            "需要且只能连接一台已授权设备，当前检测到 0 台",
            model.connectionLabel,
        )
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

    private fun NodeDetailsModel.row(label: String): DetailRowModel =
        sections.flatMap { it.rows }.single { it.label == label }
}
