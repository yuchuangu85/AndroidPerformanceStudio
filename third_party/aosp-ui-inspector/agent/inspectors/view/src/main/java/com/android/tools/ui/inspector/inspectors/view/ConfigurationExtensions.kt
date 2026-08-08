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

package com.android.tools.ui.inspector.inspectors.view

import android.content.res.Configuration as AndroidResConfiguration
import android.os.Build
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ColorModeHdr
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ColorModeWideGamut
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Configuration
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.GrammaticalGender
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.HardKeyboardHidden
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Keyboard
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.KeyboardHidden
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.LayoutDirection
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Locale
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Navigation
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.NavigationHidden
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Orientation
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ScreenLayoutLong
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ScreenLayoutRound
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ScreenLayoutSize
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.TouchScreen
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.UiModeNight
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.UiModeType
import java.util.Locale as JavaUtilLocale

internal fun AndroidResConfiguration.convert(stringTable: StringTable): Configuration =
  Configuration.newBuilder().populateFrom(this, stringTable).build()

private fun Configuration.Builder.populateFrom(config: AndroidResConfiguration, stringTable: StringTable): Configuration.Builder {
  fontScale = config.fontScale
  countryCode = config.mcc
  networkCode = config.mnc
  smallestScreenWidthDp = config.smallestScreenWidthDp
  density = config.densityDpi
  screenWidthDp = config.screenWidthDp
  screenHeightDp = config.screenHeightDp

  if (Build.VERSION.SDK_INT >= 24) {
    if (!config.locales.isEmpty) {
      locale = config.locales[0].convert(stringTable)
    }
  } else {
    @Suppress("DEPRECATION")
    locale = config.locale.convert(stringTable)
  }

  populateScreenLayout(config.screenLayout)
  if (Build.VERSION.SDK_INT >= 26) {
    populateColorMode(config.colorMode)
  }

  // populate touchScreen
  touchScreen =
    when (config.touchscreen) {
      AndroidResConfiguration.TOUCHSCREEN_NOTOUCH -> TouchScreen.TOUCH_SCREEN_NOTOUCH
      AndroidResConfiguration.TOUCHSCREEN_STYLUS -> TouchScreen.TOUCH_SCREEN_STYLUS
      AndroidResConfiguration.TOUCHSCREEN_FINGER -> TouchScreen.TOUCH_SCREEN_FINGER
      else -> TouchScreen.TOUCH_SCREEN_UNDEFINED
    }

  // populate keyboard
  keyboard =
    when (config.keyboard) {
      AndroidResConfiguration.KEYBOARD_NOKEYS -> Keyboard.KEYBOARD_NOKEYS
      AndroidResConfiguration.KEYBOARD_QWERTY -> Keyboard.KEYBOARD_QWERTY
      AndroidResConfiguration.KEYBOARD_12KEY -> Keyboard.KEYBOARD_12KEY
      else -> Keyboard.KEYBOARD_UNDEFINED
    }

  // populate keyboardHidden
  keyboardHidden =
    when (config.keyboardHidden) {
      AndroidResConfiguration.KEYBOARDHIDDEN_NO -> KeyboardHidden.KEYBOARD_HIDDEN_NO
      AndroidResConfiguration.KEYBOARDHIDDEN_YES -> KeyboardHidden.KEYBOARD_HIDDEN_YES
      else -> KeyboardHidden.KEYBOARD_HIDDEN_UNDEFINED
    }

  // populate hardKeyboardHidden
  hardKeyboardHidden =
    when (config.hardKeyboardHidden) {
      AndroidResConfiguration.HARDKEYBOARDHIDDEN_NO -> HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_NO
      AndroidResConfiguration.HARDKEYBOARDHIDDEN_YES -> HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_YES
      else -> HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_UNDEFINED
    }

  // populate navigation
  navigation =
    when (config.navigation) {
      AndroidResConfiguration.NAVIGATION_NONAV -> Navigation.NAVIGATION_NONAV
      AndroidResConfiguration.NAVIGATION_DPAD -> Navigation.NAVIGATION_DPAD
      AndroidResConfiguration.NAVIGATION_TRACKBALL -> Navigation.NAVIGATION_TRACKBALL
      AndroidResConfiguration.NAVIGATION_WHEEL -> Navigation.NAVIGATION_WHEEL
      else -> Navigation.NAVIGATION_UNDEFINED
    }

  // populate navigationHidden
  navigationHidden =
    when (config.navigationHidden) {
      AndroidResConfiguration.NAVIGATIONHIDDEN_NO -> NavigationHidden.NAVIGATION_HIDDEN_NO
      AndroidResConfiguration.NAVIGATIONHIDDEN_YES -> NavigationHidden.NAVIGATION_HIDDEN_YES
      else -> NavigationHidden.NAVIGATION_HIDDEN_UNDEFINED
    }

  // populate uiMode (type, night)
  populateUiMode(config.uiMode)

  // orientation
  orientation =
    when (config.orientation) {
      AndroidResConfiguration.ORIENTATION_PORTRAIT -> Orientation.ORIENTATION_PORTRAIT
      AndroidResConfiguration.ORIENTATION_LANDSCAPE -> Orientation.ORIENTATION_LANDSCAPE
      AndroidResConfiguration.ORIENTATION_SQUARE -> Orientation.ORIENTATION_SQUARE
      else -> Orientation.ORIENTATION_UNDEFINED
    }

  // populate grammaticalGender
  if (Build.VERSION.SDK_INT >= 34) {
    grammaticalGender =
      when (config.grammaticalGender) {
        AndroidResConfiguration.GRAMMATICAL_GENDER_NEUTRAL -> GrammaticalGender.GRAMMATICAL_GENDER_NEUTRAL
        AndroidResConfiguration.GRAMMATICAL_GENDER_FEMININE -> GrammaticalGender.GRAMMATICAL_GENDER_FEMININE
        AndroidResConfiguration.GRAMMATICAL_GENDER_MASCULINE -> GrammaticalGender.GRAMMATICAL_GENDER_MASCULINE
        else -> GrammaticalGender.GRAMMATICAL_GENDER_UNDEFINED
      }
  }

  return this
}

