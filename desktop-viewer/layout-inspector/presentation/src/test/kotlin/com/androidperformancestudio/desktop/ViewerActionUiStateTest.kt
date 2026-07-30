package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ViewerActionUiStateTest {
    @Test
    fun `toggle actions reflect scan and hidden panel state`() {
        val visibility = PanelVisibility(
            showHierarchy = true,
            showFindings = false,
            showDetails = true,
        )

        assertEquals(
            ViewerActionUiState(enabled = true, checked = true),
            viewerActionUiState(
                action = ViewerAction.TOGGLE_AUTO_SCAN,
                selectedNodeId = null,
                autoScanEnabled = true,
                panelVisibility = visibility,
            ),
        )
        assertEquals(
            ViewerActionUiState(enabled = true, checked = true),
            viewerActionUiState(
                action = ViewerAction.TOGGLE_FINDINGS,
                selectedNodeId = null,
                autoScanEnabled = true,
                panelVisibility = visibility,
            ),
        )
        assertEquals(
            ViewerActionUiState(enabled = true, checked = false),
            viewerActionUiState(
                action = ViewerAction.TOGGLE_HIERARCHY,
                selectedNodeId = null,
                autoScanEnabled = true,
                panelVisibility = visibility,
            ),
        )
        assertEquals(
            ViewerActionUiState(enabled = true, checked = false),
            viewerActionUiState(
                action = ViewerAction.TOGGLE_DETAILS,
                selectedNodeId = null,
                autoScanEnabled = true,
                panelVisibility = visibility,
            ),
        )
    }

    @Test
    fun `tree actions require a selected node`() {
        assertFalse(
            viewerActionUiState(
                action = ViewerAction.NEXT_NODE,
                selectedNodeId = null,
                autoScanEnabled = false,
                panelVisibility = PanelVisibility(),
            ).enabled,
        )
        assertTrue(
            viewerActionUiState(
                action = ViewerAction.NEXT_NODE,
                selectedNodeId = "root",
                autoScanEnabled = false,
                panelVisibility = PanelVisibility(),
            ).enabled,
        )
    }
}
