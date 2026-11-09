package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.radiobutton.MacOSChoiceChip
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioWorkspaceHeader
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ViewerBackgroundRenderingTest {
    @Test
    fun `base surface pixels and selected text stay stable across theme accents`() =
        runDesktopComposeUiTest(width = 800, height = 520) {
            var darkTheme by mutableStateOf(false)
            var accent by mutableStateOf(ViewerAccentPalette.lemonYellow)
            setContent {
                ViewerTheme(darkTheme, accentColor = accent) {
                    backgroundSamples()
                }
            }

            listOf(false, true).forEach { dark ->
                val accents = listOf(ViewerAccentPalette.lemonYellow, ViewerAccentPalette.royalPurple)
                accents.forEachIndexed { index, color ->
                    runOnIdle {
                        darkTheme = dark
                        accent = color
                    }
                    waitForIdle()
                    val colors = viewerColors(dark, color)
                    val surfaces = listOf(
                        "background-header",
                        "background-card",
                        "background-elevated",
                        "background-studio-metric",
                        "background-profiler-metric",
                        "viewer-text-field-input",
                    )
                    surfaces.forEach { tag ->
                        val pixels = onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
                        assertEquals(
                            colors.panel.toArgb(),
                            pixels[pixels.width - 12, 8].toArgb(),
                            "$tag, dark=$dark, accent=$index",
                        )
                    }

                    val layouts = mutableListOf<TextLayoutResult>()
                    onNodeWithText("Selected option", useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertEquals(colors.primaryText, layouts.single().layoutInput.style.color)

                    val pixels = onNodeWithTag("background-workspace").captureToImage().toPixelMap()
                    assertEquals(colors.workspace.toArgb(), pixels[0, 0].toArgb())
                    val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until pixels.height) {
                        for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                    }
                    val output = Path.of("build/reports/uniform-backgrounds/dark-$dark-accent-$index.png")
                    Files.createDirectories(output.parent)
                    ImageIO.write(image, "png", output.toFile())
                }
            }
        }
}

@Composable
private fun backgroundSamples() {
    val colors = LocalViewerColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.workspace)
            .testTag("background-workspace")
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StudioWorkspaceHeader("CPU Profiler", modifier = Modifier.testTag("background-header"))
        StudioCard(
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("background-card"),
        ) {
            Text("Capture artifact")
        }
        Surface(
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("background-elevated"),
            tonalElevation = 12.dp,
        ) {
            Text("Elevated surface", Modifier.padding(12.dp))
        }
        ViewerTextField("Trace file", {})
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StudioMetricCard(
                "Frames",
                "42",
                modifier = Modifier.weight(1f).height(70.dp).testTag("background-studio-metric"),
                tone = colors.accent,
            )
            ProfilerMetricCard(
                "CPU",
                "12%",
                modifier = Modifier.weight(1f).height(70.dp).testTag("background-profiler-metric"),
            )
        }
        MacOSChoiceChip("Selected option", true, true, colors, {})
    }
}
