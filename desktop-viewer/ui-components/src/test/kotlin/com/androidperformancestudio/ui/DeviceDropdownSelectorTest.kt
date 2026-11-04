package com.androidperformancestudio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeviceDropdownSelectorTest {
    @Test
    fun `device placeholder is localized and selection preserves device labels`() {
        UiLanguage.entries.forEach { language ->
            listOf(false, true).forEach { dark ->
                runDesktopComposeUiTest {
                    var selected by mutableStateOf<String?>(null)
                    setContent {
                        ViewerTheme(darkTheme = dark) {
                            DeviceDropdownSelector(
                                items = listOf("Pixel 8 (serial-1)", "Pixel 9 (serial-2)"),
                                selectedItem = selected,
                                onItemSelected = { selected = it },
                                itemLabel = { it },
                                language = language,
                            )
                        }
                    }
                    val label = if (language == UiLanguage.ENGLISH) "Device" else "设备"
                    val description = if (language == UiLanguage.ENGLISH) "Device selector" else "设备选择器"
                    val screenshotName = "${language.name}-${if (dark) "dark" else "light"}"
                    onNodeWithText(label).assertExists()
                    saveScreenshot(onNodeWithContentDescription(description).captureToImage(), "$screenshotName-empty")
                    onNodeWithContentDescription(description).performClick()
                    onNodeWithText("Pixel 9 (serial-2)").performClick()
                    assertEquals("Pixel 9 (serial-2)", selected)
                    onNodeWithText("Pixel 9 (serial-2)").assertExists()
                    onNodeWithText(label).assertDoesNotExist()
                    saveScreenshot(
                        onNodeWithContentDescription(description).captureToImage(),
                        "$screenshotName-selected",
                    )
                }
            }
        }
    }

    @Test
    fun `automatic selection remains an explicit menu action but uses the same device placeholder`() {
        UiLanguage.entries.forEach { language ->
            runDesktopComposeUiTest {
                var selected by mutableStateOf<String?>("Pixel 8")
                setContent {
                    ViewerTheme(darkTheme = false) {
                        DeviceDropdownSelector(
                            items = listOf("Pixel 8"),
                            selectedItem = selected,
                            onItemSelected = { selected = it },
                            itemLabel = { it },
                            language = language,
                            onAutomaticSelection = { selected = null },
                        )
                    }
                }
                val label = if (language == UiLanguage.ENGLISH) "Device" else "设备"
                val description = if (language == UiLanguage.ENGLISH) "Device selector" else "设备选择器"
                val automatic = if (language == UiLanguage.ENGLISH) "Auto-select device" else "自动选择设备"
                onNodeWithContentDescription(description).performClick()
                onNodeWithText(automatic).performClick()
                assertEquals(null, selected)
                onNodeWithText(label).assertExists()
                onNodeWithText(automatic).assertDoesNotExist()
                onNodeWithContentDescription(description).performClick()
                onNode(hasText(automatic) and isSelected()).assertIsSelected()
                onNodeWithText("Pixel 8").performClick()
                assertEquals("Pixel 8", selected)
            }
        }
    }

    @Test
    fun `offline devices and capture locked selectors remain disabled`() =
        runDesktopComposeUiTest {
            var selected by mutableStateOf<String?>(null)
            var locked by mutableStateOf(false)
            setContent {
                ViewerTheme(darkTheme = false) {
                    DeviceDropdownSelector(
                        items = listOf("Online device", "Offline device"),
                        selectedItem = selected,
                        onItemSelected = { selected = it },
                        itemLabel = { it },
                        language = UiLanguage.ENGLISH,
                        itemEnabled = { it != "Offline device" },
                        enabled = !locked,
                    )
                }
            }
            onNodeWithContentDescription("Device selector").performClick()
            onNodeWithText("Offline device").assertIsNotEnabled().performClick()
            assertEquals(null, selected)
            onNodeWithText("Online device").performClick()
            assertEquals("Online device", selected)
            locked = true
            onNodeWithContentDescription("Device selector").assertIsNotEnabled()
        }

    @Test
    fun `empty devices are disabled without automatic selection`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = true) {
                    DeviceDropdownSelector(
                        items = emptyList<String>(),
                        selectedItem = null,
                        onItemSelected = {},
                        itemLabel = { it },
                        language = UiLanguage.SIMPLIFIED_CHINESE,
                    )
                }
            }
            onNodeWithText("设备").assertExists()
            onNodeWithContentDescription("设备选择器").assertIsNotEnabled()
        }

    private fun saveScreenshot(image: ImageBitmap, name: String) {
        val pixels = image.toPixelMap()
        val screenshot = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                screenshot.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        val output = Path.of("build/reports/device-selector/$name.png")
        Files.createDirectories(output.parent)
        ImageIO.write(screenshot, "png", output.toFile())
    }
}
