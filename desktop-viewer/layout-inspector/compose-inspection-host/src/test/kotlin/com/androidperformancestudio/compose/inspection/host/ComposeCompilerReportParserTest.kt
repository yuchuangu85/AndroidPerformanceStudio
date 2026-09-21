package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposeCompilerStability
import com.androidperformancestudio.compose.inspection.ComposeJankObservation
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeCapabilityState
import com.androidperformancestudio.compose.inspection.ComposeFrameCompleteness
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.protocol.Bounds
import java.nio.file.Files
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeCompilerReportParserTest {
    @Test
    fun `parses stability reports and combines runtime counters with explicit jank`() {
        val directory = Files.createTempDirectory("compose-reports")
        directory.resolve("app-composables.txt").writeText(
            """restartable scheme(\"[androidx.compose.ui.UiComposable]\") fun Feed(
  unstable state: FeedState,
  stable title: String
)
""",
        )
        directory.resolve("app-classes.txt").writeText(
            """unstable class FeedState {
  unstable items: List<Item>
}
""",
        )

        val report = ComposeCompilerReportParser().parse(directory)
        val function = report.functions.single()
        assertTrue(function.restartable)
        assertFalse(function.skippable)
        assertEquals(ComposeCompilerStability.UNSTABLE, function.parameters.first().stability)
        assertEquals("FeedState", report.classes.single().name)

        val document = ComposeInspectionDocument(
            packageName = "dev.example",
            capturedAtEpochMillis = 1,
            frame = ComposeInspectionFrame(
                frameId = "frame",
                generation = 1,
                mode = ComposeInspectionMode.FULL,
                capabilities = emptyList<ComposeCapabilityState>(),
                roots = listOf(
                    ComposableRoot(
                        viewId = 1,
                        nodes = listOf(
                            ComposableNode(
                                id = 7,
                                anchorHash = 1,
                                name = "Feed",
                                bounds = Bounds(0, 0, 100, 100),
                                recomposeCount = 9,
                                skipCount = 2,
                            ),
                        ),
                    ),
                ),
                completeness = ComposeFrameCompleteness.COMPLETE,
            ),
        )
        val finding = ComposeStabilityAnalyzer().analyze(
            report,
            document,
            listOf(ComposeJankObservation("Feed", frameId = 9, isJank = true, source = "JankStats")),
        ).single()
        assertEquals(9, finding.recomposeCount)
        assertEquals(2, finding.skipCount)
        assertEquals(1, finding.jankFrameCount)
        assertEquals("state", finding.unstableParameters.single().name)
    }
}