private fun Configuration.Builder.populateScreenLayout(screenLayout: Int) {
  val sizeVal = screenLayout and AndroidResConfiguration.SCREENLAYOUT_SIZE_MASK
  screenLayoutSize =
    when (sizeVal) {
      AndroidResConfiguration.SCREENLAYOUT_SIZE_SMALL -> ScreenLayoutSize.SCREEN_LAYOUT_SIZE_SMALL
      AndroidResConfiguration.SCREENLAYOUT_SIZE_NORMAL -> ScreenLayoutSize.SCREEN_LAYOUT_SIZE_NORMAL
      AndroidResConfiguration.SCREENLAYOUT_SIZE_LARGE -> ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE
      AndroidResConfiguration.SCREENLAYOUT_SIZE_XLARGE -> ScreenLayoutSize.SCREEN_LAYOUT_SIZE_XLARGE
      else -> ScreenLayoutSize.SCREEN_LAYOUT_SIZE_UNDEFINED
    }

  val longVal = screenLayout and AndroidResConfiguration.SCREENLAYOUT_LONG_MASK
  screenLayoutLong =
    when (longVal) {
      AndroidResConfiguration.SCREENLAYOUT_LONG_NO -> ScreenLayoutLong.SCREEN_LAYOUT_LONG_NO
      AndroidResConfiguration.SCREENLAYOUT_LONG_YES -> ScreenLayoutLong.SCREEN_LAYOUT_LONG_YES
      else -> ScreenLayoutLong.SCREEN_LAYOUT_LONG_UNDEFINED
    }

  val layoutDirVal = screenLayout and AndroidResConfiguration.SCREENLAYOUT_LAYOUTDIR_MASK
  layoutDirection =
    when (layoutDirVal) {
      AndroidResConfiguration.SCREENLAYOUT_LAYOUTDIR_LTR -> LayoutDirection.LAYOUT_DIRECTION_LTR
      AndroidResConfiguration.SCREENLAYOUT_LAYOUTDIR_RTL -> LayoutDirection.LAYOUT_DIRECTION_RTL
      else -> LayoutDirection.LAYOUT_DIRECTION_UNDEFINED
    }

  val roundVal = screenLayout and AndroidResConfiguration.SCREENLAYOUT_ROUND_MASK
  screenLayoutRound =
    when (roundVal) {
      AndroidResConfiguration.SCREENLAYOUT_ROUND_NO -> ScreenLayoutRound.SCREEN_LAYOUT_ROUND_NO
      AndroidResConfiguration.SCREENLAYOUT_ROUND_YES -> ScreenLayoutRound.SCREEN_LAYOUT_ROUND_YES
      else -> ScreenLayoutRound.SCREEN_LAYOUT_ROUND_UNDEFINED
    }
}

