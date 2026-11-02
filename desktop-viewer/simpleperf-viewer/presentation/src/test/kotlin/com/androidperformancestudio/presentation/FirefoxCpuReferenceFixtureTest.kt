package com.androidperformancestudio.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.FlameGraphPanelState
import com.androidperformancestudio.application.OfflineImportRequest
import com.androidperformancestudio.application.OfflineImportResult
import com.androidperformancestudio.application.OfflineProfileFormat
import com.androidperformancestudio.application.OfflineProfileImporter
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import com.androidperformancestudio.storage.PanelProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirefoxCpuReferenceFixtureTest {
    @Test
    fun `upstream public simpleperf fixture imports and renders the same selected thread`() {
        val fixture = Path.of("../../../third_party/firefox-profiler/src/test/fixtures/upgrades", FIXTURE_NAME)
        assumeTrue(Files.isRegularFile(fixture), "Initialize the Firefox submodule for the shared-input check")
        val sha =
            MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(fixture))
                .joinToString("") { "%02x".format(it) }
        assertEquals(FIXTURE_SHA256, sha)
        val root = Files.createTempDirectory("aps-firefox-reference-")
        try {
            val report = loadReport(fixture, root)
            assertEquals(FILTERED_SAMPLE_COUNT, report.overview.sampleCount)
            val stack = assertIs<PanelProjection.Ready<StackChartSnapshot>>(report.stackChart).value
            assertTrue(stack.blocks.isNotEmpty())
            writeFacts(report, stack)
            listOf(false, true).forEach { dark ->
                captureStack(report, stack, dark)
                captureFlame(report, dark)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun loadReport(
        fixture: Path,
        root: Path,
    ): ReportData =
        runBlocking {
            val trace = root.resolve("input.trace")
            GZIPInputStream(Files.newInputStream(fixture)).use { input ->
                Files.newOutputStream(trace).use { output -> input.copyTo(output) }
            }
            val importer =
                OfflineProfileImporter(
                    hostSimpleperfResolver = { error("A protobuf import must not require host simpleperf") },
                    perfDataConverter = { _, _, _ -> error("The reference is already protobuf") },
                )
            val imported =
                assertIs<StudioResult.Success<OfflineImportResult>>(
                    importer.import(
                        OfflineImportRequest(
                            "reference",
                            root.resolve("sessions"),
                            trace,
                            OfflineProfileFormat.SIMPLEPERF_PROTOBUF,
                        ),
                    ),
                ).value
            ReportController().use { controller ->
                controller.openSession(imported.sessionDirectory)
                val initial = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
                val thread = initial.sessionThreads.single { it.name == "com.example.sampleapplication" }
                controller.updateThreads(setOf(thread.threadId))
                val unfiltered = assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
                assertEquals(UNFILTERED_SAMPLE_COUNT, unfiltered.overview.sampleCount)
                // Upstream's zero-interval import uses this exact right-open committed range.
                controller.commitRange(initial.sessionOverview.startNanos, initial.sessionOverview.endNanosInclusive)
                assertIs<ReportLoadState.Ready>(controller.state.value.loadState).report
            }
        }

    private fun captureStack(
        report: ReportData,
        stack: StackChartSnapshot,
        dark: Boolean,
    ) = runDesktopComposeUiTest(width = REFERENCE_WIDTH, height = REFERENCE_HEIGHT) {
        setContent {
            MaterialTheme(colorScheme = referenceScheme(dark)) {
                StackChartCanvas(
                    stack,
                    StackChartViewport(
                        report.sessionOverview.startNanos ?: 0,
                        requireNotNull(report.sessionOverview.endNanosInclusive),
                    ),
                    null,
                    onSelect = {},
                )
            }
        }
        val output = REPORTS.resolve("aps-reference-stack-$dark.png")
        val image = onNodeWithTag("stack-chart-canvas").captureToImage()
        ImageIO.write(image.toBufferedImage(), "png", output.toFile())
        writePixelDiagnostic(image, dark)
    }

    private fun captureFlame(
        report: ReportData,
        dark: Boolean,
    ) = runDesktopComposeUiTest(width = REFERENCE_WIDTH, height = FLAME_WITH_NAVIGATION_HEIGHT) {
        setContent {
            MaterialTheme(colorScheme = referenceScheme(dark)) {
                FlameGraphPanel(FlameGraphPanelState(), snapshot = report.flameGraph, actions = goldenActions())
            }
        }
        val output = REPORTS.resolve("aps-reference-flame-$dark.png")
        ImageIO.write(onRoot().captureToImage().toBufferedImage(), "png", output.toFile())
    }

    private fun writeFacts(
        report: ReportData,
        stack: StackChartSnapshot,
    ) {
        Files.createDirectories(REPORTS)
        val facts =
            mapOf(
                "sessionStart" to report.sessionOverview.startNanos,
                "sessionEndInclusive" to report.sessionOverview.endNanosInclusive,
                "sessionSamples" to report.sessionOverview.sampleCount,
                "threadStart" to report.overview.startNanos,
                "threadEndInclusive" to report.overview.endNanosInclusive,
                "threadSamples" to report.overview.sampleCount,
                "stackStart" to stack.startNanos,
                "stackEnd" to stack.endNanosExclusive,
                "rootEnd" to stack.blocks.first().endNanosExclusive,
            )
        val json = facts.entries.joinToString(",", prefix = "{", postfix = "}") { (key, value) -> "\"$key\":$value" }
        Files.writeString(REPORTS.resolve("reference-input-facts.json"), json)
    }

    private fun writePixelDiagnostic(
        image: ImageBitmap,
        dark: Boolean,
    ) {
        val theme = if (dark) "dark" else "light"
        val reference = Path.of("../../../build/reports/cpu-firefox-reference/stack-chart-$theme-chart.png")
        if (Files.isRegularFile(reference)) {
            val expected = ImageIO.read(reference.toFile())
            assertEquals(expected.width, image.width)
            assertEquals(expected.height, image.height)
            val rowMismatch = rowFillMismatch(image.toBufferedImage(), expected)
            assertTrue(
                rowMismatch <= MAXIMUM_ROW_FILL_MISMATCH,
                "The pinned same-window row-fill geometry changed: $rowMismatch",
            )
            val json =
                """
                {
                  "sourceRevision": "$SOURCE_REVISION",
                  "width": $REFERENCE_WIDTH,
                  "height": $REFERENCE_HEIGHT,
                  "pixelMismatchRatio": ${image.mismatchRatio(expected)},
                  "rowFillMismatchRatio": $rowMismatch,
                  "verdict": "diagnostic-only"
                }
                """.trimIndent()
            Files.writeString(REPORTS.resolve("stack-reference-diff-$theme.json"), json)
        }
    }
}

private fun rowFillMismatch(
    actual: BufferedImage,
    expected: BufferedImage,
): Double {
    var mismatches = 0
    var compared = 0
    for (y in ROW_FILL_Y until actual.height step REFERENCE_ROW_HEIGHT) {
        for (x in 0 until actual.width) {
            val left = actual.getRGB(x, y)
            val right = expected.getRGB(x, y)
            if (listOf(0, Byte.SIZE_BITS, Byte.SIZE_BITS * 2).any { shift ->
                    abs((left ushr shift and CHANNEL_MASK) - (right ushr shift and CHANNEL_MASK)) > CHANNEL_TOLERANCE
                }
            ) {
                mismatches++
            }
            compared++
        }
    }
    return mismatches.toDouble() / compared
}

private fun referenceScheme(dark: Boolean) =
    if (dark) darkColorScheme(background = Color(0xFF18181A)) else lightColorScheme(background = Color.White)

private val REPORTS = Path.of("build/reports/cpu-parity")
private const val FIXTURE_NAME = "simpleperf-cpu-clock.trace.gz"
private const val FIXTURE_SHA256 = "e808c0825f79fcc50ab83d5ddf68d186dfd435d0dc7a6598f5b68521602c879a"
private const val SOURCE_REVISION = "a73f07c297386c77b880c3f4b9d07571953c535e"
private const val REFERENCE_WIDTH = 1440
private const val REFERENCE_HEIGHT = 586
private const val FLAME_WITH_NAVIGATION_HEIGHT = 615
private const val UNFILTERED_SAMPLE_COUNT = 778L
private const val FILTERED_SAMPLE_COUNT = 777L
private const val REFERENCE_ROW_HEIGHT = 16
private const val ROW_FILL_Y = 14
private const val CHANNEL_MASK = 0xff
private const val CHANNEL_TOLERANCE = 8
private const val MAXIMUM_ROW_FILL_MISMATCH = 0.005
