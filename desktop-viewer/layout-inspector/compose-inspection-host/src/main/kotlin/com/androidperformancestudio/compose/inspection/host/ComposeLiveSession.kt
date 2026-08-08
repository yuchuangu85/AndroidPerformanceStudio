package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.AdbCommandFactory
import com.androidperformancestudio.adb.ProcessRunner
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposeArchivePrivacy
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionProjection
import com.androidperformancestudio.compose.inspection.ComposeParameterReference
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.LayoutSnapshot
import java.util.concurrent.atomic.AtomicBoolean

data class ComposeLiveCapture(
    val snapshot: LayoutSnapshot,
    val composeInspection: ComposeInspectionDocument,
    val screenshotPng: ByteArray?,
)

class ComposeLiveSession private constructor(
    private val injection: InjectedComposeSession,
    private val protocol: AospInspectorProtocolClient,
    private val client: ComposeInspectionClient,
    private val resolvedInspector: ResolvedComposeInspector,
    private val processRunner: ProcessRunner,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var lastFrame: ComposeInspectionFrame? = null

    fun capture(hideSystemComposables: Boolean = true): ComposeLiveCapture {
        check(!closed.get() && injection.isTargetAlive()) { "Target Compose process is no longer available" }
        val stableCapture = StableComposeFrameCapture(client).capture(injection.packageName)
        val views = stableCapture.views
        val compose = stableCapture.compose
        lastFrame = compose
        val snapshot = ComposeInspectionProjection.mergeInto(
            snapshot = views.snapshot.copy(
                capabilities = views.snapshot.capabilities.copy(composeSemantics = true),
            ),
            frame = compose,
            hideSystemComposables = hideSystemComposables,
        )
        return ComposeLiveCapture(
            snapshot = snapshot,
            composeInspection = ComposeInspectionDocument(
                packageName = injection.packageName,
                capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                frame = compose,
                artifact = resolvedInspector.identity,
                privacy = ComposeArchivePrivacy.FULL_FIDELITY,
            ),
            screenshotPng = processRunner.run(AdbCommandFactory.captureScreenshot(injection.serial))
                .takeIf { it.exitCode == 0 && it.stdoutBytes.isNotEmpty() }
                ?.stdoutBytes,
        )
    }

    fun loadDetail(nodeId: Long, anchorHash: Int): ComposableDetail {
        val root = lastFrame?.roots?.firstOrNull { root -> root.nodes.any { it.contains(nodeId) } }
            ?: error("Compose node is not part of the current frame")
        return client.loadDetail(root.viewId, nodeId, anchorHash)
    }

    fun loadParameterDetails(
        reference: ComposeParameterReference,
        startIndex: Int,
        maxElements: Int = 50,
    ): ComposeValue {
        val root = lastFrame?.roots?.firstOrNull { root ->
            root.nodes.any { it.contains(reference.composableId) }
        } ?: error("Compose parameter is not part of the current frame")
        return client.loadParameterDetails(root.viewId, reference, startIndex, maxElements)
    }

    fun startRecompositionObservation() = client.startRecompositionObservation()
    fun stopRecompositionObservation() = client.stopRecompositionObservation()
    fun resetRecompositionCounts() = client.resetRecompositionCounts()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { protocol.shutdownAgent() }
        protocol.close()
        injection.close()
    }

    companion object {
        fun open(
            injection: InjectedComposeSession,
            artifactResolver: ComposeInspectorArtifactResolver,
            processRunner: ProcessRunner,
            expectedComposeVersion: String,
            explicitLocalArtifact: java.nio.file.Path? = null,
        ): ComposeLiveSession {
            val protocol = AospInspectorProtocolClient.connect(injection.hostPort, injection.token)
            try {
                protocol.createInspector(
                    AospInspectorProtocolClient.VIEW_INSPECTOR_ID,
                    injection.privateViewInspectorPath,
                )
                val version = protocol.getComposeVersion()
                    ?: error("Jetpack Compose was not detected in the target process")
                require(version == expectedComposeVersion) {
                    "Compose version changed from $expectedComposeVersion to $version; authorize again"
                }
                val resolved = artifactResolver.resolve(version, explicitLocalArtifact)
                protocol.createInspector(
                    AospInspectorProtocolClient.COMPOSE_INSPECTOR_ID,
                    injection.deployComposeInspector(resolved),
                )
                return ComposeLiveSession(
                    injection = injection,
                    protocol = protocol,
                    client = ComposeInspectionClient(protocol),
                    resolvedInspector = resolved,
                    processRunner = processRunner,
                )
            } catch (error: Throwable) {
                runCatching { protocol.shutdownAgent() }
                protocol.close()
                injection.close()
                throw error
            }
        }
    }
}

private fun com.androidperformancestudio.compose.inspection.ComposableNode.contains(target: Long): Boolean =
    id == target || children.any { it.contains(target) }
