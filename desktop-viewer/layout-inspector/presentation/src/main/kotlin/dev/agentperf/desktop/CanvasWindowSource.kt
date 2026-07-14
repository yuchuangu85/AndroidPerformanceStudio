package dev.agentperf.desktop

import dev.agentperf.application.InspectorState

internal object CanvasWindowSource {
    fun sourceRect(state: InspectorState, appOnly: Boolean): CropRect? {
        val display = state.snapshot?.display ?: return null
        return CanvasGeometry.sourceRect(
            appBounds = state.activeWindow?.bounds ?: state.activeRoot?.bounds,
            displayWidth = display.widthPx,
            displayHeight = display.heightPx,
            appOnly = appOnly,
        )
    }
}
