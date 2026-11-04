package com.androidperformancestudio.network.app

import com.androidperformancestudio.network.analysis.NetworkAnalyzer
import com.androidperformancestudio.network.model.EvidenceCompleteness
import com.androidperformancestudio.network.model.InstrumentationMode
import com.androidperformancestudio.network.model.NETWORK_REDACTION_POLICY_VERSION
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkCoverage
import com.androidperformancestudio.network.model.NetworkEvidenceCompleteness
import com.androidperformancestudio.network.model.NetworkSession
import com.androidperformancestudio.network.model.NetworkSessionStatus
import com.androidperformancestudio.network.model.NetworkTimeDomain
import com.androidperformancestudio.network.presentation.NetworkProfilerState
import com.androidperformancestudio.ui.TitleBarDeviceOption
import com.androidperformancestudio.ui.TitleBarTargetHost
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NetworkProfilerTargetSelectionTest {
    @Test
    fun `idle state follows shared device and package selections`() {
        val host = targetHost()
        val state = NetworkProfilerState(deviceSerial = "manual", packageName = "manual.app")

        val selected = state.withTitleBarTarget(host)

        assertEquals("serial-a", selected.deviceSerial)
        assertEquals("com.example.first", selected.packageName)
        assertTrue(selected.canCaptureWith(host))

        host.choosePackage("com.example.second")
        val changedPackage = selected.withTitleBarTarget(host)
        assertEquals("serial-a", changedPackage.deviceSerial)
        assertEquals("com.example.second", changedPackage.packageName)

        host.selectDevice("serial-b")
        val changedDevice = changedPackage.withTitleBarTarget(host)
        assertEquals("serial-b", changedDevice.deviceSerial)
        assertEquals("", changedDevice.packageName)
        assertFalse(changedDevice.canCaptureWith(host))
    }

    @Test
    fun `package clearing and device disconnect clear idle targets`() {
        val host = targetHost()
        val selected = NetworkProfilerState().withTitleBarTarget(host)

        host.choosePackage(null)
        val clearedPackage = selected.withTitleBarTarget(host)
        assertEquals("serial-a", clearedPackage.deviceSerial)
        assertEquals("", clearedPackage.packageName)
        assertFalse(clearedPackage.canCaptureWith(host))

        host.updateDevices(emptyList())
        val disconnected = selected.withTitleBarTarget(host)
        assertEquals("", disconnected.deviceSerial)
        assertEquals("", disconnected.packageName)
        assertFalse(disconnected.canCaptureWith(host))
    }

    @Test
    fun `null host preserves standalone or inactive page fallback`() {
        val state = NetworkProfilerState(deviceSerial = "manual", packageName = "manual.app")

        assertSame(state, state.withTitleBarTarget(null))
        assertTrue(state.canCaptureWith(null))
        assertFalse(state.copy(deviceSerial = " ").canCaptureWith(null))
        assertFalse(state.copy(packageName = " ").canCaptureWith(null))
        assertFalse(NetworkProfilerState().canCaptureWith(null))
    }

    @Test
    fun `host mismatch disables start until idle target synchronization`() {
        val host = targetHost()
        val state = NetworkProfilerState().withTitleBarTarget(host)

        assertTrue(state.canCaptureWith(host))
        assertFalse(state.copy(deviceSerial = "serial-b").canCaptureWith(host))
        assertFalse(state.copy(packageName = "com.example.other").canCaptureWith(host))
        assertFalse(NetworkProfilerState().canCaptureWith(host))

        host.choosePackage("com.example.second")
        assertFalse(state.canCaptureWith(host))
        assertTrue(state.withTitleBarTarget(host).canCaptureWith(host))
    }

    @Test
    fun `connecting or capturing pins original target and keeps stop enabled`() {
        val host = targetHost()
        val capturing = NetworkProfilerState().withTitleBarTarget(host).copy(capturing = true)

        host.selectDevice("serial-b")
        host.choosePackage("com.example.second")
        assertSame(capturing, capturing.withTitleBarTarget(host))
        assertEquals("serial-a", capturing.deviceSerial)
        assertEquals("com.example.first", capturing.packageName)
        assertTrue(capturing.canCaptureWith(host))

        host.clearDevice()
        assertSame(capturing, capturing.withTitleBarTarget(host))
        assertTrue(capturing.canCaptureWith(host))
        assertTrue(capturing.canCaptureWith(null))
        assertTrue(NetworkProfilerState(capturing = true).canCaptureWith(host))

        val stopped = capturing.copy(capturing = false).withTitleBarTarget(host)
        assertEquals("", stopped.deviceSerial)
        assertEquals("", stopped.packageName)
        assertFalse(stopped.canCaptureWith(host))
    }

    @Test
    fun `target synchronization preserves captured result and presentation evidence`() {
        val result = capturedResult()
        val summary = NetworkAnalyzer().summarize(result.calls)
        val artifact = NetworkArtifactFactory { Instant.EPOCH }.agent(result)
        val state = NetworkProfilerState(
            deviceSerial = "captured-serial",
            packageName = "com.example.captured",
            liveEventCount = 17,
            result = result,
            summary = summary,
            selectedCallId = "selected-call",
            message = "Capture completed",
            error = "Previous warning",
            artifact = artifact,
        )

        val updated = state.withTitleBarTarget(targetHost())

        assertEquals(state.copy(deviceSerial = "serial-a", packageName = "com.example.first"), updated)
        assertSame(result, updated.result)
        assertSame(summary, updated.summary)
        assertSame(artifact, updated.artifact)
        assertEquals("captured-serial", updated.result?.session?.deviceSerial)
        assertEquals("com.example.captured", updated.result?.session?.packageName)
    }

    private fun targetHost(): TitleBarTargetHost = TitleBarTargetHost().apply {
        updateDevices(listOf(TitleBarDeviceOption("serial-a", "Pixel A"), TitleBarDeviceOption("serial-b", "Pixel B")))
        selectDevice("serial-a")
        choosePackage("com.example.first")
    }

    private fun capturedResult(): NetworkCaptureResult = NetworkCaptureResult(
        session = NetworkSession(
            id = "captured-session",
            deviceSerial = "captured-serial",
            packageName = "com.example.captured",
            startedAt = Instant.EPOCH,
            endedAt = Instant.EPOCH.plusSeconds(1),
            coverage = NetworkCoverage(
                processIds = setOf(42),
                observedLibraries = emptySet(),
                observedInstrumentationIds = emptySet(),
                instrumentationMode = InstrumentationMode.EXPLICIT_FACTORY,
                supportedEventKinds = emptySet(),
                knownLimitations = setOf("partial capture"),
                windowStartedNs = 0L,
                windowEndedNs = 1_000_000_000L,
            ),
            completeness = NetworkEvidenceCompleteness(
                status = EvidenceCompleteness.PARTIAL,
                droppedEvents = 1L,
                sequenceGaps = 0L,
                unpairedEvents = 0,
                skippedRecords = 0,
            ),
            sourceTimeDomain = NetworkTimeDomain.DEVICE_MONOTONIC,
            sourceTimeOriginNs = 0L,
            clockMapping = null,
            status = NetworkSessionStatus.PARTIAL,
            redactionPolicyVersion = NETWORK_REDACTION_POLICY_VERSION,
        ),
        calls = emptyList(),
    )
}
