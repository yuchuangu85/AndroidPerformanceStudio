package com.androidperformancestudio.aiplatform.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DshMarkdownTest {
    @Test
    fun `report blocks render in both themes without navigating or overflowing the viewport`() {
        for (width in listOf(480, 900, 1280)) {
            for (dark in listOf(false, true)) {
                runDesktopComposeUiTest(width = width, height = 900) {
                    val colors = if (dark) DshChatColors.Dark else DshChatColors.Light
                    setContent {
                        ViewerTheme(dark) {
                            CompositionLocalProvider(LocalDshChatColors provides colors) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .background(colors.background)
                                        .verticalScroll(rememberScrollState())
                                        .padding(24.dp)
                                        .testTag("markdown-viewport"),
                                ) {
                                    DshSafeText(REPORT, UiLanguage.SIMPLIFIED_CHINESE, "report")
                                }
                            }
                        }
                    }
                    onNodeWithText("CPU 分析报告").assertIsDisplayed()
                    onNodeWithText("热点函数").assertExists()
                    onNodeWithText("renderFrame()").assertExists()
                    onNodeWithText("先验证证据，再优化。", substring = true).assertExists()
                    val image = onNodeWithTag("markdown-viewport").captureToImage().toPixelMap()
                    val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until image.height) for (x in 0 until image.width) bitmap.setRGB(x, y, image[x, y].toArgb())
                    val target = Path.of("build/reports/dsh-markdown/report-$width-${if (dark) "dark" else "light"}.png")
                    Files.createDirectories(target.parent)
                    assertTrue(ImageIO.write(bitmap, "png", target.toFile()))
                }
            }
        }
    }

    @Test
    fun `code wrapping toggles layout without changing code contents`() =
        runDesktopComposeUiTest(width = 480, height = 700) {
            val code = "val payload = \"${"unchanged_".repeat(18)}\""
            setContent {
                ViewerTheme(false) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        DshSafeText("```kotlin\n$code\n```", UiLanguage.ENGLISH, "snippet")
                    }
                }
            }
            val results = mutableListOf<TextLayoutResult>()
            val text = onNodeWithTag("snippet-block-0-code-text")
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(1, results.last().lineCount)
            onNodeWithTag("snippet-block-0-code-wrap").performClick()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(results.last().lineCount > 1)
            text.assertTextContains(code)
            onNodeWithTag("snippet-block-0-code-copy").performClick()
            text.assertTextContains(code)
        }

    @Test
    fun `wide tables scroll locally and malicious links and HTML stay inert`() =
        runDesktopComposeUiTest(width = 480, height = 700) {
            setContent {
                ViewerTheme(false) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        DshSafeText(
                            "| A | B | C | D |\n|---|---|---|---|\n| one | two | three | four |\n\n" +
                                "[secret](file:///private/key)\n\n<script>alert(1)</script>",
                            UiLanguage.ENGLISH,
                            "safe",
                        )
                    }
                }
            }
            val table = onNodeWithTag("safe-block-0-table-scroll")
            val initial = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(initial.maxValue() > 0f)
            table.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> assertTrue(scroll(180f, 0f)) }
            val scrolled = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue(scrolled.value() > 0f)
            onNodeWithText("[secret](file:///private/key)").performScrollTo().assertExists()
            onNodeWithText("<script>alert(1)</script>").performScrollTo().assertExists()
        }

    @Test
    fun `code highlighting preserves all source characters and unknown languages remain plain`() {
        val code = "val answer = 42 // unchanged\nprintln(\"test\")"
        val highlighted = dshHighlightCode(code, "kotlin", DshChatColors.Light)
        assertEquals(code, highlighted.text)
        assertTrue(highlighted.spanStyles.isNotEmpty())
        assertEquals(code, dshHighlightCode(code, "unknown", DshChatColors.Dark).text)
        assertFalse(dshHighlightCode(code, "unknown", DshChatColors.Dark).spanStyles.isNotEmpty())
    }

    private companion object {
        val REPORT =
            """
            # CPU 分析报告

            **当前范围**存在热点，优先检查 `renderFrame()` 的自身开销。
            结论仍需结合 *数据质量*，避免 ~~直接推断 ANR~~。

            ## 热点函数

            | 函数 | 自身占比 | 样本数 |
            | :--- | ---: | ---: |
            | `renderFrame()` | **42%** | 1280 |
            | `dispatch()` | 18% | 540 |

            > 先验证证据，再优化。

            ### 后续检查

            - [x] 确认采集范围
            - [ ] 检查主要调用链
              1. 主线程热点
              2. Native 开销

            ```kotlin
            fun renderFrame() {
                val sampleCount = 1280
                // Keep measurements separate from hypotheses.
                println("CPU hotspots")
            }
            ```

            ---

            [Android docs](https://developer.android.com) 保持为惰性文本，不自动打开。
            """.trimIndent()
    }
}