private fun Configuration.Builder.populateColorMode(colorMode: Int) {
  val wideGamutVal = colorMode and AndroidResConfiguration.COLOR_MODE_WIDE_COLOR_GAMUT_MASK
  colorModeWideGamut =
    when (wideGamutVal) {
      AndroidResConfiguration.COLOR_MODE_WIDE_COLOR_GAMUT_NO -> ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_NO
      AndroidResConfiguration.COLOR_MODE_WIDE_COLOR_GAMUT_YES -> ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_YES
      else -> ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_UNDEFINED
    }

  val hdrVal = colorMode and AndroidResConfiguration.COLOR_MODE_HDR_MASK
  colorModeHdr =
    when (hdrVal) {
      AndroidResConfiguration.COLOR_MODE_HDR_NO -> ColorModeHdr.COLOR_MODE_HDR_NO
      AndroidResConfiguration.COLOR_MODE_HDR_YES -> ColorModeHdr.COLOR_MODE_HDR_YES
      else -> ColorModeHdr.COLOR_MODE_HDR_UNDEFINED
    }
}

private fun Configuration.Builder.populateUiMode(uiMode: Int) {
  val uiModeTypeVal = uiMode and AndroidResConfiguration.UI_MODE_TYPE_MASK
  uiModeType =
    when (uiModeTypeVal) {
      AndroidResConfiguration.UI_MODE_TYPE_NORMAL -> UiModeType.UI_MODE_TYPE_NORMAL
      AndroidResConfiguration.UI_MODE_TYPE_DESK -> UiModeType.UI_MODE_TYPE_DESK
      AndroidResConfiguration.UI_MODE_TYPE_CAR -> UiModeType.UI_MODE_TYPE_CAR
      AndroidResConfiguration.UI_MODE_TYPE_TELEVISION -> UiModeType.UI_MODE_TYPE_TELEVISION
      AndroidResConfiguration.UI_MODE_TYPE_APPLIANCE -> UiModeType.UI_MODE_TYPE_APPLIANCE
      AndroidResConfiguration.UI_MODE_TYPE_WATCH -> UiModeType.UI_MODE_TYPE_WATCH
      AndroidResConfiguration.UI_MODE_TYPE_VR_HEADSET -> UiModeType.UI_MODE_TYPE_VR_HEADSET
      else -> UiModeType.UI_MODE_TYPE_UNDEFINED
    }

  val uiModeNightVal = uiMode and AndroidResConfiguration.UI_MODE_NIGHT_MASK
  uiModeNight =
    when (uiModeNightVal) {
      AndroidResConfiguration.UI_MODE_NIGHT_NO -> UiModeNight.UI_MODE_NIGHT_NO
      AndroidResConfiguration.UI_MODE_NIGHT_YES -> UiModeNight.UI_MODE_NIGHT_YES
      else -> UiModeNight.UI_MODE_NIGHT_UNDEFINED
    }
}

internal fun JavaUtilLocale.convert(stringTable: StringTable): Locale {
  val self = this
  return Locale.newBuilder()
    .apply {
      language = stringTable.put(self.language)
      country = stringTable.put(self.country)
      variant = stringTable.put(self.variant)
      script = stringTable.put(self.script)
    }
    .build()
}
