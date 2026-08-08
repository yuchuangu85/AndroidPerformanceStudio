/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.ui.inspector.printer.text

import com.android.tools.ui.inspector.AppContext
import com.android.tools.ui.inspector.ColorModeHdr
import com.android.tools.ui.inspector.ColorModeWideGamut
import com.android.tools.ui.inspector.DeviceConfiguration
import com.android.tools.ui.inspector.DeviceLocale
import com.android.tools.ui.inspector.Dimension
import com.android.tools.ui.inspector.DisplayInfo
import com.android.tools.ui.inspector.GrammaticalGender
import com.android.tools.ui.inspector.HardKeyboardHidden
import com.android.tools.ui.inspector.Keyboard
import com.android.tools.ui.inspector.KeyboardHidden
import com.android.tools.ui.inspector.LayoutDirection
import com.android.tools.ui.inspector.Navigation
import com.android.tools.ui.inspector.NavigationHidden
import com.android.tools.ui.inspector.Orientation
import com.android.tools.ui.inspector.ScreenLayoutLong
import com.android.tools.ui.inspector.ScreenLayoutRound
import com.android.tools.ui.inspector.ScreenLayoutSize
import com.android.tools.ui.inspector.TouchScreen
import com.android.tools.ui.inspector.UiModeNight
import com.android.tools.ui.inspector.UiModeType
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Test

class ConfigurationPrinterTest {

  @Test
  fun testPrintDeviceConfiguration() {
    val config =
      DeviceConfiguration(
        density = Dimension.Dpi(420),
        screenWidthDp = Dimension.Dp(1080),
        screenHeightDp = Dimension.Dp(1920),
        smallestScreenWidthDp = Dimension.Dp(720),
        fontScale = 1.2f,
        orientation = Orientation.LANDSCAPE,
        screenLayoutSize = ScreenLayoutSize.LARGE,
        screenLayoutLong = ScreenLayoutLong.YES,
        layoutDirection = LayoutDirection.RTL,
        screenLayoutRound = ScreenLayoutRound.YES,
        colorModeWideGamut = ColorModeWideGamut.YES,
        colorModeHdr = ColorModeHdr.YES,
        touchScreen = TouchScreen.FINGER,
        keyboard = Keyboard.QWERTY,
        keyboardHidden = KeyboardHidden.NO,
        hardKeyboardHidden = HardKeyboardHidden.NO,
        navigation = Navigation.NONAV,
        navigationHidden = NavigationHidden.YES,
        uiModeType = UiModeType.NORMAL,
        uiModeNight = UiModeNight.YES,
        locale = DeviceLocale(language = "en", country = "US", variant = "variant", script = "Latn"),
        grammaticalGender = GrammaticalGender.FEMININE,
      )

    val output = captureOutput { printDeviceConfiguration(config, it) }

    val expectedOutput =
      """
Device Configuration:
 Color Mode Hdr: yes
 Color Mode Wide Gamut: yes
 Density: 420 dpi
 Font Scale: 1.2
 Grammatical Gender: feminine
 Hard Keyboard Hidden: no
 Keyboard: qwerty
 Keyboard Hidden: no
 Layout Direction: rtl
 Locale: en-US-variant-Latn
 Navigation: nonav
 Navigation Hidden: yes
 Orientation: landscape
 Screen Height Dp: 1920 dp
 Screen Layout Long: yes
 Screen Layout Round: yes
 Screen Layout Size: large
 Screen Width Dp: 1080 dp
 Smallest Screen Width Dp: 720 dp
 Touch Screen: finger
 Ui Mode Night: yes
 Ui Mode Type: normal
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testPrintDeviceConfiguration_minimal() {
    val config = DeviceConfiguration(density = Dimension.Dpi(160), grammaticalGender = null)

    val output = captureOutput { printDeviceConfiguration(config, it) }

    val expectedOutput =
      """
Device Configuration:
 Density: 160 dpi
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testPrintAppContext() {
    val appContext =
      AppContext(
        theme = "@style/Theme.AppCompat",
        displays = listOf(DisplayInfo(id = 0, widthPx = 1080, heightPx = 1920, orientation = 90)),
      )

    val output = captureOutput { printAppContext(appContext, it) }

    val expectedOutput =
      """
App Context:
 Theme: @style/Theme.AppCompat
 Displays:
  - Display 0: 1080x1920 px, rotation 90°
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testPrintAppContext_nullTheme() {
    val appContext = AppContext(theme = null, displays = listOf(DisplayInfo(id = 0, widthPx = 1080, heightPx = 1920, orientation = 90)))

    val output = captureOutput { printAppContext(appContext, it) }

    val expectedOutput =
      """
App Context:
 Displays:
  - Display 0: 1080x1920 px, rotation 90°
"""
        .trim()

    assertThat(output.normalizeLineEndings()).isEqualTo(expectedOutput.normalizeLineEndings())
  }

  @Test
  fun testFormatPropertyName() {
    assertThat(formatPropertyName("fontScale")).isEqualTo("Font Scale")
    assertThat(formatPropertyName("screenLayoutSize")).isEqualTo("Screen Layout Size")
    assertThat(formatPropertyName("smallestScreenWidthDp")).isEqualTo("Smallest Screen Width Dp")
    assertThat(formatPropertyName("uiModeNight")).isEqualTo("Ui Mode Night")
    assertThat(formatPropertyName("grammaticalGender")).isEqualTo("Grammatical Gender")
  }

  private fun captureOutput(action: (PrintStream) -> Unit): String {
    val outContent = ByteArrayOutputStream()
    action(PrintStream(outContent))
    return outContent.toString().trim()
  }

  private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace('\r', '\n')
}
