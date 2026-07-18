package com.androidperformancestudio.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.FlameGraphPanelState
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FlameGraphVisualGoldenTest {
    @Test
    fun `light Firefox flame graph matches the pinned visual golden`() = verifyGolden("light", dark = false)

    @Test
    fun `dark Firefox flame graph matches the pinned visual golden`() = verifyGolden("dark", dark = true)

    private fun verifyGolden(
        name: String,
        dark: Boolean,
    ) = runDesktopComposeUiTest(width = GOLDEN_WIDTH, height = GOLDEN_HEIGHT) {
        setContent {
            MaterialTheme(
                colorScheme =
                    if (dark) {
                        darkColorScheme(background = Color(0xFF18181A), surface = Color(0xFF18181A))
                    } else {
                        lightColorScheme(background = Color.White, surface = Color.White)
                    },
            ) {
                Box(Modifier.fillMaxSize().testTag(GOLDEN_TAG)) {
                    FlameGraphPanel(
                        sessionIdentity = Path.of("firefox-compatibility-fixture"),
                        state = FlameGraphPanelState(),
                        snapshot = accessibilitySnapshot(),
                        actions = goldenActions(),
                    )
                }
            }
        }
        waitForIdle()
        val actual = onNodeWithTag(GOLDEN_TAG).captureToImage()
        val goldenPath = Path.of("src/test/resources/goldens/firefox-flame-graph-$name.png")
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
            "Firefox flame graph golden mismatch for $name: $mismatchRatio",
        )
    }
}

internal fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = toPixelMap()
    return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, pixels[x, y].argbInt())
            }
        }
    }
}

internal fun ImageBitmap.mismatchRatio(expected: BufferedImage): Double {
    val actual = toPixelMap()
    var mismatches = 0L
    val total = width.toLong() * height.toLong()
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (!actual[x, y].matches(expected.getRGB(x, y))) mismatches++
        }
    }
    return mismatches.toDouble() / total.toDouble()
}

private fun Color.matches(expectedArgb: Int): Boolean {
    val actualArgb = argbInt()
    return CHANNEL_SHIFTS.all { shift ->
        abs((actualArgb ushr shift and CHANNEL_MASK) - (expectedArgb ushr shift and CHANNEL_MASK)) <= CHANNEL_TOLERANCE
    }
}

private fun Color.argbInt(): Int =
    (alpha.channelByte() shl 24) or
        (red.channelByte() shl 16) or
        (green.channelByte() shl 8) or
        blue.channelByte()

private fun Float.channelByte(): Int = (this * CHANNEL_MASK).roundToInt().coerceIn(0, CHANNEL_MASK)

internal fun goldenActions() =
    ReportActions(
        onOpenSession = {},
        onCloseSession = {},
        onSelectTab = {},
        onTimeRange = { _, _ -> },
        onThreads = {},
        onEvents = {},
        onTopFunctionSort = { _, _ -> },
        onCallTreeDirection = {},
        onFlamePreviewRange = {},
        onCancelFlamePreview = {},
        onFlameSearch = {},
        onFlameImplementation = {},
        onApplyFlameTransform = {},
        onUndoFlameTransform = {},
        onClearFlameTransforms = {},
        onRetryFlameProjection = {},
        onSelectCallNode = {},
        onHoverFlameNode = {},
        onOpenFlameContext = {},
        onOpenFlameDetails = {},
        onCloseFlameDetails = {},
        onCopyFlameFunction = {},
        onNavigateFlameNode = { null },
        onFocusCallTreeFunction = {},
        onFocusFunction = {},
        onExportSession = {},
        onExportReport = {},
        onExportRawProtobuf = {},
        onExportScreenshot = {},
        onGenerateSimpleperfReport = {},
        onGenerateHtmlReport = {},
        onExportExternalGuide = {},
    )

private const val GOLDEN_TAG = "firefox-flame-golden"
private const val GOLDEN_WIDTH = 900
private const val GOLDEN_HEIGHT = 520
private const val CHANNEL_MASK = 0xFF
private const val CHANNEL_TOLERANCE = 8
private const val MAXIMUM_MISMATCH_RATIO = 0.03
private val CHANNEL_SHIFTS = intArrayOf(24, 16, 8, 0)
