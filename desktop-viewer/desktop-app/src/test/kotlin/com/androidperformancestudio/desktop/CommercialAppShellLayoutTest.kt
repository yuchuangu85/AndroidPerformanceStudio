package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.androidperformancestudio.desktop.shell.CommercialAppShell
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.studio.StudioTokens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class CommercialAppShellLayoutTest {
    @Test
    fun `AI rail entry remains accessible and opens AI without navigating in both rail modes and themes`() {
        listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
            listOf(false, true).forEach { dark ->
                listOf(false, true).forEach { collapsed ->
                    runDesktopComposeUiTest(width = 1280, height = 720) {
                        val navigator = AppNavigator()
                        val initialDestination = navigator.destination
                        var opened = 0
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                CommercialAppShell(
                                    navigator = navigator,
                                    language = language,
                                    navigationRailCollapsed = collapsed,
                                    onOpenAi = { opened++ },
                                ) { Box(Modifier.fillMaxSize()) }
                            }
                        }

                        val label = if (language == UiLanguage.SIMPLIFIED_CHINESE) "AI 分析" else "AI Analysis"
                        onNodeWithContentDescription(label).assertIsDisplayed().performClick()
                        runOnIdle {
                            assertEquals(1, opened)
                            assertEquals(initialDestination, navigator.destination)
                        }
                        if (language == UiLanguage.ENGLISH) {
                            val pixels = onNodeWithTag("studio-navigation-rail").captureToImage().toPixelMap()
                            val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                            for (y in 0 until pixels.height) {
                                for (x in 0 until pixels.width) screenshot.setRGB(x, y, pixels[x, y].toArgb())
                            }
                            val output = Path.of("build/reports/ai-entry-icons/rail-dark-$dark-collapsed-$collapsed.png")
                            Files.createDirectories(output.parent)
                            ImageIO.write(screenshot, "png", output.toFile())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `rail and destination content fill the root without a global footer gutter`() {
        listOf(900, 1280, 1440).forEach { width ->
            listOf(UiLanguage.ENGLISH, UiLanguage.SIMPLIFIED_CHINESE).forEach { language ->
                listOf(false, true).forEach { dark ->
                    listOf(false, true).forEach { collapsed ->
                        val scenario = "width=$width language=$language dark=$dark collapsed=$collapsed"
                        runDesktopComposeUiTest(width = width, height = 720) {
                            val navigator = AppNavigator()
                            setContent {
                                ViewerTheme(darkTheme = dark) {
                                    CommercialAppShell(
                                        navigator = navigator,
                                        language = language,
                                        navigationRailCollapsed = collapsed,
                                        modifier = Modifier.testTag("commercial-shell"),
                                    ) {
                                        Box(Modifier.fillMaxSize().testTag("shell-destination-content"))
                                    }
                                }
                            }

                            assertFillLayout(collapsed, scenario)
                        }
                    }
                }
            }
        }
    }

    private fun DesktopComposeUiTest.assertFillLayout(
        collapsed: Boolean,
        scenario: String,
    ) {
        val root = onRoot().getUnclippedBoundsInRoot()
        val shell = onNodeWithTag("commercial-shell").getUnclippedBoundsInRoot()
        val rail = onNodeWithTag("studio-navigation-rail").getUnclippedBoundsInRoot()
        val content = onNodeWithTag("shell-destination-content").getUnclippedBoundsInRoot()
        val expectedRailWidth =
            if (collapsed) StudioTokens.navigationCollapsedWidth else StudioTokens.navigationExpandedWidth

        assertEquals(root, shell, scenario)
        assertEquals(root.top, rail.top, scenario)
        assertEquals(root.bottom, rail.bottom, "$scenario: rail must not reserve the old 28dp footer")
        assertEquals(root.left, rail.left, scenario)
        assertEquals(expectedRailWidth, rail.right - rail.left, scenario)
        assertEquals(root.top, content.top, scenario)
        assertEquals(root.bottom, content.bottom, "$scenario: content must not reserve the old 28dp footer")
        assertEquals(rail.right, content.left, scenario)
        assertEquals(root.right, content.right, scenario)
        onNodeWithTag("studio-navigation-rail").assertIsDisplayed()
        onNodeWithTag("shell-destination-content").assertIsDisplayed()
    }
}
