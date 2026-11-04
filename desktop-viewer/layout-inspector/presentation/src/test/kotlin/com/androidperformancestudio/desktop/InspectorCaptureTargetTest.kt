package com.androidperformancestudio.desktop

import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorCaptureTargetTest {
    private val target = InspectorCaptureTarget("phone", "com.example", CaptureTargetMode.FOREGROUND_APP)

    @Test
    fun `capture belongs to the requested device package and mode`() {
        assertTrue(target.matches("phone", "com.example", CaptureTargetMode.FOREGROUND_APP))
        assertFalse(target.matches("other", "com.example", CaptureTargetMode.FOREGROUND_APP))
        assertFalse(target.matches("phone", "com.other", CaptureTargetMode.FOREGROUND_APP))
        assertFalse(target.matches("phone", "com.example", CaptureTargetMode.SYSTEM_UI))
        assertFalse(target.matches(null, "com.example", CaptureTargetMode.FOREGROUND_APP))
    }

    @Test
    fun `a snapshot from another foreground package is rejected`() {
        assertTrue(target.acceptsSnapshot("com.example"))
        assertFalse(target.acceptsSnapshot("com.other"))
        assertFalse(target.acceptsSnapshot(null))
    }

    @Test
    fun `system UI captures retain their own package identity`() {
        val systemUi = InspectorCaptureTarget("phone", SYSTEM_UI_PACKAGE_NAME, CaptureTargetMode.SYSTEM_UI)
        assertTrue(systemUi.matches("phone", SYSTEM_UI_PACKAGE_NAME, CaptureTargetMode.SYSTEM_UI))
        assertTrue(systemUi.acceptsSnapshot(SYSTEM_UI_PACKAGE_NAME))
        assertFalse(systemUi.acceptsSnapshot("com.example"))
    }

    @Test
    fun `ordinary native screens do not warn that Compose was not detected`() {
        assertNull(composeCaptureWarning(SampleSnapshots.dashboard, "No Compose metadata", expectedCompose = false))
        assertNull(composeCaptureWarning(SampleSnapshots.dashboard, null, expectedCompose = true))
    }

    @Test
    fun `known Compose targets and compatible Compose hosts preserve the unavailable reason`() {
        val snapshot = SampleSnapshots.dashboard
        val reason = "Compose tree unavailable"
        assertEquals(reason, composeCaptureWarning(snapshot, reason, expectedCompose = true))
        for (className in listOf("androidx.compose.ui.platform.ComposeView", "androidx.compose.ui.platform.AndroidComposeView")) {
            val composeHost = snapshot.copy(root = ViewNode("host", className, Bounds(0, 0, 100, 100)))
            assertEquals(reason, composeCaptureWarning(composeHost, reason, expectedCompose = false))
        }
    }
}
