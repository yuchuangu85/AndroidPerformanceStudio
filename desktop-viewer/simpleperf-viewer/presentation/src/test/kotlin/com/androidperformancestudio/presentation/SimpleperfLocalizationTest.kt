package com.androidperformancestudio.presentation

import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleperfLocalizationTest {
    @Test
    fun `string resources use sp module and semantic names in every locale`() {
        val localizedResourcePairs =
            listOf(
                Path.of("src/main/composeResources"),
                Path.of("../app-desktop/src/main/composeResources"),
                Path.of("../visualization/src/main/composeResources"),
            ).map { resourceRoot ->
                listOf(
                    resourceRoot.resolve("values/strings.xml"),
                    resourceRoot.resolve("values-zh/strings.xml"),
                )
            }
        val semanticName = Regex("""sp_[a-z][a-z0-9]*_[a-z][a-z0-9_]*""")
        val forbiddenModules = setOf("dynamic", "prefix", "semantic")

        localizedResourcePairs.forEach { localeFiles ->
            val namesByLocale = localeFiles.map(::stringResourceNames)
            namesByLocale.forEach { names ->
                assertEquals(names.size, names.toSet().size, "String resource names must be unique")
                names.forEach { name ->
                    assertTrue(semanticName.matches(name), "Invalid SimplePerf resource name: $name")
                    assertFalse(name.substringAfter("sp_").substringBefore('_') in forbiddenModules, name)
                    assertFalse(Regex("""sp_\d{3}_[0-9a-f]{8}""").matches(name), name)
                }
            }
            assertEquals(namesByLocale.first().toSet(), namesByLocale.last().toSet())
        }
    }

    @Test
    fun `typed resources cover primary workflow labels`() {
        assertEquals(
            "设备和目标",
            localizedStringResource(SimpleperfViewerRes.sp_target_device_target, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "继续采集",
            localizedStringResource(SimpleperfViewerRes.sp_target_continue_capture, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "性能采集目标",
            localizedStringResource(SimpleperfViewerRes.sp_target_profile_target, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "获取数据",
            localizedStringResource(SimpleperfViewerRes.sp_capture_get_data, UiLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "Back to home",
            localizedStringResource(SimpleperfViewerRes.sp_target_back_home, UiLanguage.ENGLISH),
        )
    }

    @Test
    fun `dynamic resources use structured arguments instead of parsed English text`() {
        assertEquals(
            "丢失样本：12",
            localizedStringResource(
                SimpleperfViewerRes.sp_diagnostics_lost_samples_value_format,
                UiLanguage.SIMPLIFIED_CHINESE,
                12,
            ),
        )
        assertEquals(
            "包含 12 · 独占 3",
            localizedStringResource(
                SimpleperfViewerRes.sp_report_inclusive_exclusive_summary_format,
                UiLanguage.SIMPLIFIED_CHINESE,
                12,
                3,
            ),
        )
        assertEquals(
            "样本 4 · 25.00%",
            localizedStringResource(
                SimpleperfViewerRes.sp_report_samples_summary_format,
                UiLanguage.SIMPLIFIED_CHINESE,
                4,
                "25.00%",
            ),
        )
        assertEquals(
            "• 查看数据质量",
            localizedStringResource(
                SimpleperfViewerRes.sp_common_bullet_format,
                UiLanguage.SIMPLIFIED_CHINESE,
                localizedStringResource(SimpleperfViewerRes.sp_flame_review_data_quality, UiLanguage.SIMPLIFIED_CHINESE),
            ),
        )
    }

    @Test
    fun `workspace pages do not expose language or theme controls`() {
        val homeScreen =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/HomeScreen.kt"),
            )

        assertFalse(homeScreen.contains("SimpleperfSettingsBar"))
        assertFalse(homeScreen.contains("onThemePreferenceChanged"))
        assertFalse(homeScreen.contains("onLanguagePreferenceChanged"))
    }

    @Test
    fun `flame accessibility semantics resolve typed resources`() {
        val panel =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphPanel.kt"),
            )
        val overlay =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphSemanticsOverlay.kt"),
            )

        assertTrue(panel.contains("SimpleperfViewerRes.sp_flame_flame_graph_call_stacks"))
        assertTrue(overlay.contains("SimpleperfViewerRes.sp_accessibility_select"))
        assertTrue(overlay.contains("SimpleperfViewerRes.sp_accessibility_open_details"))
        assertTrue(overlay.contains("SimpleperfViewerRes.sp_accessibility_open_context_menu"))
    }

    @Test
    fun `production localization resolves typed resources instead of English text lookups`() {
        val sourceRoot = Path.of("src/main/kotlin")
        val forbiddenPatterns =
            listOf(
                "localizedSimpleperfText(",
                "localizedSimpleperfResource(",
                "translateSimpleperfText(",
                "SimpleperfTranslationMap",
                "internal fun Text(",
            )

        Files.walk(sourceRoot).use { paths ->
            val source =
                paths
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .map(Files::readString)
                    .toList()
                    .joinToString("\n")

            forbiddenPatterns.forEach { pattern ->
                assertFalse(source.contains(pattern), "Production source must not contain $pattern")
            }
        }
    }
}

private fun stringResourceNames(path: Path): List<String> {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
    val strings = document.getElementsByTagName("string")
    return List(strings.length) { index ->
        strings
            .item(index)
            .attributes
            .getNamedItem("name")
            .nodeValue
    }
}
