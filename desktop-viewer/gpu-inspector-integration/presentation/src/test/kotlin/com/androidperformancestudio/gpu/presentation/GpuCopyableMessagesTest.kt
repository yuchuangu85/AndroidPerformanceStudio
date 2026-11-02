package com.androidperformancestudio.gpu.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class GpuCopyableMessagesTest {
    @Test
    fun `device status errors and capability warnings keep full text behind visual truncation`() {
        val hub = source("GpuAnalysisHubScreen.kt")
        listOf(
            "reading_device_context",
            "no_connected_devices",
            "capability_no_arguments",
            "capability_no_warnings",
            "tool_responsibility_hint",
        ).forEach { resource -> assertCopyable(hub, "localizedStringResource(Res.string.$resource, language)") }
        assertTrue(hub.contains("state.deviceError?.let { CopyableText(it"))
        assertTrue(hub.contains("maxLines = 2, overflow = TextOverflow.Ellipsis"))
        assertCopyable(hub, "\"\u2022 \${localizedWarning(warning, language)}\"")
        assertTrue(hub.contains("    Text(text, style = ViewerTypography.subsectionTitle"))
        assertTrue(hub.contains("onRefresh = actions.onRefreshDevices"))
    }

    @Test
    fun `artifact empty states and closed capability explanations are copyable without replacing table cells`() {
        val artifacts = source("GpuAnalysisHubArtifacts.kt")
        listOf(
            "no_gpu_artifacts",
            "no_matching_artifacts",
            "artifact_preview_empty",
            "no_related_evidence",
            "closed_panels_hint",
        ).forEach { resource -> assertCopyable(artifacts, "localizedStringResource(Res.string.$resource, language)") }
        assertCopyable(artifacts, "\"\u2022 \$panel\"")
        assertTrue(artifacts.contains("modifier = Modifier.weight(weight).padding(horizontal = 3.dp)"))
        assertTrue(Regex("\\bText\\(\\s*text,\\s*modifier = Modifier.weight\\(weight\\)").containsMatchIn(artifacts))
        assertTrue(artifacts.contains("onClick = { actions.onOpenArtifact(artifact) }"))
    }

    @Test
    fun `legacy screen warnings operation messages errors and availability remain copyable`() {
        val legacy = source("GpuIntegrationScreen.kt")
        assertCopyable(legacy, "localizedWarning(warning, language)")
        assertCopyable(legacy, "it.locationStatus.displayName(language)")
        assertTrue(legacy.contains("state.message?.let { CopyableText(it"))
        assertTrue(legacy.contains("state.error?.let { CopyableText(it"))
        assertTrue(legacy.contains("                                Text(localizedStringResource(Res.string.verify, language))"))
    }

    private fun source(name: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/gpu/presentation/$name"))

    private fun assertCopyable(source: String, argument: String) {
        assertTrue(Regex("CopyableText\\(\\s*" + Regex.escape(argument)).containsMatchIn(source), argument)
    }
}
