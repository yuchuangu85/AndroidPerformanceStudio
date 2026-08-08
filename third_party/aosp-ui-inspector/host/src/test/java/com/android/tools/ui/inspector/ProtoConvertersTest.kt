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

import com.android.tools.ui.inspector.printer.text.format
import com.android.tools.ui.inspector.printer.text.formatComposeParameter
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.google.common.truth.Truth.assertThat
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.Test

class ProtoConvertersTest {

  @Test
  fun testConvertComposeNode_PrimitiveParameters() {
    val stringTable =
      mapOf(
        1 to "MyComponent",
        2 to "paramString",
        3 to "Hello",
        4 to "paramDouble",
        5 to "paramDimension",
        6 to "paramColor",
        7 to "paramResource",
        8 to "textView",
        9 to "android",
        10 to "paramLambda",
        11 to "File.kt",
      )

    val composableNode =
      LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
        .setId(100)
        .setName(1) // MyComponent
        .build()

    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3) // "Hello"
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(4)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.DOUBLE)
                .setDoubleValue(3.14)
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(5)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_DP)
                .setFloatValue(8f)
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(6)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.COLOR)
                .setInt32Value(-1) // 0xFFFFFFFF
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(7)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.RESOURCE)
                .setResourceValue(
                  LayoutInspectorComposeProtocol.Resource.newBuilder()
                    .setNamespace(9) // android
                    .setType(1)
                    .setName(8) // textView
                )
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(10)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.LAMBDA)
                .setLambdaValue(
                  LayoutInspectorComposeProtocol.LambdaValue.newBuilder()
                    .setFileName(11) // File.kt
                    .setStartLineNumber(42)
                )
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("paramString"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("Hello"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("paramDouble"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(5).setStr("paramDimension"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(6).setStr("paramColor"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(7).setStr("paramResource"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(8).setStr("textView"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(9).setStr("android"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(10).setStr("paramLambda"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(11).setStr("File.kt"))
        .build()

    val composeNode =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = emptyMap(),
        parameters = allParams,
        includeParameters = true,
        includeSemantics = true,
      )

    assertThat(composeNode.parameters).hasSize(6)

    // 1. String Value
    val pString = composeNode.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(pString.name).isEqualTo("paramString")
    assertThat(pString.value).isEqualTo(UiNode.ComposeParameter.Value.StringVal("Hello"))
    assertThat(formatComposeParameter(pString)).isEqualTo("Hello")

    // 2. Double Value
    val pDouble = composeNode.parameters[1] as UiNode.ComposeParameter.Single
    assertThat(pDouble.name).isEqualTo("paramDouble")
    assertThat(pDouble.value).isEqualTo(UiNode.ComposeParameter.Value.NumberVal(3.14))
    assertThat(formatComposeParameter(pDouble)).isEqualTo("3.14")

    // 3. Dimension Value
    val pDim = composeNode.parameters[2] as UiNode.ComposeParameter.Single
    assertThat(pDim.name).isEqualTo("paramDimension")
    assertThat(pDim.value).isEqualTo(UiNode.ComposeParameter.Value.DimensionVal(8f, UiNode.ComposeParameter.DimensionUnit.DP))
    assertThat(formatComposeParameter(pDim)).isEqualTo("8.0dp")

    // 4. Color Value
    val pColor = composeNode.parameters[3] as UiNode.ComposeParameter.Single
    assertThat(pColor.name).isEqualTo("paramColor")
    assertThat(pColor.value).isEqualTo(UiNode.ComposeParameter.Value.ColorVal(-1))
    assertThat(formatComposeParameter(pColor)).isEqualTo("#FFFFFFFF")

    // 5. Resource Value
    val pRes = composeNode.parameters[4] as UiNode.ComposeParameter.Single
    assertThat(pRes.name).isEqualTo("paramResource")
    assertThat(pRes.value).isEqualTo(UiNode.ComposeParameter.Value.ResourceVal("android", null, "textView"))
    assertThat(formatComposeParameter(pRes)).isEqualTo("@android:textView")

    // 6. Lambda Value
    val pLambda = composeNode.parameters[5] as UiNode.ComposeParameter.Single
    assertThat(pLambda.name).isEqualTo("paramLambda")
    assertThat(pLambda.value).isEqualTo(UiNode.ComposeParameter.Value.LambdaVal("File.kt", 42))
    assertThat(formatComposeParameter(pLambda)).isEqualTo("[lambda in File.kt:42]")
  }

  @Test
  fun testConvertComposeNode_CollectionsAndGroups() {
    val stringTable = mapOf(1 to "MyComponent", 2 to "listParam", 3 to "item1", 4 to "item2", 5 to "mapParam", 6 to "key1", 7 to "val1")

    val composableNode =
      LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
        .setId(100)
        .setName(1) // MyComponent
        .build()

    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2) // listParam
                .addElements(
                  LayoutInspectorComposeProtocol.Parameter.newBuilder()
                    .setName(0) // anonymous
                    .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                    .setInt32Value(3) // "item1"
                )
                .addElements(
                  LayoutInspectorComposeProtocol.Parameter.newBuilder()
                    .setName(0) // anonymous
                    .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                    .setInt32Value(4) // "item2"
                )
            )
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(5) // mapParam
                .addElements(
                  LayoutInspectorComposeProtocol.Parameter.newBuilder()
                    .setName(6) // key1
                    .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                    .setInt32Value(7) // "val1"
                )
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("listParam"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("item1"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("item2"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(5).setStr("mapParam"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(6).setStr("key1"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(7).setStr("val1"))
        .build()

    val composeNode =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = emptyMap(),
        parameters = allParams,
        includeParameters = true,
        includeSemantics = true,
      )

    assertThat(composeNode.parameters).hasSize(2)

    // 1. Collection List
    val pList = composeNode.parameters[0] as UiNode.ComposeParameter.Group
    assertThat(pList.name).isEqualTo("listParam")
    assertThat(pList.isCollection).isTrue()
    assertThat(pList.elements).hasSize(2)
    assertThat(formatComposeParameter(pList)).isEqualTo("[item1, item2]")

    // 2. Map object
    val pMap = composeNode.parameters[1] as UiNode.ComposeParameter.Group
    assertThat(pMap.name).isEqualTo("mapParam")
    assertThat(pMap.isCollection).isFalse()
    assertThat(pMap.elements).hasSize(1)
    assertThat(formatComposeParameter(pMap)).isEqualTo("{key1=val1}")
  }

  @Test
  fun testConvertViewNode_PrimitiveAttributes() {
    val stringTable = mapOf(1 to "myView", 2 to "myCharAttr", 3 to "myIntAttr")

    val viewNodeProto =
      ViewInspectorProtocol.ViewNode.newBuilder()
        .setId(100L)
        .setClassName(1) // myView
        .setBounds(ViewInspectorProtocol.Rect.newBuilder().setX(0).setY(0).setWidth(100).setHeight(100))
        .addAttributes(
          ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
            .setName(2) // myCharAttr
            .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.CHAR)
            .setInt32Value(65) // 'A'
        )
        .addAttributes(
          ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
            .setName(3) // myIntAttr
            .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.INT32)
            .setInt32Value(42)
        )
        .build()

    val viewNode = convertViewNode(viewNodeProto, stringTable, includeResolutionStack = true)

    assertThat(viewNode.attributes).hasSize(2)

    val charAttr = viewNode.attributes[0]
    assertThat(charAttr.name).isEqualTo("myCharAttr")
    assertThat(charAttr.value).isEqualTo(UiNode.AttributeValue.StringVal("A"))

    val intAttr = viewNode.attributes[1]
    assertThat(intAttr.name).isEqualTo("myIntAttr")
    assertThat(intAttr.value).isEqualTo(UiNode.AttributeValue.NumberVal(42))
  }

  @Test
  fun testConvertConfiguration() {
    val stringTable = mapOf(1 to "en", 2 to "US", 3 to "variant", 4 to "Latn")
    val configProto =
      ViewInspectorProtocol.Configuration.newBuilder()
        .setDensity(420)
        .setScreenWidthDp(1080)
        .setScreenHeightDp(1920)
        .setSmallestScreenWidthDp(720)
        .setFontScale(1.2f)
        .setOrientation(ViewInspectorProtocol.Orientation.ORIENTATION_LANDSCAPE)
        .setScreenLayoutSize(ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE)
        .setScreenLayoutLong(ViewInspectorProtocol.ScreenLayoutLong.SCREEN_LAYOUT_LONG_YES)
        .setLayoutDirection(ViewInspectorProtocol.LayoutDirection.LAYOUT_DIRECTION_RTL)
        .setScreenLayoutRound(ViewInspectorProtocol.ScreenLayoutRound.SCREEN_LAYOUT_ROUND_YES)
        .setColorModeWideGamut(ViewInspectorProtocol.ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_YES)
        .setColorModeHdr(ViewInspectorProtocol.ColorModeHdr.COLOR_MODE_HDR_YES)
        .setTouchScreen(ViewInspectorProtocol.TouchScreen.TOUCH_SCREEN_FINGER)
        .setKeyboard(ViewInspectorProtocol.Keyboard.KEYBOARD_QWERTY)
        .setKeyboardHidden(ViewInspectorProtocol.KeyboardHidden.KEYBOARD_HIDDEN_NO)
        .setHardKeyboardHidden(ViewInspectorProtocol.HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_NO)
        .setNavigation(ViewInspectorProtocol.Navigation.NAVIGATION_NONAV)
        .setNavigationHidden(ViewInspectorProtocol.NavigationHidden.NAVIGATION_HIDDEN_YES)
        .setUiModeType(ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_NORMAL)
        .setUiModeNight(ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_YES)
        .setLocale(ViewInspectorProtocol.Locale.newBuilder().setLanguage(1).setCountry(2).setVariant(3).setScript(4))
        .setGrammaticalGender(ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_FEMININE)
        .build()

    val config = convertConfiguration(configProto, stringTable)

    assertThat(config.density).isEqualTo(Dimension.Dpi(420))
    assertThat(config.screenWidthDp).isEqualTo(Dimension.Dp(1080))
    assertThat(config.screenHeightDp).isEqualTo(Dimension.Dp(1920))
    assertThat(config.smallestScreenWidthDp).isEqualTo(Dimension.Dp(720))
    assertThat(config.fontScale).isEqualTo(1.2f)
    assertThat(config.orientation).isEqualTo(Orientation.LANDSCAPE)
    assertThat(config.screenLayoutSize).isEqualTo(ScreenLayoutSize.LARGE)
    assertThat(config.screenLayoutLong).isEqualTo(ScreenLayoutLong.YES)
    assertThat(config.layoutDirection).isEqualTo(LayoutDirection.RTL)
    assertThat(config.screenLayoutRound).isEqualTo(ScreenLayoutRound.YES)
    assertThat(config.colorModeWideGamut).isEqualTo(ColorModeWideGamut.YES)
    assertThat(config.colorModeHdr).isEqualTo(ColorModeHdr.YES)
    assertThat(config.touchScreen).isEqualTo(TouchScreen.FINGER)
    assertThat(config.keyboard).isEqualTo(Keyboard.QWERTY)
    assertThat(config.keyboardHidden).isEqualTo(KeyboardHidden.NO)
    assertThat(config.hardKeyboardHidden).isEqualTo(HardKeyboardHidden.NO)
    assertThat(config.navigation).isEqualTo(Navigation.NONAV)
    assertThat(config.navigationHidden).isEqualTo(NavigationHidden.YES)
    assertThat(config.uiModeType).isEqualTo(UiModeType.NORMAL)
    assertThat(config.uiModeNight).isEqualTo(UiModeNight.YES)
    assertThat(config.locale?.format()).isEqualTo("en-US-variant-Latn")
    assertThat(config.grammaticalGender).isEqualTo(GrammaticalGender.FEMININE)
  }

  @Test
  fun testConvertAppContext() {
    val stringTable = mapOf(1 to "@style/Theme.AppCompat")
    val displayProto = ViewInspectorProtocol.Display.newBuilder().setId(0).setWidthPx(1080).setHeightPx(1920).setOrientation(90).build()
    val appContextProto = ViewInspectorProtocol.AppContext.newBuilder().setTheme(1).addDisplayInfo(displayProto).build()

    val appContext = convertAppContext(appContextProto, stringTable)

    assertThat(appContext.theme).isEqualTo("@style/Theme.AppCompat")
    assertThat(appContext.displays).hasSize(1)
    val display = appContext.displays.first()
    assertThat(display.id).isEqualTo(0)
    assertThat(display.widthPx).isEqualTo(1080)
    assertThat(display.heightPx).isEqualTo(1920)
    assertThat(display.orientation).isEqualTo(90)
  }

  @Test
  fun testConvertComposeNode_facetsAreIndependent() {
    val stringTable = mapOf(1 to "MyComponent")
    val composableNode = LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(100).setName(1).build()

    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2) // text
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3) // "Hello"
            )
            .addMergedSemantics(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(4) // ContentDescription
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(5) // "Button"
            )
            .addUnmergedSemantics(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(4) // ContentDescription
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(5) // "Button"
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("text"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("Hello"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("ContentDescription"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(5).setStr("Button"))
        .build()

    fun convert(includeParameters: Boolean, includeSemantics: Boolean) =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = emptyMap(),
        parameters = allParams,
        includeParameters = includeParameters,
        includeSemantics = includeSemantics,
      )

    // The device response carries both facets, but each is copied into the node only when requested.
    val attributesOnly = convert(includeParameters = true, includeSemantics = false)
    assertThat(attributesOnly.parameters).hasSize(1)
    assertThat(attributesOnly.mergedSemantics).isEmpty()
    assertThat(attributesOnly.unmergedSemantics).isEmpty()

    val semanticsOnly = convert(includeParameters = false, includeSemantics = true)
    assertThat(semanticsOnly.parameters).isEmpty()
    assertThat(semanticsOnly.mergedSemantics).hasSize(1)
    assertThat(semanticsOnly.unmergedSemantics).hasSize(1)
    assertThat((semanticsOnly.mergedSemantics.single() as UiNode.ComposeParameter.Single).name).isEqualTo("ContentDescription")

    val both = convert(includeParameters = true, includeSemantics = true)
    assertThat(both.parameters).hasSize(1)
    assertThat(both.mergedSemantics).hasSize(1)
    assertThat(both.unmergedSemantics).hasSize(1)
  }

  @Test
  fun testConvertComposeNode_noFacetsRequested_copiesNothing() {
    val stringTable = mapOf(1 to "MyComponent")
    val composableNode = LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(100).setName(1).build()
    val allParams =
      LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
        .addParameterGroups(
          LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(100)
            .addParameter(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3)
            )
            .addMergedSemantics(
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(2)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(3)
            )
        )
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("text"))
        .addStrings(LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("Hello"))
        .build()

    // Even with a response present, the converter is the contract: nothing requested, nothing copied.
    val node =
      convertComposeNode(
        node = composableNode,
        stringTable = stringTable,
        hostedViews = emptyMap(),
        parameters = allParams,
        includeParameters = false,
        includeSemantics = false,
      )

    assertThat(node.parameters).isEmpty()
    assertThat(node.mergedSemantics).isEmpty()
    assertThat(node.unmergedSemantics).isEmpty()
  }

  @Test
  fun testConvertViewNode_withoutResolutionStack_omitsProvenance() {
    val stringTable = mapOf(1 to "android.widget.TextView", 2 to "text", 3 to "Hello", 4 to "layout.xml", 5 to "AppTheme")
    // The agent reports provenance whenever the device setting is enabled — which an earlier resolution-stack run
    // leaves set — so the converter must drop it when the facet was not requested.
    val viewNodeProto =
      ViewInspectorProtocol.ViewNode.newBuilder()
        .setId(1)
        .setClassName(1)
        .addAttributes(
          ViewInspectorProtocol.ViewNode.Attribute.newBuilder()
            .setName(2)
            .setType(ViewInspectorProtocol.ViewNode.Attribute.Type.STRING)
            .setInt32Value(3)
            .setDirectSource(4)
            .addStyleChain(5)
        )
        .build()

    val withProvenance = convertViewNode(viewNodeProto, stringTable, includeResolutionStack = true)
    assertThat(withProvenance.attributes.single().directSource).isEqualTo("layout.xml")
    assertThat(withProvenance.attributes.single().styleChain).containsExactly("AppTheme")

    val withoutProvenance = convertViewNode(viewNodeProto, stringTable, includeResolutionStack = false)
    assertThat(withoutProvenance.attributes.single().directSource).isNull()
    assertThat(withoutProvenance.attributes.single().styleChain).isEmpty()
    // The attribute value itself is unaffected: provenance is the resolution-stack facet, the value is the attributes facet.
    assertThat(withoutProvenance.attributes.single().value).isEqualTo(UiNode.AttributeValue.StringVal("Hello"))
  }
}
