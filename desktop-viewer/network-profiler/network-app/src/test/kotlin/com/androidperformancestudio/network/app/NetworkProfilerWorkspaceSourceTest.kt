package com.androidperformancestudio.network.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerMainPage.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome without changing network screen`() {
        assertTrue(source.contains("HeaderToolbar("))
        assertFalse(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactTextField"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedTextField"))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("NetworkProfilerScreen("))
    }

    @Test
    fun `toolbar preserves capture import and export wiring with enabled predicates`() {
        assertButtonContains(
            "text = localizedStringResource(Res.string.import_har, language)",
            "enabled = !state.capturing",
            "chooseOpenFile(",
            "HarParser().parse(file.toPath())",
            "complete(",
        )
        assertButtonContains(
            "localizedStringResource(Res.string.live_capture, language)",
            "enabled = state.canCaptureWith(targetHost)",
            "if (state.capturing) stop() else start()",
        )
        assertButtonContains(
            "text = localizedStringResource(Res.string.json, language)",
            "enabled = state.result != null",
            "exporter.writeJson(",
            "requireNotNull(state.summary)",
        )
        assertButtonContains(
            "text = localizedStringResource(Res.string.har, language)",
            "enabled = state.result != null",
            "exporter.writePartialHar(",
        )
        assertButtonContains(
            "text = localizedStringResource(Res.string.csv, language)",
            "enabled = state.result != null",
            "exporter.writeCsv(",
        )
        assertButtonContains(
            "text = localizedStringResource(Res.string.raw_bundle, language)",
            "enabled = state.result != null",
            "exporter.writeRawBundle(",
            "requireNotNull(state.summary)",
        )
    }

    @Test
    fun `active title bar selections synchronize when idle`() {
        assertTrue(source.contains("LocalTitleBarTargetHost.current.takeIf { LocalWindowMenuBarActive.current }"))
        val effect = Regex("LaunchedEffect\\((.*?)\\)\\s*\\{([^{}]*)}", RegexOption.DOT_MATCHES_ALL)
            .findAll(source)
            .firstOrNull { it.groupValues[2].contains("state.withTitleBarTarget(targetHost)") }
        requireNotNull(effect) { "Missing title-bar target synchronization effect" }
        listOf("targetHost", "selectedDeviceSerial", "selectedPackageName", "state.capturing").forEach {
            assertTrue(effect.groupValues[1].contains(it), "Missing synchronization key: $it")
        }
        assertTrue(effect.groupValues[2].contains("state = state.withTitleBarTarget(targetHost)"))
    }

    @Test
    fun `target fields and their spacers stay inside standalone fallbacks`() {
        val device = blockAfter("TitleBarTargetOrInline(TitleBarTargetSlot.DEVICE)")
        val process = blockAfter("TitleBarTargetOrInline(TitleBarTargetSlot.PROCESS)")
        assertTrue(device.contains("Res.string.device_serial"))
        assertTrue(process.contains("Res.string.`package`"))
        listOf(device, process).forEach { block ->
            assertTrue(block.contains("ProfilerCompactTextField("))
            assertTrue(block.contains("HeaderSpacer()"))
        }
        assertTrue(
            Regex("ProfilerCompactTextField\\(").findAll(source).count() == 2,
            "Both target fields must be owned by their fallback blocks",
        )
        assertFalse(
            Regex("HeaderSpacer\\(\\)\\s*TitleBarTargetOrInline").containsMatchIn(source),
            "Target-leading spacers must be inside fallback blocks",
        )
    }

    @Test
    fun `capture pins a target before launching connection work`() {
        val start = blockAfter("fun start()")
        val launchIndex = start.indexOf("scope.launch {")
        assertTrue(launchIndex >= 0)
        val beforeLaunch = start.substring(0, launchIndex)
        assertTrue(beforeLaunch.contains("if (state.capturing || !state.canCaptureWith(targetHost)) return"))
        assertTrue(beforeLaunch.contains("val serial = state.deviceSerial"))
        assertTrue(beforeLaunch.contains("val packageName = state.packageName"))
        assertTrue(beforeLaunch.contains("capturing = true"))
        assertTrue(start.contains("capture.start(serial, packageName)"))
        assertFalse(start.contains("capture.start(state.deviceSerial, state.packageName)"))
    }

    @Test
    fun `capture callbacks inherit the Compose dispatcher without requiring a global Main dispatcher`() {
        assertFalse(source.contains("Dispatchers.Main"))
        assertFalse(source.contains("scope.launch(Dispatchers.IO)"))
        assertTrue(source.contains("runNetworkCaptureSession("))
        val stop = blockAfter("fun stop()")
        assertTrue(stop.contains("stopRequested = true"))
        assertTrue(stop.contains("pollJob?.cancel()"))
        assertFalse(stop.contains("scope.launch"))
    }

    @Test
    fun `toolbar preserves home navigation and capture field enablement`() {
        val homeBlock =
            source.substring(
                source.indexOf("onNavigateHome = {"),
                source.indexOf("onNavigateSettings = null"),
            )
        assertTrue(homeBlock.contains("if (state.capturing) stop()"))
        assertTrue(homeBlock.contains("onBack()"))

        assertTextFieldContains(
            "label = localizedStringResource(Res.string.device_serial, language)",
            "onValueChange = { state = state.copy(deviceSerial = it) }",
            "enabled = !state.capturing",
        )
        assertTextFieldContains(
            "label = localizedStringResource(Res.string.`package`, language)",
            "onValueChange = { state = state.copy(packageName = it) }",
            "enabled = !state.capturing",
        )
    }

    private fun assertButtonContains(
        anchor: String,
        vararg invariants: String,
    ) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val blockStart = source.lastIndexOf("ProfilerCompactButton(", anchorIndex)
        val blockEnd = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length).let {
            if (it >= 0) it else source.indexOf("Spacer(", anchorIndex)
        }
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }

    private fun blockAfter(anchor: String): String {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val opening = source.indexOf('{', anchorIndex + anchor.length)
        assertTrue(opening >= 0, "Missing block: $anchor")
        var depth = 1
        var closing = opening + 1
        while (closing < source.length && depth > 0) {
            when (source[closing]) {
                '{' -> depth++
                '}' -> depth--
            }
            closing++
        }
        assertTrue(depth == 0, "Unclosed block: $anchor")
        return source.substring(opening + 1, closing - 1)
    }

    private fun assertTextFieldContains(
        anchor: String,
        vararg invariants: String,
    ) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val blockStart = source.lastIndexOf("ProfilerCompactTextField(", anchorIndex)
        val nextButton = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length)
        val nextTextField = source.indexOf("ProfilerCompactTextField(", anchorIndex + anchor.length)
        val blockEnd = listOf(nextButton, nextTextField).filter { it >= 0 }.minOrNull() ?: source.length
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }
}
