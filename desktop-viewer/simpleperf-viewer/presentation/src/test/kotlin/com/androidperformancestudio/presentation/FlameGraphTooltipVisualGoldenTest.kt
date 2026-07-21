package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameTheme
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FlameGraphTooltipVisualGoldenTest {
    @Test
    fun `light Firefox call node tooltip matches the pinned visual golden`() = verifyGolden("light", dark = false)

    @Test
    fun `dark Firefox call node tooltip matches the pinned visual golden`() = verifyGolden("dark", dark = true)

    private fun verifyGolden(
        name: String,
        dark: Boolean,
    ) {
        assumeMacOsGoldenHost()
        runDesktopComposeUiTest(width = GOLDEN_WIDTH, height = GOLDEN_HEIGHT) {
            val theme = if (dark) FlameTheme.DARK else FlameTheme.LIGHT
            val background = if (dark) Color(0xFF18181A) else Color.White
            setContent {
                MaterialTheme(
                    colorScheme =
                        if (dark) {
                            darkColorScheme(background = background, surface = background)
                        } else {
                            lightColorScheme(background = background, surface = background)
                        },
                ) {
                    Box(Modifier.fillMaxSize().background(background).testTag(GOLDEN_TAG)) {
                        FirefoxFlameGraphTooltip(
                            facts = tooltipGoldenFacts(),
                            style = FirefoxFlameGraphStyle.resolve(theme),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            val actual = onNodeWithTag(GOLDEN_TAG).captureToImage()
            val goldenPath = Path.of("src/test/resources/goldens/firefox-call-node-tooltip-$name.png")
            if (System.getenv("UPDATE_FLAME_GOLDENS") == "1") {
                Files.createDirectories(goldenPath.parent)
                ImageIO.write(actual.toBufferedImage(), "png", goldenPath.toFile())
            }
            assertTrue(Files.exists(goldenPath), "Missing golden $goldenPath; run with UPDATE_FLAME_GOLDENS=1")
            val expected = ImageIO.read(goldenPath.toFile())
            assertEquals(expected.width, actual.width)
            assertEquals(expected.height, actual.height)
            val mismatchRatio = actual.mismatchRatio(expected)
            assertTrue(
                mismatchRatio <= MAXIMUM_MISMATCH_RATIO,
                "Firefox call node tooltip golden mismatch for $name: $mismatchRatio",
            )
        }
    }
}

private fun assumeMacOsGoldenHost() {
    assumeTrue(
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
        "macOS visual goldens are pinned to macOS text and graphics rendering.",
    )
}

private fun tooltipGoldenFacts() =
    FlameGraphTooltipFacts(
        function = "android.view.ThreadedRenderer.draw",
        category = "Rendering",
        implementation = FrameImplementation.MANAGED,
        resource = "/system/framework/framework.jar",
        inclusiveWeight = 12_345,
        selfWeight = 1_280,
        sampleCount = 90,
        threadCount = 2,
        percentage = 61.728,
        previewRangeWeight = null,
    )

private const val GOLDEN_TAG = "firefox-tooltip-golden"
private const val GOLDEN_WIDTH = 620
private const val GOLDEN_HEIGHT = 280
private const val MAXIMUM_MISMATCH_RATIO = 0.03
