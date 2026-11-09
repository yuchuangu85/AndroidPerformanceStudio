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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ComposeCompilerReportParserTest {
    @TempDir
    lateinit var reports: Path

    @Test
    fun `compiler CSV and text reports merge flags and parameter stability instead of duplicating functions`() {
        writeCompilerReports(reports)

        val report = ComposeCompilerReportParser().parse(reports)

        assertEquals(3, report.files.size)
        val function = report.functions.single()
        assertEquals("com.example", function.packageName)
        assertEquals("Feed", function.name)
        assertTrue(function.restartable)
        assertTrue(function.skippable)
        assertEquals(ComposeCompilerStability.UNSTABLE, function.parameters.first().stability)
        assertEquals("FeedState", function.parameters.first().type)
        assertEquals(ComposeCompilerStability.UNKNOWN, function.parameters[1].stability)
        assertEquals("Modifier?", function.parameters.last().type)
        assertEquals("FeedState", report.classes.single().name)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun `selecting any report file imports sibling formats for only that module`() {
        writeCompilerReports(reports)
        reports.resolve("other-composables.txt").writeText("restartable fun Other()")

        for (suffix in listOf("-classes.txt", "-composables.csv", "-composables.txt")) {
            val report = ComposeCompilerReportParser().parse(reports.resolve("app$suffix"))
            assertEquals(3, report.files.size)
            assertEquals("Feed", report.functions.single().name)
            assertEquals(ComposeCompilerStability.UNSTABLE, report.functions.single().parameters.first().stability)
        }
    }

    @Test
    fun `same named functions and separate module reports do not silently discard parameters`() {
        writeCompilerReports(reports)
        reports.resolve("other-composables.txt").writeText(
            """restartable fun Feed(
  stable count: Int
)
""",
        )

        val report = ComposeCompilerReportParser().parse(reports)
        assertEquals(2, report.functions.size)
        assertEquals(setOf("state", "count"), report.functions.map { it.parameters.first().name }.toSet())
    }

    @Test
    fun `empty unsupported and unreadable format selections fail instead of loading empty reports`() {
        val parser = ComposeCompilerReportParser()
        assertThrows(IllegalArgumentException::class.java) { parser.parse(reports) }
        assertThrows(IllegalArgumentException::class.java) { parser.parse(reports.resolve("missing")) }
        val metrics = reports.resolve("app-module.json").apply { writeText("{}") }
        assertThrows(IllegalArgumentException::class.java) { parser.parse(metrics) }
        reports.resolve("app-composables.csv").writeText("unsupported,columns\n1,2")
        assertThrows(IllegalArgumentException::class.java) { parser.parse(reports) }
    }

    private fun writeCompilerReports(directory: Path) {
        directory.resolve("app-composables.csv").writeText(
            """package,name,composable,skippable,restartable,readonly,inline,isLambda,hasDefaults,defaultsGroup,groups,calls,
com.example.Feed,Feed,1,1,1,0,0,0,0,0,11,7,
""",
        )
        directory.resolve("app-composables.txt").writeText(
            """restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun Feed(
  unstable state: FeedState
  retained: RetainedState
  stable modifier: Modifier? = @static Companion
)
""",
        )
        directory.resolve("app-classes.txt").writeText(
            """unstable class FeedState {
  unstable val items: List<Item>
  <runtime stability> = Unstable
}
""",
        )
    }

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
