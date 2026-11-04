package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.AdbCommandFactory
import com.androidperformancestudio.adb.ProcessRunner
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposeArchivePrivacy
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionProjection
import com.androidperformancestudio.compose.inspection.ComposeParameterReference
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.effectiveWindows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ComposeLiveCapture(
    val snapshot: LayoutSnapshot,
    val composeInspection: ComposeInspectionDocument,
    val screenshotPng: ByteArray?,
)

/** The injected target app died (for example because it crashed or restarted). */
class TargetProcessUnavailableException(
    val serial: String,
    val packageName: String,
    val pid: Int,
) : IllegalStateException("Target Compose process is no longer available")

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
        check(!closed.get()) { "Compose inspection session is closed" }
        if (!injection.isTargetAlive()) {
            throw TargetProcessUnavailableException(injection.serial, injection.packageName, injection.pid)
        }
        val stableCapture = StableComposeFrameCapture(client).capture(injection.packageName)
        val compose = stableCapture.compose
        val snapshot = stableCapture.projectSnapshot(hideSystemComposables)
        lastFrame = compose
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
        activeSessions.remove(this)
        runCatching { protocol.shutdownAgent() }
        protocol.close()
        injection.close()
    }

    companion object {
        /**
         * Sessions whose device agent still has to be shut down.
         *
         * Closing the window makes Compose Desktop call exitProcess, which does not wait for the
         * coroutine that would otherwise close a live session. The agent would then stay attached to
         * the target process, and the next preflight would refuse to start with "Another Layout
         * Inspector is already attached" until the target app restarts. A shutdown hook closes
         * whatever is left so an ordinary exit leaves no agent behind.
         */
        private val activeSessions: MutableSet<ComposeLiveSession> = ConcurrentHashMap.newKeySet()
        private val shutdownHookInstalled = AtomicBoolean(false)

        private const val SHUTDOWN_TIMEOUT_MILLIS = 5_000L

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
                val session = ComposeLiveSession(
                    injection = injection,
                    protocol = protocol,
                    client = ComposeInspectionClient(protocol),
                    resolvedInspector = resolved,
                    processRunner = processRunner,
                )
                installShutdownHook()
                activeSessions += session
                return session
            } catch (error: Throwable) {
                runCatching { protocol.shutdownAgent() }
                protocol.close()
                injection.close()
                throw error
            }
        }

        private fun installShutdownHook() {
            if (!shutdownHookInstalled.compareAndSet(false, true)) return
            runCatching {
                Runtime.getRuntime().addShutdownHook(
                    Thread(::closeActiveSessions, "aps-compose-inspector-shutdown"),
                )
            }
        }

        /**
         * Closes the sessions that are still open. The work runs on a worker thread so a stuck adb
         * call cannot keep the process alive past [SHUTDOWN_TIMEOUT_MILLIS].
         */
        private fun closeActiveSessions() {
            val sessions = activeSessions.toList()
            if (sessions.isEmpty()) return
            System.err.println("[layout-inspector] shutting down " + sessions.size + " live Compose session(s)")
            val worker =
                Thread(
                    { sessions.forEach { session -> runCatching { session.close() } } },
                    "aps-compose-inspector-close",
                )
            worker.isDaemon = true
            worker.start()
            worker.join(SHUTDOWN_TIMEOUT_MILLIS)
            if (worker.isAlive) {
                System.err.println("[layout-inspector] live Compose session cleanup timed out")
            }
        }
    }
}

internal fun StableComposeCapture.projectSnapshot(hideSystemComposables: Boolean): LayoutSnapshot {
    val snapshot = ComposeInspectionProjection.mergeInto(
        snapshot = views.snapshot.copy(
            capabilities = views.snapshot.capabilities.copy(composeSemantics = true),
        ),
        frame = compose,
        hideSystemComposables = hideSystemComposables,
    )
    val pending = ArrayDeque<UiNode>()
    val projectedIds = mutableSetOf<String>()
    pending.addAll(snapshot.effectiveWindows.map { it.root })
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (node is ComposeNode) projectedIds.add(node.id)
        pending.addAll(node.children)
    }
    if (projectedIds.isNotEmpty()) {
        val expectedIds = mutableSetOf<String>()
        val composables = ArrayDeque<ComposableNode>()
        composables.addAll(compose.roots.flatMap { it.nodes })
        while (composables.isNotEmpty()) {
            val node = composables.removeFirst()
            if (!hideSystemComposables || !node.systemCreated) expectedIds.add("compose-inspection:" + node.id)
            composables.addAll(node.children)
        }
        val missing = expectedIds - projectedIds
        check(missing.isEmpty()) {
            "Compose tree unavailable after projection; Composable nodes " + missing.take(10) +
                " were not merged into the captured windows. Keep the target screen visible and retry capture."
        }
        return snapshot
    }
    error(
        "Compose tree unavailable after projection; no Compose nodes remain in the captured windows. " +
            "Keep the target Compose screen visible and retry capture" +
            if (hideSystemComposables) ", or show system composables." else ".",
    )
}

private fun com.androidperformancestudio.compose.inspection.ComposableNode.contains(target: Long): Boolean =
    id == target || children.any { it.contains(target) }
