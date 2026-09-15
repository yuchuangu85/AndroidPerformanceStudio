@file:Suppress("MagicNumber")

package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.Finding
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.application.TimelineChangeType
import com.androidperformancestudio.application.TimelineDiff
import com.androidperformancestudio.application.TimelineFrame
import com.androidperformancestudio.application.TimelineNodeChange
import com.androidperformancestudio.compose.inspection.CapabilityAvailability
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeArchivePrivacy
import com.androidperformancestudio.compose.inspection.ComposeCapability
import com.androidperformancestudio.compose.inspection.ComposeCapabilityState
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.ProtocolCodec
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Produces the checked-in `.apinspect` fixture consumed by Electron's
 * cross-runtime archive test. Every member is emitted through Kotlin's archive
 * service and serializers, rather than by manually assembling a ZIP in JS.
 */
public object ElectronCaptureArchiveFixtureWriter {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1) { "Expected exactly one fixture output path" }
        val target = Path.of(args.single()).toAbsolutePath()
        target.parent?.let(Files::createDirectories)
        Files.deleteIfExists(target)

        val snapshot = SampleSnapshots.dashboard.copy(
            display = DisplayInfo(widthPx = 1, heightPx = 1, density = 1f),
        )
        val service = CaptureArchiveService(
            archiveCodec = CaptureArchiveCodec(),
            protocolCodec = ProtocolCodec(supportedMajor = 1),
        )
        service.export(
            target = target,
            producerVersion = "kotlin-capture-fixture-v1",
            snapshot = snapshot,
            screenshotPng = ONE_PIXEL_PNG,
            rawArtifacts = CaptureRawArtifacts(
                zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
                text = "Kotlin visible-window hierarchy\n",
            ),
            analysis = AnalysisReport(
                metrics = LayoutMetrics(nodeCount = 3, maxDepth = 2, widestLevel = 2),
                findings = listOf(
                    Finding(
                        ruleId = "layout.deep-hierarchy",
                        severity = Severity.WARNING,
                        nodeId = "root",
                        message = "Kotlin archive finding",
                        arguments = mapOf("depth" to "12"),
                    ),
                ),
            ),
            aiAnalysis = AiAnalysisReport(
                model = "kotlin-archive-model",
                summary = "Kotlin archive AI summary",
                findings = listOf(
                    AiFinding(
                        ruleId = "ai.layout",
                        severity = Severity.ERROR,
                        nodeId = "root",
                        title = "Kotlin archive AI finding",
                        message = "The hierarchy needs attention.",
                        recommendation = "Flatten nested containers.",
                        confidence = 0.9f,
                    ),
                ),
            ),
            timelineFrames = listOf(
                TimelineFrame(
                    index = 1,
                    snapshot = snapshot,
                    screenshotPng = ONE_PIXEL_PNG,
                    diffFromPrevious = TimelineDiff(
                        previousCapturedAtEpochMillis = snapshot.capturedAtEpochMillis - 1_000,
                        currentCapturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                        addedNodes = 1,
                        removedNodes = 0,
                        boundsChangedNodes = 2,
                        changes = listOf(
                            TimelineNodeChange(
                                type = TimelineChangeType.CHANGED,
                                windowId = "window:main",
                                nodeId = "title",
                                nodeKey = "window:main:title",
                                className = "TextView",
                                changedProperties = listOf("bounds", "text"),
                            ),
                        ),
                    ),
                ),
            ),
            composeInspection = ComposeInspectionDocument(
                packageName = snapshot.packageName,
                capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                privacy = ComposeArchivePrivacy.SAFE_REDACTED,
                frame = ComposeInspectionFrame(
                    frameId = "${snapshot.packageName}:${snapshot.capturedAtEpochMillis}:1",
                    generation = 1,
                    mode = ComposeInspectionMode.FULL,
                    capabilities = listOf(
                        ComposeCapabilityState(ComposeCapability.FULL_TREE, CapabilityAvailability.AVAILABLE),
                    ),
                    roots = listOf(
                        ComposableRoot(
                            viewId = 7,
                            nodes = listOf(
                                ComposableNode(1, 11, "Content", Bounds(0, 0, 1, 1)),
                            ),
                        ),
                    ),
                    details = mapOf(
                        1L to ComposableDetail(
                            nodeId = 1,
                            anchorHash = 11,
                            parameters = listOf(ComposeValue("title", "String", "sensitive Kotlin title")),
                        ),
                    ),
                ),
            ),
        )
    }

    private val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )
}
