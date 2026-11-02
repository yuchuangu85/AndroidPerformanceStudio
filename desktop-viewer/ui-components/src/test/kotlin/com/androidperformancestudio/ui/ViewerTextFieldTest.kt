package com.androidperformancestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.ui.studio.StudioSearchField
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ViewerTextFieldTest {
    @Test
    fun `ordinary input areas have the same compact height regardless of label value or focus`() =
        runDesktopComposeUiTest {
            var value by mutableStateOf("")
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column(Modifier.width(320.dp)) {
                        ViewerTextField(
                            value,
                            { value = it },
                            Modifier.testTag("editable"),
                            label = { Text("路径 Path") },
                        )
                        ViewerTextField("/Users/sample/AndroidProject", {}, label = { Text("Project") })
                        ViewerTextField("", {}, placeholder = { Text("Search") })
                        StudioSearchField("", {}, "Search", compact = false)
                    }
                }
            }
            repeat(4) {
                onAllNodesWithTag("viewer-text-field-input", useUnmergedTree = true)[it]
                    .assertHeightIsEqualTo(32.dp)
                    .assertWidthIsEqualTo(320.dp)
                onAllNodesWithTag("viewer-text-field-outline", useUnmergedTree = true)[it]
                    .assertHeightIsEqualTo(32.dp)
                    .assertWidthIsEqualTo(320.dp)
            }
            onNodeWithTag("editable").performClick().assertIsFocused().performTextInput("路径")
            assertEquals("路径", value)
            repeat(4) {
                onAllNodesWithTag("viewer-text-field-input", useUnmergedTree = true)[it].assertHeightIsEqualTo(32.dp)
            }
        }

    @Test
    fun `controlled editing and IME actions are not changed by compact decoration`() =
        runDesktopComposeUiTest {
            var value by mutableStateOf("12")
            var done = 0
            setContent {
                ViewerTheme(darkTheme = true) {
                    ViewerTextField(
                        value,
                        { if (it.all(Char::isDigit)) value = it },
                        Modifier.testTag("number"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { done++ }),
                    )
                }
            }
            onNodeWithTag("number").performTextReplacement("1234")
            assertEquals("1234", value)
            onNodeWithTag("number").performTextReplacement("invalid")
            assertEquals("1234", value)
            onNodeWithTag("number").performImeAction()
            assertEquals(1, done)
        }

    @Test
    fun `password masking read only and disabled behavior are retained`() =
        runDesktopComposeUiTest {
            var password by mutableStateOf("secret")
            var readOnlyChanges = 0
            setContent {
                ViewerTheme(darkTheme = false) {
                    Column {
                        ViewerTextField(
                            password,
                            { password = it },
                            Modifier.testTag("password"),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        ViewerTextField(
                            "Read only",
                            { readOnlyChanges++ },
                            Modifier.testTag("readonly"),
                            readOnly = true,
                        )
                        ViewerTextField("Disabled", {}, Modifier.testTag("disabled"), enabled = false)
                    }
                }
            }
            val field = onNodeWithTag("password")
            assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
            field.assertTextEquals("••••••").performTextReplacement("secret7")
            assertEquals("secret7", password)
            onNodeWithTag("readonly").performClick().assertIsFocused()
            assertFalse(onNodeWithTag("readonly").fetchSemanticsNode().config.contains(SemanticsActions.SetText))
            assertEquals(0, readOnlyChanges)
            onNodeWithTag("disabled").assertIsNotEnabled()
        }

    @Test
    fun `supporting messages wrap outside the input and do not change its height`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = false) {
                    ViewerTextField(
                        "",
                        {},
                        Modifier.width(220.dp).testTag("error-field"),
                        isError = true,
                        label = { Text("令牌 Token") },
                        supportingText = {
                            Text("请输入有效的凭证引用。Enter a valid credential reference before continuing.")
                        },
                    )
                }
            }
            onNodeWithTag("viewer-text-field-input", useUnmergedTree = true).assertHeightIsEqualTo(32.dp)
            assertTrue(onNodeWithTag("error-field").fetchSemanticsNode().config.contains(SemanticsProperties.Error))
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText("请输入有效", substring = true, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.didOverflowWidth)
            // Mixed CJK metrics can round the paragraph one pixel above its integer layout height.
            assertTrue(layout.multiParagraph.height <= layout.size.height + 1f)
            assertTrue(layout.getBoundingBox(layout.layoutInput.text.lastIndex).bottom <= layout.size.height + 1f)
            assertTrue(layout.lineCount > 1)
        }

    @Test
    fun `larger font scale grows the input instead of clipping the line`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = false) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                        ViewerTextField(
                            "路径 Path",
                            {},
                            Modifier.width(300.dp).testTag("scaled"),
                            label = { Text("项目路径 Project path") },
                        )
                    }
                }
            }
            val field = onNodeWithTag("scaled")
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.hasVisualOverflow)
            val bounds =
                onNodeWithTag("viewer-text-field-input", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height > 32f)
            assertTrue(layout.size.height < bounds.height)
        }

    @Test
    fun `explicit multiline inputs are not squeezed into a single line`() =
        runDesktopComposeUiTest {
            setContent {
                ViewerTheme(darkTheme = false) {
                    ViewerTextField(
                        "First\nSecond\nThird",
                        {},
                        Modifier.width(300.dp).testTag("multiline"),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5,
                    )
                }
            }
            val bounds =
                onNodeWithTag("viewer-text-field-input", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height > 32f)
            onNodeWithTag("multiline").assertTextEquals("First\nSecond\nThird")
        }

    @Test
    fun `light dark English and Chinese forms remain compact at narrow and wide sizes`() {
        for (width in listOf(900, 1280)) {
            for (dark in listOf(false, true)) {
                for (chinese in listOf(false, true)) {
                    runDesktopComposeUiTest(width = width, height = 720) {
                        setContent {
                            ViewerTheme(darkTheme = dark) {
                                InputGallery(chinese)
                            }
                        }
                        repeat(6) {
                            onAllNodesWithTag(
                                "viewer-text-field-input",
                                useUnmergedTree = true,
                            )[it].assertHeightIsEqualTo(ViewerDimensions.textFieldHeight)
                        }
                        val theme = if (dark) "dark" else "light"
                        val language = if (chinese) "zh" else "en"
                        saveScreenshot(onNodeWithTag("input-gallery").captureToImage(), "$width-$theme-$language")
                    }
                }
            }
        }
    }

    @Composable
    @Suppress("FunctionNaming", "ktlint:standard:function-naming")
    private fun InputGallery(chinese: Boolean) {
        Column(
            Modifier.fillMaxWidth().background(LocalViewerColors.current.workspace)
                .padding(16.dp).testTag("input-gallery"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val label = if (chinese) "项目路径" else "Project path"
            val values = listOf("", "/Users/sample/Workspace/AndroidProject", "LongProjectPath/".repeat(18))
            values.forEach { value ->
                ViewerTextField(
                    value, {}, Modifier.fillMaxWidth(),
                    label = { Text(label) }, placeholder = { Text(label) },
                )
            }
            ViewerTextField(
                "", {}, Modifier.fillMaxWidth(),
                label = { Text(if (chinese) "凭证" else "Credential") }, isError = true,
                supportingText = {
                    Text(if (chinese) "请输入有效的凭证引用" else "Enter a valid credential reference")
                },
            )
            ViewerTextField("Disabled", {}, Modifier.fillMaxWidth(), enabled = false)
            StudioSearchField("", {}, if (chinese) "搜索" else "Search", Modifier.fillMaxWidth())
        }
    }

    private fun saveScreenshot(bitmap: ImageBitmap, name: String) {
        val pixels = bitmap.toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        }
        val output = Path.of("build/reports/inputs/$name.png")
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(image, "png", output.toFile()))
    }
}
