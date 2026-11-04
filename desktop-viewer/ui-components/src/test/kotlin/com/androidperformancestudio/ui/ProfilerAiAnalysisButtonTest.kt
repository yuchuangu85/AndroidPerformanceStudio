package com.androidperformancestudio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProfilerAiAnalysisButtonTest {
    @Test
    fun `compact analysis entry fits the toolbar in both languages themes and widths`() {
        for (width in listOf(360, 900, 1280)) {
            for (dark in listOf(false, true)) {
                for (language in UiLanguage.entries) {
                    runDesktopComposeUiTest(width = width, height = 100) {
                        val host =
                            object : AiContextHost {
                                override fun publish(context: UiAiContext) {}

                                override fun remove(key: String) {}

                                override fun analyze(context: UiAiContext) {
                                    error("Screenshot must not execute analysis")
                                }
                            }
                        setContent {
                            ViewerTheme(dark) {
                                CompositionLocalProvider(LocalAiContextHost provides host, LocalWindowMenuBarActive provides true) {
                                    HeaderToolbar(
                                        language = language,
                                        onNavigateHome = null,
                                        onNavigateSettings = null,
                                        modifier = Modifier.testTag("toolbar"),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            ProfilerAiAnalysisButton(context("{}"), language)
                                            ProfilerCompactButton("Capture", {})
                                            ProfilerCompactButton("Import", {})
                                        }
                                    }
                                }
                            }
                        }
                        val button = onNodeWithTag("profiler-ai-analysis").getUnclippedBoundsInRoot()
                        val toolbar = onNodeWithTag("toolbar").getUnclippedBoundsInRoot()
                        val label =
                            onNodeWithText(
                                if (language ==
                                    UiLanguage.SIMPLIFIED_CHINESE
                                ) {
                                    "AI 分析"
                                } else {
                                    "AI Analysis"
                                },
                            ).getUnclippedBoundsInRoot()
                        assertTrue(button.top >= toolbar.top && button.bottom <= toolbar.bottom)
                        assertTrue(label.left >= button.left && label.right <= button.right)
                        val pixels = onNodeWithTag("toolbar").captureToImage().toPixelMap()
                        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                        val file = Path.of("build/reports/profiler-ai-entry/$width-${language.name}-$dark.png")
                        Files.createDirectories(file.parent)
                        assertTrue(ImageIO.write(image, "png", file.toFile()))
                    }
                }
            }
        }
    }

    @Test
    fun `analyze sends exact displayed context only on click and disables missing oversized or inactive context`() =
        runDesktopComposeUiTest {
            var current by mutableStateOf<UiAiContext?>(context("{}"))
            var active by mutableStateOf(true)
            var busy by mutableStateOf(false)
            val requests = mutableListOf<UiAiContext>()
            val host =
                object : AiContextHost {
                    override val analysisBusy: Boolean get() = busy

                    override fun publish(context: UiAiContext) {}

                    override fun remove(key: String) {}

                    override fun analyze(context: UiAiContext) {
                        requests += context
                    }
                }
            setContent {
                ViewerTheme(false) {
                    CompositionLocalProvider(LocalAiContextHost provides host, LocalWindowMenuBarActive provides active) {
                        ProfilerAiAnalysisButton(current, UiLanguage.SIMPLIFIED_CHINESE)
                    }
                }
            }
            assertEquals(0, requests.size)
            onNodeWithTag("profiler-ai-analysis").assertIsEnabled().performClick()
            assertSame(current, requests.single())
            runOnIdle { current = null }
            onNodeWithTag("profiler-ai-analysis").assertIsNotEnabled()
            runOnIdle { current = context("测".repeat(3000)) }
            onNodeWithTag("profiler-ai-analysis").assertIsNotEnabled()
            runOnIdle {
                current = context("{} candidates")
                active = false
            }
            onNodeWithTag("profiler-ai-analysis").assertIsNotEnabled()
            assertEquals(1, requests.size)
            runOnIdle {
                active = true
                busy = true
            }
            onNodeWithTag("profiler-ai-analysis").assertIsNotEnabled()
        }

    @Test
    fun `standalone has no fake AI execution entry`() =
        runDesktopComposeUiTest {
            setContent { ViewerTheme(false) { ProfilerAiAnalysisButton(context("{}"), UiLanguage.ENGLISH) } }
            onNodeWithTag("profiler-ai-analysis").assertDoesNotExist()
        }

    @Test
    fun `UTF8 and total labels are bounded across all evidence items without modifying payload`() {
        val exact = context("x".repeat(8192))
        assertSame(exact, boundedAiContext(exact))
        assertNull(boundedAiContext(context("测".repeat(3000))))
        assertNull(boundedAiContext(context("{}", "x".repeat(2049))))
        val two = exact.copy(evidence = List(2) { exact.evidence.single() })
        assertNull(boundedAiContext(two))
    }

    private fun context(
        payload: String,
        summary: String = "Measured frame",
    ) = UiAiContext("frame", "Frame analysis", true, listOf(UiAiEvidence("frame:1", "frame-selection", summary, payload)))
}
