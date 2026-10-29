package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ComposeCaptureRoutingSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
    private val manual =
        source
            .substringAfter("LaunchedEffect(manualRefreshRequest, autoScanEnabled)")
            .substringBefore("var paneWidths")

    @Test
    fun `manual capture awaits preparation before choosing full or compatible capture`() {
        val prepare = manual.indexOf("prepareFullComposeForForeground(forceRetry = true, target = target)")
        val choosePipeline = manual.indexOf("if (fullComposeEnabled)")
        assertTrue(prepare >= 0 && choosePipeline > prepare)
        assertFalse(manual.substring(0, choosePipeline).contains("coroutineScope.launch"))
        assertTrue(manual.contains("manualRefreshSession.capture(target.serial)"))
    }

    @Test
    fun `automatic capture awaits preparation before choosing a pipeline`() {
        val automatic =
            source
                .substringAfter("LaunchedEffect(autoScanEnabled, selectedDeviceSerial")
                .substringBefore("LaunchedEffect(state.selectedNodeId")
        val prepare = automatic.indexOf("prepareFullComposeForForeground(target = target)")
        assertTrue(prepare >= 0 && automatic.indexOf("if (fullComposeEnabled)") > prepare)
        assertTrue(automatic.contains("verifyCaptureTarget(target, capture.snapshot.packageName)"))
        assertTrue(automatic.contains("verifyCaptureTarget(target, snapshot.packageName)"))
    }

    @Test
    fun `manual full native and fallback results are verified before publication`() {
        assertTrue(manual.contains("verifyCaptureTarget(target, capture.snapshot.packageName)"))
        assertTrue(Regex("verifyCaptureTarget\\(target, snapshot.packageName\\)").findAll(manual).count() == 2)
        assertTrue(manual.contains("catch (error: InspectorCaptureTargetChangedException)"))
        assertTrue(manual.contains("composeInspectionWarning = composeCaptureWarning(snapshot, composeUnavailableReason, composeExpected)"))
        assertTrue(manual.contains("composeInspectionWarning = reason"))
        assertTrue(source.contains("ComposeInspectionWarningBanner(reason, uiLanguage)"))
    }

    @Test
    fun `same package preparation is not permanently suppressed after a failure`() {
        assertFalse(source.contains("automaticComposePreflightAttempt"))
        assertTrue(source.contains("composePreparation.prepareForCapture(requested.serial, requested.packageName, forceRetry)"))
        assertTrue(source.contains("composePreparation.invalidate()"))
        assertTrue(source.contains("fullComposeRecoveryAttempts >= MAX_FULL_COMPOSE_RECOVERY_ATTEMPTS"))
    }

    @Test
    fun `process restart exhaustion preserves the failure before compatible capture resumes`() {
        val recovery =
            source
                .substringAfter("if (fullComposeEnabled && recoverableTarget)")
                .substringBefore("else if (fullComposeEnabled)")
        assertTrue(recovery.indexOf("composeUnavailableReason =") >= 0)
        assertTrue(
            recovery.indexOf("store.fallbackToCompatibleInspection(composeUnavailableReason)") <
                recovery.indexOf("prepareFullComposeForForeground()"),
        )
    }
}
