package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.localizedStringResource
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class ComposeCompilerReportGuideTest {
    @Test
    fun `settings guide is readable and copyable across languages widths and themes`() {
        for (width in listOf(400, 900)) {
            for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
                for (darkTheme in listOf(false, true)) {
                    runDesktopComposeUiTest(width = width, height = 800) {
                        setContent {
                            ViewerTheme(darkTheme = darkTheme) {
                                LayoutInspectorSettingsContent(
                                    language = language,
                                    onSettingsChanged = {},
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        for (resource in guideResources) {
                            val node = onNodeWithText(localizedStringResource(resource, language), useUnmergedTree = true)
                            node.performScrollTo().assertIsDisplayed()
                            val layouts = mutableListOf<TextLayoutResult>()
                            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            val layout = layouts.single()
                            assertCompleteLayout(layout, width)
                        }

                        for ((tag, text) in listOf(
                            "compose-report-configuration" to COMPOSE_REPORT_CONFIGURATION,
                            "compose-report-generate-command" to COMPOSE_REPORT_GENERATE_COMMAND,
                        )) {
                            val node = onNodeWithTag(tag)
                            node.performScrollTo().assertIsDisplayed().assertTextEquals(text)
                            val copyActions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
                            assertTrue(copyActions.isNotEmpty(), "Expected a full-text copy action for $tag")
                            val layouts = mutableListOf<TextLayoutResult>()
                            onNodeWithText(text, useUnmergedTree = true)
                                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            assertCompleteLayout(layouts.single(), width)
                        }

                        if (!darkTheme) {
                            onNodeWithTag("compose-report-configuration").performScrollTo()
                            val pixels = onRoot().captureToImage().toPixelMap()
                            val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                            for (y in 0 until pixels.height) {
                                for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                            }
                            val output = Path.of("build/reports/compose-report-guide/${language.name.lowercase()}-$width.png")
                            Files.createDirectories(output.parent)
                            ImageIO.write(image, "png", output.toFile())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `localized guide preserves Gradle identifiers and supported report formats`() {
        guideResources.forEach { resource ->
            assertNotEquals(
                localizedStringResource(resource, UiLanguage.ENGLISH),
                localizedStringResource(resource, UiLanguage.SIMPLIFIED_CHINESE),
                "Missing translated guide text: $resource",
            )
        }
        for (language in listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE)) {
            val configuration = localizedStringResource(Res.string.compose_reports_configure_hint, language)
            listOf("org.jetbrains.kotlin.plugin.compose", "build.gradle.kts", "reportsDestination", "metricsDestination")
                .forEach { assertTrue(configuration.contains(it), "Missing configuration identifier: $it") }
            val output = localizedStringResource(Res.string.compose_reports_output_hint, language)
            listOf("app/build/compose_compiler", "*-composables.txt", "*-composables.csv", "*-classes.txt", "*-module.json")
                .forEach { assertTrue(output.contains(it), "Missing output format: $it") }
            assertTrue(localizedStringResource(Res.string.compose_reports_generate_hint, language).contains("--rerun-tasks"))
        }
    }

    private fun assertCompleteLayout(layout: TextLayoutResult, width: Int) {
        // Text bounds use whole pixels, while paragraph metrics can retain a fractional pixel.
        assertTrue(
            layout.multiParagraph.width <= layout.size.width + 1f &&
                layout.multiParagraph.height <= layout.size.height + 1f,
            "Overflow at width $width: ${layout.layoutInput.text.text}; " +
                "bounds=${layout.size}, paragraph=${layout.multiParagraph.width} x ${layout.multiParagraph.height}, " +
                "style=${layout.layoutInput.style}, constraints=${layout.layoutInput.constraints}, " +
                "density=${layout.layoutInput.density.density}, fontScale=${layout.layoutInput.density.fontScale}",
        )
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
    }

    private companion object {
        val guideResources = listOf(
            Res.string.compose_reports_title,
            Res.string.compose_reports_purpose,
            Res.string.compose_reports_configure,
            Res.string.compose_reports_configure_hint,
            Res.string.compose_reports_generate,
            Res.string.compose_reports_generate_hint,
            Res.string.compose_reports_output_hint,
            Res.string.compose_reports_import,
            Res.string.compose_reports_import_hint,
            Res.string.compose_reports_limitations,
        )
    }
}
