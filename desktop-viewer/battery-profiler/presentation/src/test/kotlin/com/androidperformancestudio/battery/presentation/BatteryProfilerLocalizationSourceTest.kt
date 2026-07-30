package com.androidperformancestudio.battery.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryProfilerLocalizationSourceTest {
    @Test
    fun `empty pane uses the selected application language`() {
        val source =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/battery/presentation/BatteryProfilerScreen.kt"),
            )
        val emptyPane =
            source.substring(
                source.indexOf("private fun EmptyPane("),
                source.indexOf("private fun ResultsPane("),
            )
        val analysisDescription =
            "analyze_wakelocks_alarms_jobs_" +
                "network_sensors_and_system_energy_estima"

        assertTrue(
            Regex(
                """localizedStringResource\(\s*Res\.string\.battery_energy_profiler,\s*language,?\s*\)""",
            ).containsMatchIn(emptyPane),
        )
        assertTrue(emptyPane.contains("Res.string.$analysisDescription"))
        assertTrue(emptyPane.substringAfter("Res.string.$analysisDescription").contains("language"))
        assertFalse(emptyPane.contains("stringResource("))
    }

    @Test
    fun `chinese resources translate the empty pane title and description`() {
        val resources =
            Files.readString(
                Path.of("src/main/composeResources/values-zh/strings.xml"),
            )

        assertTrue(resources.contains("""<string name="battery_energy_profiler">电池 / 能耗分析器</string>"""))
        assertTrue(resources.contains("通过前后快照差分分析"))
    }
}
