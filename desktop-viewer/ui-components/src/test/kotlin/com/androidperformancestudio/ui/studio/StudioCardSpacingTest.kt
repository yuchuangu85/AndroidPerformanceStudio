package com.androidperformancestudio.ui.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.ViewerTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StudioCardSpacingTest {
    @Test
    fun `external card spacing is four dp without reducing content insets`() {
        listOf(900, 1280).forEach { width ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest(width = width, height = 720) {
                    var density = 1f
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            density = LocalDensity.current.density
                            Column(
                                Modifier.fillMaxSize().testTag("workspace").padding(StudioTokens.workspacePadding),
                                verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                            ) {
                                repeat(2) { row ->
                                    Row(
                                        Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
                                    ) {
                                        repeat(2) { column ->
                                            val id = "$row-$column"
                                            StudioCard(Modifier.weight(1f).fillMaxSize().testTag("card-$id")) {
                                                Box(Modifier.size(24.dp).testTag("content-$id"))
                                                Text(if (dark) "性能证据" else "Performance evidence")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val workspace = onNodeWithTag("workspace").fetchSemanticsNode().boundsInRoot
                    val first = onNodeWithTag("card-0-0").fetchSemanticsNode().boundsInRoot
                    val right = onNodeWithTag("card-0-1").fetchSemanticsNode().boundsInRoot
                    val bottom = onNodeWithTag("card-1-0").fetchSemanticsNode().boundsInRoot
                    val last = onNodeWithTag("card-1-1").fetchSemanticsNode().boundsInRoot
                    val content = onNodeWithTag("content-0-0").fetchSemanticsNode().boundsInRoot
                    listOf(
                        first.left - workspace.left,
                        first.top - workspace.top,
                        workspace.right - last.right,
                        workspace.bottom - last.bottom,
                        right.left - first.right,
                        bottom.top - first.bottom,
                    ).forEach { gap -> assertEquals(4f * density, gap, 0.6f) }
                    assertEquals(8f * density, content.left - first.left, 0.6f)
                    assertEquals(8f * density, content.top - first.top, 0.6f)
                    assertEquals(8.dp, StudioTokens.compactContentPadding)
                    assertEquals(8.dp, StudioTokens.panelGap)

                    saveScreenshot(onNodeWithTag("workspace").captureToImage(), width, dark)
                }
            }
        }
    }

    private fun saveScreenshot(image: androidx.compose.ui.graphics.ImageBitmap, width: Int, dark: Boolean) {
        val pixels = image.toPixelMap()
        val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                screenshot.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        val output = Path.of("build/reports/card-spacing/$width-${if (dark) "dark" else "light"}.png")
        Files.createDirectories(output.parent)
        ImageIO.write(screenshot, "png", output.toFile())
    }
}
