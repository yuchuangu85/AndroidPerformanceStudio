package com.androidperformancestudio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ViewerTypographyTest {
    @Test
    fun `every public semantic role uses one of three shared font sizes`() {
        // Inspect every style getter so a newly added role cannot silently add a fourth size.
        val styles =
            ViewerTypography::class.java.methods
                .filter { it.returnType == TextStyle::class.java && it.parameterCount == 0 }
                .associate { it.name to (it.invoke(ViewerTypography) as TextStyle) }

        assertTrue(styles.isNotEmpty())
        assertEquals(setOf(11.sp, 13.sp, 16.sp), styles.values.map { it.fontSize }.toSet())
        styles.forEach { (name, style) ->
            assertTrue(style.lineHeight >= style.fontSize, "$name must not clip its font")
        }
    }

    @Test
    fun `positions select heading body and supporting sizes consistently`() {
        listOf(
            ViewerTypography.homeHero,
            ViewerTypography.pageTitle,
            ViewerTypography.workspaceTitle,
            ViewerTypography.sectionTitle,
            ViewerTypography.cardTitle,
            ViewerTypography.metric,
            ViewerTypography.icon,
        ).forEach { assertEquals(16.sp, it.fontSize) }
        listOf(
            ViewerTypography.subsectionTitle,
            ViewerTypography.lead,
            ViewerTypography.body,
            ViewerTypography.bodyMedium,
            ViewerTypography.bodyCompact,
        ).forEach { assertEquals(13.sp, it.fontSize) }
        listOf(
            ViewerTypography.workspaceSubtitle,
            ViewerTypography.secondary,
            ViewerTypography.label,
            ViewerTypography.dense,
            ViewerTypography.micro,
        ).forEach { assertEquals(11.sp, it.fontSize) }
    }

    @Test
    fun `all Material roles including default displays stay inside the shared hierarchy`() =
        runDesktopComposeUiTest {
            val dark = mutableStateOf(false)
            lateinit var typography: Typography
            setContent {
                ViewerTheme(darkTheme = dark.value) {
                    typography = MaterialTheme.typography
                    Text("Theme content")
                }
            }

            onNodeWithText("Theme content").assertExists()
            assertMaterialSizes(typography)
            dark.value = true
            waitForIdle()
            assertMaterialSizes(typography)
        }

    @Test
    fun `plain text and restored popup context use the same body size`() =
        runDesktopComposeUiTest {
            var context: ViewerThemeContext? = null
            val restored = mutableStateOf(false)
            var renderedStyle: TextStyle? = null
            setContent {
                if (restored.value) {
                    ProvideViewerThemeContext(requireNotNull(context)) {
                        Text("Restored", onTextLayout = { renderedStyle = it.layoutInput.style })
                    }
                } else {
                    ViewerTheme(darkTheme = true) {
                        context = rememberViewerThemeContext()
                        Text("Original", onTextLayout = { renderedStyle = it.layoutInput.style })
                    }
                }
            }

            onNodeWithText("Original").assertExists()
            assertEquals(13.sp, requireNotNull(renderedStyle).fontSize)
            restored.value = true
            waitForIdle()
            onNodeWithText("Restored").assertExists()
            assertEquals(13.sp, requireNotNull(renderedStyle).fontSize)
            assertMaterialSizes(requireNotNull(context).typography)
        }

    @Test
    fun `compact English and Chinese text fits existing table and toolbar line heights`() =
        runDesktopComposeUiTest(width = 800, height = 400) {
            val heights = mutableListOf<Pair<TextStyle, Int>>()
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        val styles =
                            listOf(ViewerTypography.bodyCompact, ViewerTypography.label, ViewerTypography.dense)
                        styles.forEach { style ->
                            Text(
                                "属性值 Evidence 123",
                                style = style,
                                onTextLayout = {
                                    assertTrue(!it.hasVisualOverflow)
                                    heights += style to it.size.height
                                },
                            )
                        }
                    }
                }
            }

            waitForIdle()
            assertTrue(heights.isNotEmpty())
            heights.forEach { (style, height) ->
                assertTrue(height <= style.lineHeight.value, "$style exceeds its compact line height: $height")
            }
        }

    @Test
    fun `canvas labels and browser fallback derive their size from shared text roles`() {
        assertEquals(ViewerTypography.secondary.fontSize.value, ViewerTypography.flameGraphLabelPixels)
        assertEquals("${ViewerTypography.pageTitle.fontSize.value.toInt()}px", ViewerDocumentTheme.titleFontSize)
        assertEquals("${ViewerTypography.body.fontSize.value.toInt()}px", ViewerDocumentTheme.bodyFontSize)
        assertEquals("${ViewerTypography.secondary.fontSize.value.toInt()}px", ViewerDocumentTheme.supportingFontSize)
        assertEquals(ViewerDocumentTheme.titleFontSize, ViewerDocumentTheme.errorFontSize)
    }

    private fun assertMaterialSizes(typography: Typography) {
        listOf(
            typography.displayLarge,
            typography.displayMedium,
            typography.displaySmall,
            typography.headlineLarge,
            typography.headlineMedium,
            typography.headlineSmall,
            typography.titleLarge,
        ).forEach { assertEquals(16.sp, it.fontSize) }
        listOf(
            typography.titleMedium,
            typography.titleSmall,
            typography.bodyLarge,
            typography.bodyMedium,
            typography.labelLarge,
        ).forEach { assertEquals(13.sp, it.fontSize) }
        listOf(typography.bodySmall, typography.labelMedium, typography.labelSmall).forEach {
            assertEquals(11.sp, it.fontSize)
        }
    }
}
