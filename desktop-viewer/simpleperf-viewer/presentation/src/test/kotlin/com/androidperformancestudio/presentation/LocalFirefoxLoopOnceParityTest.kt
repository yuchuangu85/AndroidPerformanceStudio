package com.androidperformancestudio.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import com.androidperformancestudio.profileanalysis.FlameGraphSnapshot
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class LocalFirefoxLoopOnceParityTest {
    @Test
    fun `native nodes match the private browser oracle for default main and all thread selections`() {
        val sessionValue = System.getenv("APS_CPU_PARITY_SESSION")
        val oracleValue = System.getenv("APS_CPU_PARITY_EXPECTATIONS")
        assumeTrue(sessionValue != null && oracleValue != null, "Set the authorized local profile and browser oracle")
        val session = Path.of(requireNotNull(sessionValue))
        val oraclePath = Path.of(requireNotNull(oracleValue))
        val expected = Properties().apply { Files.newInputStream(oraclePath).use(::load) }
        assertEquals(expected.getProperty("input.sha256"), sha256(session.resolve("perf_data.json.gz")))
        val databaseHash = sha256(session.resolve("profile.sqlite"))
        val observed = Properties()
        runBlocking {
            ReportController(selectInitialThread = true, matchFirefoxSampleWeights = true).use { controller ->
                controller.openSession(session)
                expected.getProperty("cases").split(',').forEach { case ->
                    val prefix = "case.$case."
                    val threadIds =
                        expected
                            .getProperty(prefix + "threadIds")
                            .split(',')
                            .map(String::toInt)
                            .toSet()
                    if (case != "default") controller.updateThreads(threadIds)
                    val state = controller.state.value
                    val report = (state.loadState as ReportLoadState.Ready).report
                    assertEquals(threadIds, state.filter.threadIds, case)
                    assertEquals(expected.long(prefix + "totalSamples"), report.overview.sampleCount, case)
                    val snapshot = report.flameGraph
                    assertEquals(CallStackWeightType.SAMPLES, snapshot.weightType)
                    assertEquals(expected.long(prefix + "totalWeight"), snapshot.totalWeight, case)
                    compareNodes(expected, case, snapshot, observed)
                }
            }
        }
        assertEquals(databaseHash, sha256(session.resolve("profile.sqlite")), "Source facts must remain unchanged")
        assertEquals(expected.getProperty("input.sha256"), sha256(session.resolve("perf_data.json.gz")))
        val output = Path.of("build/reports/local-firefox-parity/native-results.properties")
        Files.createDirectories(output.parent)
        Files.newOutputStream(output).use { observed.store(it, "Private same-profile native parity observations") }
    }

    private fun compareNodes(
        expected: Properties,
        case: String,
        snapshot: FlameGraphSnapshot,
        observed: Properties,
    ) {
        val prefix = "case.$case."
        val table = snapshot.callNodes
        val actualNodes =
            (0 until table.size).filter {
                table.frameAt(it)?.symbolName == "android.os.Looper.loopOnce" && (table.sampleCountAt(it) ?: 0) > 0
            }
        assertEquals(expected.getProperty(prefix + "node.count").toInt(), actualNodes.size, case)
        repeat(actualNodes.size) { node ->
            val key = prefix + "node.$node."
            val ancestry =
                String(
                    Base64.getDecoder().decode(expected.getProperty(key + "ancestryBase64")),
                    Charsets.UTF_8,
                ).split('\u001f')
            val index = actualNodes.single { table.ancestry(it) == ancestry }
            val id = assertNotNull(table.nodeIdAt(index))
            val facts = assertNotNull(snapshot.tooltipFacts(id))
            assertEquals(expected.long(key + "samples"), facts.sampleCount, key)
            assertEquals(expected.long(key + "selfSamples"), facts.selfSampleCount, key)
            assertEquals(expected.long(key + "weight"), facts.inclusiveWeight, key)
            assertEquals(expected.long(key + "selfWeight"), facts.selfWeight, key)
            val width = snapshot.rows.endAt(index)!! - snapshot.rows.startAt(index)!!
            assertEquals(expected.getProperty(key + "widthFraction").toDouble(), width, 0.000001, key)
            assertEquals(expected.getProperty(key + "tooltip.firstLine"), firefoxTooltipHeader(facts), key)
            compareCategories(expected, key, facts)
            observed[key + "header"] = firefoxTooltipHeader(facts)
            observed[key + "samples"] = facts.sampleCount.toString()
            observed[key + "widthFraction"] = width.toString()
            captureTooltip(case, node, facts)
        }
    }

    private fun compareCategories(
        expected: Properties,
        key: String,
        facts: FlameGraphTooltipFacts,
    ) {
        val categories =
            (0 until expected.getProperty(key + "tooltip.category.count").toInt()).associate { index ->
                val prefix = key + "tooltip.category.$index."
                expected.getProperty(prefix + "name") to (
                    expected.long(prefix + "runningSamples") to
                        expected.long(prefix + "selfSamples")
                )
            }
        assertEquals(categories, facts.categorySamples.associate { it.category to (it.running to it.self) }, key)
    }

    private fun captureTooltip(
        case: String,
        node: Int,
        facts: FlameGraphTooltipFacts,
    ) = runDesktopComposeUiTest(width = 620, height = 480) {
        setContent {
            ViewerTheme(darkTheme = false) {
                SimpleperfLocalization(UiLanguage.ENGLISH) {
                    FirefoxFlameGraphTooltip(facts, rememberFirefoxFlameGraphStyle())
                }
            }
        }
        val output = Path.of("build/reports/local-firefox-parity/native-$case-$node-tooltip.png")
        Files.createDirectories(output.parent)
        ImageIO.write(onNodeWithTag("firefox-flame-tooltip").captureToImage().toBufferedImage(), "png", output.toFile())
    }
}

private fun CallNodeTable.ancestry(index: Int): List<String> {
    val names = ArrayList<String>()
    var current: Int? = index
    while (current != null && current >= 0) {
        names.add(frameAt(current)!!.symbolName)
        current = parentIndexAt(current)
    }
    return names.asReversed()
}

private fun Properties.long(key: String): Long = getProperty(key).toLong()

private fun sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
