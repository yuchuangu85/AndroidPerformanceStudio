package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryCapabilities
import com.androidperformancestudio.battery.model.BatteryCapabilityLevel
import com.androidperformancestudio.battery.model.BatteryDeviceState
import com.androidperformancestudio.battery.model.BatteryEnvironment
import com.androidperformancestudio.battery.model.BatteryExperimentConfig
import com.androidperformancestudio.battery.model.BatterySession
import com.androidperformancestudio.contracts.ArtifactCompleteness
import com.androidperformancestudio.contracts.CaptureArtifactJson
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryArtifactFactoryTest {
    @Test
    fun `interrupted battery evidence is PARTIAL and omits raw device serial`() {
        val artifact = BatteryArtifactFactory { Instant.EPOCH }.forSession(session(), status = "INTERRUPTED")

        assertEquals(ArtifactCompleteness.PARTIAL, artifact.completeness)
        assertFalse(BatteryArtifactFactory.CAPTURE_COMPLETED in artifact.availableCapabilities)
        assertTrue(artifact.limitations.any { it.capability == BatteryArtifactFactory.CAPTURE_COMPLETED })
        assertTrue(artifact.limitations.any { it.code == "interrupted" })
        assertTrue(artifact.device?.rawSerial == null)
        assertFalse(CaptureArtifactJson.encode(artifact).contains("raw-battery-serial"))
    }

    private fun session(): BatterySession =
        BatterySession(
            id = "session-1",
            deviceSerial = "raw-battery-serial",
            packageName = "dev.example.app",
            uid = 10_123,
            attributionScope = AttributionScope.UID,
            config = BatteryExperimentConfig(),
            capabilities =
                BatteryCapabilities(
                    level = BatteryCapabilityLevel.RESOURCE_BASIC,
                    checkin = true,
                    history = false,
                    reset = false,
                    energy = false,
                    bugreport = true,
                ),
            environment = BatteryEnvironment(BatteryDeviceState()),
            createdAt = Instant.EPOCH,
        )
}
