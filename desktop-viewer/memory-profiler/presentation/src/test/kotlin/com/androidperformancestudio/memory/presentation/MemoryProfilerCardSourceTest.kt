package com.androidperformancestudio.memory.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProfilerCardSourceTest {
    @Test
    fun `metrics findings and bitmap cards use shared surfaces without duplicate drawing`() {
        mapOf(
            "MemoryProfilerScreen.kt" to
                listOf(
                    "LeakSuspectsPhaseTwo",
                    "ActivityLeakSection",
                    "NativeHeapSummarySection",
                    "HeapDiffSection",
                    "BitmapSection",
                ),
            "MemoryProfilerBitmapDumpPage.kt" to listOf("BitmapSummaryMetricCard", "BitmapDumpImageCard"),
        ).forEach { (file, functions) ->
            val source = source(file)
            assertTrue(source.contains("import com.androidperformancestudio.ui.studio.StudioCard"))
            functions.forEach { name ->
                val body = functionSource(source, name)
                assertTrue(body.contains("StudioCard("), "$name must use the shared card")
                assertFalse(body.contains(".background("), "$name must not draw a duplicate background")
                assertFalse(body.contains(".border("), "$name must not draw a duplicate border")
            }
        }
        val metric = functionSource(source("MemoryProfilerScreen.kt"), "MetricCard")
        assertTrue(metric.contains("StudioMetricCard("))
        assertTrue(metric.contains("label = label"))
        assertTrue(metric.contains("value = value"))
        assertTrue(metric.contains("modifier = modifier"))
        assertFalse(metric.contains(".padding("))
    }

    @Test
    fun `offline leak investigation uses structural panes instead of nested finding cards`() {
        listOf(
            "MemoryProfilerLeakCanaryPage.kt",
            "MemoryLeakInvestigationPanes.kt",
            "MemoryLeakLiveControls.kt",
        ).forEach { file ->
            val source = source(file)
            assertFalse(source.contains("StudioCard("), "$file must remain an unframed investigation workspace")
            assertFalse(source.contains("ViewerTypography.pageTitle"))
            assertFalse(source.contains("ViewerTypography.metric"))
        }
        val page = source("MemoryProfilerLeakCanaryPage.kt")
        assertTrue(page.contains("MemoryLeakFindingsPane("))
        assertTrue(page.contains("MemoryLeakPathPane("))
        assertTrue(page.contains("MemoryObjectInspector("))
        assertTrue(page.contains("HorizontalScrollbar("))
    }

    @Test
    fun `leak suspect expansion and image sizing remain on the original content`() {
        val suspects = functionSource(source("MemoryProfilerScreen.kt"), "LeakSuspectsPhaseTwo")
        assertTrue(suspects.contains("StudioCard("))
        assertTrue(suspects.contains(".clickable { expanded = !expanded }"))
        assertTrue(suspects.contains("remember(suspect)"))
        assertTrue(suspects.contains("if (expanded)"))
        assertTrue(suspects.contains("suspect.referenceChain.forEachIndexed"))

        val bitmapSource = source("MemoryProfilerBitmapDumpPage.kt")
        val metrics = functionSource(bitmapSource, "BitmapSummaryMetricCard")
        assertTrue(metrics.contains(".width(150.dp)"))
        val image = functionSource(bitmapSource, "BitmapDumpImageCard")
        assertTrue(image.contains(".testTag(\"bitmap-dump-image-card-\${bitmap.recordIndex}\")"))
        assertTrue(image.contains("contentScale = ContentScale.Fit"))
        assertTrue(image.contains("Modifier.fillMaxWidth().height(120.dp)"))
        assertTrue(image.contains("if (!Files.isRegularFile(bitmap.file)) return@runCatching null"))
        assertTrue(image.contains("Res.string.bitmap_image_unavailable"))
    }

    @Test
    fun `snapshot tabs tables and page wrappers stay out of card migration`() {
        val tabs = source("MemoryProfilerSnapshotTabs.kt")
        assertFalse(tabs.contains("StudioCard("))
        assertTrue(tabs.contains(".clickable(role = Role.Tab)"))
        assertTrue(tabs.contains("this.selected = selected"))
        assertTrue(tabs.contains("onSelectSnapshot(snapshot.id)"))
        assertTrue(tabs.contains("onCloseSnapshot(snapshot.id)"))
        val inlineMetric = functionSource(source("MemoryProfilerClassListView.kt"), "SummaryMetric")
        assertTrue(inlineMetric.contains("Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertFalse(inlineMetric.contains("StudioCard("))
        assertFalse(inlineMetric.contains(".background("))
        assertFalse(inlineMetric.contains(".border("))
        val histogram = functionSource(source("MemoryProfilerScreen.kt"), "Histogram")
        assertFalse(histogram.contains("StudioCard("))
        assertTrue(histogram.contains(".height(300.dp)"))
        val gallery = functionSource(source("MemoryProfilerBitmapDumpPage.kt"), "BitmapDumpGalleryContent")
        assertFalse(gallery.contains("StudioCard("))
        assertTrue(gallery.contains("GridCells.Adaptive(minSize = 180.dp)"))
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/memory/presentation", file))

    private fun functionSource(
        source: String,
        name: String,
    ): String {
        val marker = "fun $name("
        assertTrue(source.contains(marker), "Missing function: $name")
        return source.substringAfter(marker).substringBefore("\n@Composable")
    }
}
