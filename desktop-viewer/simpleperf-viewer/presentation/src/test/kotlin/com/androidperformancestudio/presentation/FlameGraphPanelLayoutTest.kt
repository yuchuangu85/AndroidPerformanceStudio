package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.FlameGraphPanelState
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
