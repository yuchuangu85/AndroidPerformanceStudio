package com.androidperformancestudio.application

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.WindowSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorStoreAiAnalysisTest {
    @Test
    fun `has no ai analysis target without a snapshot`() {
        assertNull(InspectorStore().aiAnalysisTarget())
    }

    @Test
    fun `matching ai analysis target applies report`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }
        val target = requireNotNull(store.aiAnalysisTarget())
        val report = AiAnalysisReport(model = "gpt-test", summary = "summary")

        assertTrue(store.isAiAnalysisTargetCurrent(target))
        assertTrue(store.loadAiAnalysis(report, target))
        assertSame(report, store.state.aiAnalysis)
    }

    @Test
    fun `replacement snapshot with identical timestamp and ids rejects old target`() {
        val snapshot = SampleSnapshots.dashboard
        val store = InspectorStore().apply { load(snapshot) }
        val target = requireNotNull(store.aiAnalysisTarget())
        val report = AiAnalysisReport(model = "gpt-test", summary = "stale")

        store.load(snapshot.copy())
        val currentReport = AiAnalysisReport(model = "gpt-test", summary = "current")
        store.loadAiAnalysis(currentReport)
        val currentState = store.state

        assertFalse(store.isAiAnalysisTargetCurrent(target))
        assertFalse(store.loadAiAnalysis(report, target))
        assertSame(currentState, store.state)
        assertSame(currentReport, store.state.aiAnalysis)
    }

    @Test
    fun `new capture rejects old ai analysis target`() {
        val store = InspectorStore().apply { loadCapture(SampleSnapshots.dashboard, byteArrayOf()) }
        val target = requireNotNull(store.aiAnalysisTarget())
        val report = AiAnalysisReport(model = "gpt-test", summary = "stale")

        store.loadCapture(SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 1234), byteArrayOf())
        val currentState = store.state

        assertFalse(store.isAiAnalysisTargetCurrent(target))
        assertFalse(store.loadAiAnalysis(report, target))
        assertSame(currentState, store.state)
        assertNull(store.state.aiAnalysis)
    }

    @Test
    fun `window switch rejects old ai analysis target`() {
        val root = SampleSnapshots.dashboard.root
        val snapshot = SampleSnapshots.dashboard.copy(
            windows = listOf(
                WindowSnapshot(id = "window:main", title = "Main", bounds = root.bounds, root = root),
                WindowSnapshot(id = "window:dialog", title = "Dialog", bounds = root.bounds, root = root),
            ),
            defaultWindowId = "window:main",
        )
        val store = InspectorStore().apply { load(snapshot) }
        val target = requireNotNull(store.aiAnalysisTarget())
        val report = AiAnalysisReport(model = "gpt-test", summary = "stale")

        assertTrue(store.selectWindow("window:dialog"))
        val currentState = store.state

        assertSame(snapshot, store.state.snapshot)
        assertFalse(store.isAiAnalysisTargetCurrent(target))
        assertFalse(store.loadAiAnalysis(report, target))
        assertSame(currentState, store.state)
        assertNull(store.state.aiAnalysis)
    }

    @Test
    fun `timeline switch rejects old target even when frames share a snapshot`() {
        val snapshot = SampleSnapshots.dashboard
        val store = InspectorStore().apply {
            loadCapture(snapshot, byteArrayOf())
            loadCapture(snapshot, byteArrayOf())
        }
        val target = requireNotNull(store.aiAnalysisTarget())
        val report = AiAnalysisReport(model = "gpt-test", summary = "stale")

        assertTrue(store.selectTimelineFrame(0))
        val currentState = store.state

        assertSame(snapshot, store.state.snapshot)
        assertFalse(store.isAiAnalysisTargetCurrent(target))
        assertFalse(store.loadAiAnalysis(report, target))
        assertSame(currentState, store.state)
        assertNull(store.state.aiAnalysis)
    }

    @Test
    fun `node selection does not invalidate capture bound ai analysis`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }
        val target = requireNotNull(store.aiAnalysisTarget())
        val report = AiAnalysisReport(model = "gpt-test", summary = "summary")

        assertTrue(store.selectNode("title"))

        assertTrue(store.isAiAnalysisTargetCurrent(target))
        assertTrue(store.loadAiAnalysis(report, target))
        assertSame(report, store.state.aiAnalysis)

        assertTrue(store.selectNode("cards"))

        assertTrue(store.isAiAnalysisTargetCurrent(target))
        assertSame(report, store.state.aiAnalysis)
    }

    @Test
    fun `stores ai analysis and clears it when capture changes`() {
        val store = InspectorStore().apply { load(SampleSnapshots.dashboard) }
        val report = AiAnalysisReport(model = "gpt-test", summary = "summary")

        store.loadAiAnalysis(report)
        assertEquals(report, store.state.aiAnalysis)

        store.loadCapture(SampleSnapshots.dashboard.copy(capturedAtEpochMillis = 1234), byteArrayOf(1, 2, 3))

        assertNull(store.state.aiAnalysis)
    }
}
