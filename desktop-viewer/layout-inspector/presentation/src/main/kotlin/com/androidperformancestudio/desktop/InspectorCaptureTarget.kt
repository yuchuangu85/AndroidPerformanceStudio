package com.androidperformancestudio.desktop

import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.UiNode
import com.androidperformancestudio.protocol.effectiveWindows

internal data class InspectorCaptureTarget(
    val serial: String,
    val packageName: String,
    val mode: CaptureTargetMode,
) {
    fun matches(
        serial: String?,
        packageName: String?,
        mode: CaptureTargetMode,
    ): Boolean = this.serial == serial && this.packageName == packageName && this.mode == mode

    fun acceptsSnapshot(snapshotPackageName: String?): Boolean = packageName == snapshotPackageName
}

internal class InspectorCaptureTargetChangedException :
    IllegalStateException(
        "Inspection target changed during capture; refresh the current target again.",
    )

internal fun composeCaptureWarning(
    snapshot: LayoutSnapshot,
    reason: String?,
    expectedCompose: Boolean,
): String? {
    if (reason == null) return null
    var hasComposeEvidence = expectedCompose || snapshot.capabilities.composeSemantics
    val pending = ArrayDeque<UiNode>()
    pending.addAll(snapshot.effectiveWindows.map { it.root })
    while (!hasComposeEvidence && pending.isNotEmpty()) {
        val node = pending.removeFirst()
        hasComposeEvidence = node is ComposeNode || node.className.substringAfterLast('.') in COMPOSE_HOST_NAMES
        pending.addAll(node.children)
    }
    return reason.takeIf { hasComposeEvidence }
}

private val COMPOSE_HOST_NAMES = setOf("ComposeView", "AndroidComposeView")
