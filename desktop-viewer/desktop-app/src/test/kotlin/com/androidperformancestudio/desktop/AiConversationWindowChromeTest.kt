package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.ui.ViewerTheme
import com.androidperformancestudio.ui.viewerColors
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalTestApi::class)
class AiConversationWindowChromeTest {
    @Test
    fun `AI window reuses native macOS properties and live theme colors without scaling its title bar`() {
        assumeTrue(MacOsWindowChrome.embedsTitleBar())
        assumeFalse(GraphicsEnvironment.isHeadless())
        runDesktopComposeUiTest(width = 1000, height = 720) {
            val frame = runOnUiThread { ComposeWindow() }
            val scope = object : FrameWindowScope {
                override val window = frame
            }
            val dark = mutableStateOf(false)
            val title = mutableStateOf("AI Chat")
            try {
                setContent {
                    val colors = viewerColors(darkTheme = dark.value)
                    Column(Modifier.fillMaxSize()) {
                        scope.AiConversationWindowChrome(title.value, colors, dark.value)
                        ViewerTheme(darkTheme = dark.value, displayScale = 1.5f) {
                            Box(Modifier.fillMaxSize().background(colors.workspace).testTag("ai-window-body"))
                        }
                    }
                }
                listOf(false, true).forEach { darkTheme ->
                    listOf("AI Chat", "AI \u5bf9\u8bdd").forEachIndexed { index, label ->
                        runOnIdle {
                            dark.value = darkTheme
                            title.value = label
                        }
                        waitForIdle()
                        onNodeWithText(label).assertIsDisplayed()
                        val body = onNodeWithTag("ai-window-body").fetchSemanticsNode().boundsInRoot
                        assertEquals(MacOsWindowChrome.titleBarHeight.value, body.top, 1f)
                        runOnUiThread {
                            assertEquals(true, frame.rootPane.getClientProperty(MacOsWindowChrome.FULL_WINDOW_CONTENT))
                            assertEquals(true, frame.rootPane.getClientProperty(MacOsWindowChrome.TRANSPARENT_TITLE_BAR))
                            assertEquals(false, frame.rootPane.getClientProperty(MacOsWindowChrome.WINDOW_TITLE_VISIBLE))
                            assertFalse(frame.isUndecorated)
                        }
                        val colors = viewerColors(darkTheme = darkTheme)
                        val pixels = onRoot().captureToImage().toPixelMap()
                        val bottom = MacOsWindowChrome.titleBarHeight.value.toInt() - 1
                        assertEquals(colors.toolbar.toArgb(), pixels[100, 2].toArgb())
                        assertEquals(colors.border.toArgb(), pixels[100, bottom].toArgb())
                        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                        for (y in 0 until pixels.height) {
                            for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                        }
                        val output = Path.of("build/reports/ai-window-chrome/dark-$darkTheme-language-$index.png")
                        Files.createDirectories(output.parent)
                        ImageIO.write(image, "png", output.toFile())
                    }
                }
            } finally {
                runOnUiThread { frame.dispose() }
            }
        }
    }

    @Test
    fun `desktop host supplies shared chrome before the AI viewer content without a separate settings action`() {
        val host = Files.readString(
            Path.of("../ai-platform/ai-platform-presentation/src/main/kotlin/com/androidperformancestudio/aiplatform/presentation/AiConversationWindowHost.kt"),
        )
        val desktop = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt"))
        val chrome = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/AiConversationWindowChrome.kt"))
        assertTrue(host.contains("windowChrome: @Composable FrameWindowScope.(title: String) -> Unit"))
        assertTrue(host.contains("windowChrome(title)"))
        assertTrue(host.indexOf("windowChrome(title)") < host.indexOf("ViewerTheme(darkTheme = darkTheme)"))
        assertTrue(host.contains("Box(Modifier.weight(1f))"))
        assertFalse(host.contains("TextButton(onClick = onOpenSettings)"))
        assertTrue(desktop.contains("windowChrome = { title -> AiConversationWindowChrome(title, windowColors, darkTheme) }"))
        assertTrue(chrome.contains("SideEffect { MacOsWindowChrome.install(window) }"))
        assertTrue(chrome.contains("WindowsWindowChromeEffect(colors, darkTheme)"))
        assertTrue(chrome.contains("background = colors.toolbar"))
        assertTrue(chrome.contains("contentColor = colors.primaryText"))
        assertTrue(chrome.contains("dividerColor = colors.border"))
    }
}
