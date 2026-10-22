package com.androidperformancestudio.perfetto.presentation

import com.androidperformancestudio.perfetto.model.PerfettoProbe
import com.androidperformancestudio.perfetto.model.PerfettoProbeGroup
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoCapturePageLayoutTest {
    private val source =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePage.kt",
            ),
        )

    @Test
    fun `capture page is split into template data source and configuration panels`() {
        assertTrue(source.contains("private fun PerfettoTemplatePanel("))
        assertTrue(source.contains("private fun PerfettoDataSourcesPanel("))
        assertTrue(source.contains("private fun PerfettoConfigurationPanel("))
        assertTrue(source.contains("BASE_TEMPLATE_PANEL_WIDTH + addedPanelWidth"))
        assertTrue(source.contains("BASE_DATA_SOURCES_PANEL_WIDTH + addedPanelWidth"))
        assertTrue(source.contains("configurationWidth"))
        assertTrue(source.contains("private fun PerfettoDataSourceGroupCard("))
        assertTrue(source.contains("PerfettoCompactTextField("))
        assertTrue(source.contains("PerfettoCompactButton("))
    }

    @Test
    fun `every data source group renders its own card only for non custom templates`() {
        val panel = functionSource("PerfettoDataSourcesPanel")
        val templateGate = blockSource(panel, "if (selectedTemplate != PerfettoTraceTemplate.CUSTOM)")
        val groupLoop = blockSource(templateGate, "PerfettoProbeGroup.entries.forEach { group ->")

        assertEquals(1, Regex("PerfettoProbeGroup\\.entries\\.forEach").findAll(panel).count())
        assertEquals(1, Regex("\\bPerfettoDataSourceGroupCard\\(").findAll(panel).count())
        assertTrue(groupLoop.contains("PerfettoDataSourceGroupCard("))
        assertTrue(groupLoop.contains("group = group"))
        assertTrue(groupLoop.contains("probes = PerfettoProbe.entries.filter { it.group == group }"))
        assertTrue(groupLoop.contains("enabledProbes = enabledProbes"))
        assertTrue(groupLoop.contains("targetPackage = targetPackage"))
        assertTrue(groupLoop.contains("capabilities = capabilities"))
        assertTrue(groupLoop.contains("onProbeChange = onProbeChange"))
    }

    @Test
    fun `model groups cover every selectable probe exactly once without empty cards`() {
        val probesByGroup =
            PerfettoProbeGroup.entries.associateWith { group ->
                PerfettoProbe.entries.filter { it.group == group }
            }

        probesByGroup.forEach { (group, probes) ->
            assertTrue(probes.isNotEmpty(), "$group must contain at least one selectable probe")
        }
        val renderedProbes = probesByGroup.values.flatMap { probes -> probes.chunked(2).flatten() }
        assertEquals(
            PerfettoProbe.entries.associateWith { 1 },
            renderedProbes.groupingBy { it }.eachCount(),
            "Every selectable probe must appear exactly once across the group cards",
        )
    }

    @Test
    fun `group cards retain compact styling and two equally weighted probe columns`() {
        val card = functionSource("PerfettoDataSourceGroupCard")
        val rows = blockSource(card, "probes.chunked(2).forEach { probeRow ->")
        val options = blockSource(rows, "probeRow.forEach { probe ->")
        val emptySlot = blockSource(rows, "if (probeRow.size == 1)")

        assertTrue(card.contains("StudioCard("))
        assertTrue(card.contains(".fillMaxWidth()"))
        assertTrue(card.contains("containerColor = MaterialTheme.colorScheme.surfaceVariant"))
        assertTrue(card.contains("borderColor = MaterialTheme.colorScheme.outline"))
        assertTrue(card.contains("contentPadding = 7.dp"))
        assertTrue(card.contains("contentSpacing = 4.dp"))
        assertFalse(card.contains(".background("))
        assertFalse(card.contains(".border("))
        assertTrue(card.contains("text = group.displayName.uppercase()"))
        assertTrue(rows.contains("Row("))
        assertEquals(1, Regex("\\bPerfettoProbeOption\\(").findAll(card).count())
        assertTrue(options.contains("PerfettoProbeOption("))
        assertTrue(options.contains("probe = probe"))
        assertTrue(options.contains("modifier = Modifier.weight(1f)"))
        assertTrue(emptySlot.contains("Spacer(Modifier.weight(1f))"))
    }

    @Test
    fun `capture card groups use four dp gaps while preserving card content and splitters`() {
        listOf("PerfettoTemplatePanel", "PerfettoDataSourcesPanel").forEach { name ->
            val panel = functionSource(name)
            assertTrue(panel.contains(".padding(StudioTokens.workspacePadding)"))
            assertTrue(panel.contains("verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)"))
            assertFalse(panel.contains("verticalArrangement = Arrangement.spacedBy(6.dp)"))
        }
        val card = functionSource("PerfettoDataSourceGroupCard")
        assertTrue(card.contains("contentPadding = 7.dp"))
        assertTrue(card.contains("contentSpacing = 4.dp"))
        assertTrue(card.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)"))
        val template = functionSource("PerfettoTemplateOption")
        assertTrue(template.contains(".padding(horizontal = 8.dp, vertical = 7.dp)"))
        assertTrue(template.contains("verticalArrangement = Arrangement.spacedBy(2.dp)"))
        assertTrue(source.contains("private val PANEL_DIVIDER_WIDTH = 1.dp"))
        assertTrue(functionSource("PerfettoConfigurationPanel").contains(".padding(10.dp)"))
    }

    @Test
    fun `group cards preserve capability target package checked state and checkbox callbacks`() {
        val card = functionSource("PerfettoDataSourceGroupCard")
        val rows = blockSource(card, "probes.chunked(2).forEach { probeRow ->")
        val options = blockSource(rows, "probeRow.forEach { probe ->")
        val reason =
            options
                .substringAfter("val reason =", "")
                .substringBefore("PerfettoProbeOption(")
                .replace(Regex("\\s+"), " ")
                .trim()

        assertEquals(
            "capabilities?.unsupportedReason(probe) " +
                "?: if (probe.requiresTargetPackage && targetPackage.isBlank()) { " +
                "\"Requires a target package\" } else { null }",
            reason,
        )
        assertTrue(options.contains("checked = probe in enabledProbes && reason == null"))
        assertTrue(options.contains("reason = reason"))
        assertTrue(options.contains("onProbeChange = onProbeChange"))

        val probeOption = functionSource("PerfettoProbeOption")
        assertTrue(probeOption.contains("Checkbox("))
        assertTrue(probeOption.contains("checked = checked"))
        assertTrue(probeOption.contains("enabled = reason == null"))
        assertTrue(probeOption.contains("onCheckedChange = { onProbeChange(probe, it) }"))
    }

    @Test
    fun `capture page leaves adb and device selection to the workspace toolbar`() {
        assertFalse(source.contains("\"ADB Path\""))
        assertFalse(source.contains("\"Device:\""))
        assertFalse(Regex("(?m)^\\s*Card\\(").containsMatchIn(source))
        assertFalse(source.contains("RadioButton("))
        assertFalse(source.contains("OutlinedTextField("))
    }

    private fun functionSource(name: String): String = blockSource(source, "fun $name(")

    private fun blockSource(
        text: String,
        marker: String,
    ): String {
        val start = text.indexOf(marker)
        assertTrue(start >= 0, "Missing source block: $marker")
        val openingBrace = text.indexOf('{', start)
        assertTrue(openingBrace >= 0, "Missing opening brace: $marker")
        var depth = 0
        for (index in openingBrace until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("Unclosed source block: $marker")
    }
}
