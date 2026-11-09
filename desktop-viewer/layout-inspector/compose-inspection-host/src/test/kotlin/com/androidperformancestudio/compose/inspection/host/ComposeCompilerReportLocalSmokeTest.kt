package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposeCompilerStability
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

@EnabledIfEnvironmentVariable(named = "APS_COMPOSE_REPORT_TEST_DIR", matches = ".+")
class ComposeCompilerReportLocalSmokeTest {
    @Test
    fun `real AndroidPerformanceDemo reports import by directory and by each file with parameter detail`() {
        val directory = Path.of(System.getenv("APS_COMPOSE_REPORT_TEST_DIR"))
        val parser = ComposeCompilerReportParser()
        val report = parser.parse(directory)
        assertEquals(3, report.files.size)
        assertEquals(42, report.functions.size)
        val screen = report.functions.single { it.name == "AnrDemoScreen" }
        assertEquals("com.codemx.anrdemo.ui", screen.packageName)
        assertTrue(screen.skippable)
        assertEquals(ComposeCompilerStability.UNSTABLE, screen.parameters.first().stability)
        assertEquals("AnrScenarioDispatcher", screen.parameters.first().type)
        assertFalse(report.classes.isEmpty())
        report.files.forEach { file ->
            val selected = parser.parse(Path.of(file))
            assertEquals(report.functions, selected.functions)
            assertEquals(report.classes, selected.classes)
        }
        println("Real Compose report import: ${report.files.size} files, ${report.functions.size} functions, ${report.classes.size} classes")
    }
}
