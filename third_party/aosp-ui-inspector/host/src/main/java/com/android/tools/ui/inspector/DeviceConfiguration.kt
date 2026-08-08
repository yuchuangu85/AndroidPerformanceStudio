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

package com.android.tools.ui.inspector

/** Represents device locale with resolved language, country, variant, and script strings. */
data class DeviceLocale(val language: String?, val country: String?, val variant: String?, val script: String?)

/** Screen orientation qualifier (e.g. portrait, landscape, square). */
enum class Orientation {
  PORTRAIT,
  LANDSCAPE,
  SQUARE,
}

/** Screen size layout qualifier (e.g. small, normal, large, xlarge). */
enum class ScreenLayoutSize {
  SMALL,
  NORMAL,
  LARGE,
  XLARGE,
}

/** Aspect ratio / screen longness layout qualifier (e.g. long for widescreen/tall displays vs not long). */
enum class ScreenLayoutLong {
  NO,
  YES,
}

/** Layout direction qualifier (e.g. LTR or RTL). */
enum class LayoutDirection {
  LTR,
  RTL,
}

/** Screen shape qualifier (e.g. round vs not round). */
enum class ScreenLayoutRound {
  NO,
  YES,
}

/** Wide color gamut capability qualifier. */
enum class ColorModeWideGamut {
  NO,
  YES,
}

/** High dynamic range (HDR) capability qualifier. */
enum class ColorModeHdr {
  NO,
  YES,
}

/** Primary touchscreen input type qualifier. */
enum class TouchScreen {
  NOTOUCH,
  STYLUS,
  FINGER,
}

/** Primary text input device / keyboard type qualifier. */
enum class Keyboard {
  NOKEYS,
  QWERTY,
  KEY_12,
}

/** State of the primary text keyboard (hidden vs visible). */
enum class KeyboardHidden {
  NO,
  YES,
}

/** State of the hardware keyboard (hidden vs visible). */
enum class HardKeyboardHidden {
  NO,
  YES,
}

/** Primary navigation method qualifier (e.g. dpad, trackball, wheel, nonav). */
enum class Navigation {
  NONAV,
  DPAD,
  TRACKBALL,
  WHEEL,
}

/** State of the navigation controller (hidden vs visible). */
enum class NavigationHidden {
  NO,
  YES,
}

/** UI mode type qualifier (e.g. normal, desk, car, television, watch, vr). */
enum class UiModeType {
  NORMAL,
  DESK,
  CAR,
  TELEVISION,
  APPLIANCE,
  WATCH,
  VR_HEADSET,
}

/** Night mode qualifier (yes vs no). */
enum class UiModeNight {
  NO,
  YES,
}

/** Grammatical gender system preference (neutral, feminine, masculine). */
enum class GrammaticalGender {
  NEUTRAL,
  FEMININE,
  MASCULINE,
}

/** Dimension qualifiers (e.g. Dp, Dpi). */
sealed class Dimension(open val value: Int) {
  data class Dp(override val value: Int) : Dimension(value)

  data class Dpi(override val value: Int) : Dimension(value)
}

/** Represents host-side device configuration. */
data class DeviceConfiguration(
  val fontScale: Float? = null,
  val countryCode: Int? = null,
  val networkCode: Int? = null,
  val locale: DeviceLocale? = null,
  val screenLayoutSize: ScreenLayoutSize? = null,
  val screenLayoutLong: ScreenLayoutLong? = null,
  val layoutDirection: LayoutDirection? = null,
  val screenLayoutRound: ScreenLayoutRound? = null,
  val colorModeWideGamut: ColorModeWideGamut? = null,
  val colorModeHdr: ColorModeHdr? = null,
  val touchScreen: TouchScreen? = null,
  val keyboard: Keyboard? = null,
  val keyboardHidden: KeyboardHidden? = null,
  val hardKeyboardHidden: HardKeyboardHidden? = null,
  val navigation: Navigation? = null,
  val navigationHidden: NavigationHidden? = null,
  val uiModeType: UiModeType? = null,
  val uiModeNight: UiModeNight? = null,
  val smallestScreenWidthDp: Dimension.Dp? = null,
  val density: Dimension.Dpi? = null,
  val orientation: Orientation? = null,
  val screenWidthDp: Dimension.Dp? = null,
  val screenHeightDp: Dimension.Dp? = null,
  val grammaticalGender: GrammaticalGender? = null,
)
