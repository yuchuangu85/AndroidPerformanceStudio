package com.androidperformancestudio.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle
import com.androidperformancestudio.visualization.FlameTheme
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FlameGraphPanelLayoutTest {
    @Test
    fun `panel gives canvas a non zero Firefox viewport that fills remaining height`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            setContent {
                MaterialTheme {
                    FlameGraphPanel(
                        sessionIdentity = Path.of("fixture"),
                        state = FlameGraphPanelState(),
                        snapshot = accessibilitySnapshot(),
                        actions = noOpReportActions(),
                    )
                }
            }

            val canvasBounds = onNodeWithContentDescription("Flame graph call stacks").fetchSemanticsNode().boundsInRoot
            val frameBounds = onNodeWithContentDescription("renderFrame, 60%, Native").fetchSemanticsNode().boundsInRoot

            assertEquals(900f, canvasBounds.width)
            assertTrue(canvasBounds.height > 600f)
            assertEquals(539f, frameBounds.width)
            assertEquals(16f, frameBounds.height)
        }

    @Test
    fun `viewport clips flame rows above and below its border`() =
        runDesktopComposeUiTest(width = 300, height = 400) {
            setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Green)
                        .testTag("clip-root"),
                ) {
                    FirefoxFlameGraphViewport(
                        style = FirefoxFlameGraphStyle.resolve(FlameTheme.LIGHT),
                        details = FlameGraphDetailsState.Closed,
                        onCloseDetails = {},
                        modifier = Modifier.fillMaxSize().padding(vertical = 40.dp),
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                color = Color.Red,
                                topLeft = Offset(0f, -100f),
                                size = Size(size.width, size.height + 200f),
                            )
                        }
                    }
                }
            }

            val pixels = onNodeWithTag("clip-root").captureToImage().toPixelMap()

            assertEquals(Color.Green, pixels[150, 20])
            assertEquals(Color.Red, pixels[150, 200])
            assertEquals(Color.Green, pixels[150, 380])
        }

    @Test
    fun `sample weight footer uses only one compact text row`() =
        runDesktopComposeUiTest(width = 900, height = 700) {
            setContent {
                MaterialTheme {
                    ReportPage(
                        state = sampleReportState(ReportTab.FLAME_GRAPH),
                        actions = goldenActions(),
                    )
                }
            }

            val canvasBounds =
                onNodeWithContentDescription("Flame graph call stacks")
                    .fetchSemanticsNode()
                    .boundsInRoot
            val footerBounds =
                onNodeWithText(ReportController.WEIGHT_SEMANTICS)
                    .fetchSemanticsNode()
                    .boundsInRoot

            val footerHeight = footerBounds.bottom - canvasBounds.bottom
            assertTrue(
                footerHeight <= 24f,
                "Footer still reserves $footerHeight px: canvas=$canvasBounds footer=$footerBounds",
            )
        }
}

private fun noOpReportActions() =
    ReportActions(
        onOpenSession = {},
        onCloseSession = {},
        onSelectTab = {},
        onTimeRange = { _, _ -> },
        onThreads = {},
        onEvents = {},
        onTopFunctions = { _, _, _ -> },
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
