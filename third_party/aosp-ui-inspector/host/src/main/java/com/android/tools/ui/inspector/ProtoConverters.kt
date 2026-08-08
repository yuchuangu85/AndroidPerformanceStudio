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

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

/**
 * Holds pre-indexed parameters mapping and string table for efficient Compose node parsing.
 *
 * The device protocol returns parameters and semantics together, but each facet is an independent `--include` demand: only the maps for
 * requested facets are built, so unrequested data never reaches the domain model or the output.
 */
private class ComposeParameters(
  response: LayoutInspectorComposeProtocol.GetAllParametersResponse,
  includeParameters: Boolean,
  includeSemantics: Boolean,
) {
  /** Maps Composable ID to its list of parameters. */
  val parametersMap: Map<Long, List<LayoutInspectorComposeProtocol.Parameter>> =
    if (includeParameters) response.parameterGroupsList.associate { group -> group.composableId to group.parameterList } else emptyMap()

  /** Maps Composable ID to its list of merged semantics. */
  val mergedSemanticsMap: Map<Long, List<LayoutInspectorComposeProtocol.Parameter>> =
    if (includeSemantics) response.parameterGroupsList.associate { group -> group.composableId to group.mergedSemanticsList }
    else emptyMap()

  /** Maps Composable ID to its list of unmerged semantics. */
  val unmergedSemanticsMap: Map<Long, List<LayoutInspectorComposeProtocol.Parameter>> =
    if (includeSemantics) response.parameterGroupsList.associate { group -> group.composableId to group.unmergedSemanticsList }
    else emptyMap()

  /** String table for resolving parameter names and string values. */
  val parameterStringTable: Map<Int, String> = response.stringsList.associate { it.id to it.str }
}

/**
 * Converts a protobuf [ViewInspectorProtocol.ViewNode] into a domain [UiNode.ViewNode].
 *
 * @param includeResolutionStack whether the `resolution-stack` facet was requested.
 */
internal fun convertViewNode(
  node: ViewInspectorProtocol.ViewNode,
  stringTable: Map<Int, String>,
  includeResolutionStack: Boolean,
): UiNode.ViewNode {
  val className = stringTable[node.className] ?: "unknown view"
  val bounds = UiNode.Bounds(x = node.bounds.x, y = node.bounds.y, width = node.bounds.width, height = node.bounds.height)
  val idResource = stringTable[node.idResource]
  val layoutResource = stringTable[node.layoutResource]
  val attributes =
    node.attributesList.map { attr ->
      val directSource = if (includeResolutionStack) stringTable[attr.directSource] else null
      val styleChain = if (includeResolutionStack) attr.styleChainList.map { stringTable[it] ?: "unknown" } else emptyList()
      UiNode.Attribute(
        name = stringTable[attr.name] ?: "unknown",
        value = attr.toAttributeValue(stringTable),
        directSource = directSource,
        styleChain = styleChain,
      )
    }
  val children = node.childrenList.map { convertViewNode(it, stringTable, includeResolutionStack) }.toMutableList<UiNode>()
  return UiNode.ViewNode(
    id = node.id,
    className = className,
    bounds = bounds,
    idResource = idResource,
    layoutResource = layoutResource,
    attributes = attributes,
    children = children,
  )
}

private fun ViewInspectorProtocol.ViewNode.Attribute.toAttributeValue(stringTable: Map<Int, String>): UiNode.AttributeValue {
  return when (type) {
    ViewInspectorProtocol.ViewNode.Attribute.Type.STRING -> {
      UiNode.AttributeValue.StringVal(stringTable[int32Value] ?: "")
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.BOOLEAN -> {
      UiNode.AttributeValue.BooleanVal(int32Value != 0)
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.INT32,
    ViewInspectorProtocol.ViewNode.Attribute.Type.INT16,
    ViewInspectorProtocol.ViewNode.Attribute.Type.BYTE -> {
      UiNode.AttributeValue.NumberVal(int32Value)
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.CHAR -> {
      UiNode.AttributeValue.StringVal(int32Value.toChar().toString())
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.INT64 -> {
      UiNode.AttributeValue.NumberVal(int64Value)
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.DOUBLE -> {
      UiNode.AttributeValue.NumberVal(doubleValue)
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.FLOAT -> {
      UiNode.AttributeValue.NumberVal(floatValue)
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.DIMENSION -> {
      UiNode.AttributeValue.DimensionVal(floatValue)
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.COLOR -> {
      UiNode.AttributeValue.ColorVal(int32Value)
    }
    ViewInspectorProtocol.ViewNode.Attribute.Type.INT_ENUM,
    ViewInspectorProtocol.ViewNode.Attribute.Type.GRAVITY,
    ViewInspectorProtocol.ViewNode.Attribute.Type.INT_FLAG,
    ViewInspectorProtocol.ViewNode.Attribute.Type.RESOURCE,
    ViewInspectorProtocol.ViewNode.Attribute.Type.DRAWABLE,
    ViewInspectorProtocol.ViewNode.Attribute.Type.ANIM,
    ViewInspectorProtocol.ViewNode.Attribute.Type.ANIMATOR,
    ViewInspectorProtocol.ViewNode.Attribute.Type.INTERPOLATOR,
    ViewInspectorProtocol.ViewNode.Attribute.Type.OBJECT,
    ViewInspectorProtocol.ViewNode.Attribute.Type.UNSPECIFIED -> {
      UiNode.AttributeValue.StringVal(stringTable[int32Value] ?: "")
    }
    else -> UiNode.AttributeValue.NullVal
  }
}

/**
 * Converts a protobuf [LayoutInspectorComposeProtocol.ComposableNode] into a domain [UiNode.ComposeNode].
 *
 * @param node the protobuf Composable node to convert.
 * @param stringTable string table containing all the text resources indexed by ID.
 * @param hostedViews a map of View ID to [UiNode.ViewNode] representing all Android views hosted within the entire Compose tree.
 * @param parameters optional Composable parameters.
 * @param includeParameters whether the `attributes` facet was requested: only then are Compose parameters copied into the nodes.
 * @param includeSemantics whether the `semantics` facet was requested: only then are the semantics lists copied into the nodes.
 */
internal fun convertComposeNode(
  node: LayoutInspectorComposeProtocol.ComposableNode,
  stringTable: Map<Int, String>,
  hostedViews: Map<Long, UiNode.ViewNode>,
  parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse? = null,
  includeParameters: Boolean,
  includeSemantics: Boolean,
): UiNode.ComposeNode {
  val composeParameters = parameters?.let { ComposeParameters(it, includeParameters, includeSemantics) }
  return doConvertComposeNode(node, stringTable, hostedViews, composeParameters)
}

/** Private recursive helper that maps the Composable nodes and propagates pre-indexed parameters. */
private fun doConvertComposeNode(
  node: LayoutInspectorComposeProtocol.ComposableNode,
  stringTable: Map<Int, String>,
  hostedViews: Map<Long, UiNode.ViewNode>,
  parameters: ComposeParameters? = null,
): UiNode.ComposeNode {
  val name = stringTable[node.name] ?: "unknown composable"
  val bounds =
    if (node.hasBounds()) {
      val layout = node.bounds.layout
      UiNode.Bounds(x = layout.x, y = layout.y, width = layout.w, height = layout.h)
    } else {
      UiNode.Bounds(x = 0, y = 0, width = 0, height = 0)
    }

  val nodeParams = parameters?.parametersMap?.get(node.id) ?: emptyList()
  val nodeMergedSemantics = parameters?.mergedSemanticsMap?.get(node.id) ?: emptyList()
  val nodeUnmergedSemantics = parameters?.unmergedSemanticsMap?.get(node.id) ?: emptyList()
  val paramStringTable = parameters?.parameterStringTable ?: emptyMap()

  val mappedParameters = nodeParams.map { convertParameterToComposeParameter(it, paramStringTable) }
  val mappedMergedSemantics = nodeMergedSemantics.map { convertParameterToComposeParameter(it, paramStringTable) }
  val mappedUnmergedSemantics = nodeUnmergedSemantics.map { convertParameterToComposeParameter(it, paramStringTable) }

  val children = node.childrenList.map { doConvertComposeNode(it, stringTable, hostedViews, parameters) }.toMutableList<UiNode>()
  if (node.viewId != 0L) {
    hostedViews[node.viewId]?.let { hostedView -> children.add(hostedView) }
  }
  val sourceLocation =
    if (node.filename != 0) {
      val filename = stringTable[node.filename] ?: "Missing"
      UiNode.SourceLocation(filename, node.lineNumber)
    } else {
      null
    }
  return UiNode.ComposeNode(
    id = node.id,
    className = name,
    bounds = bounds,
    children = children,
    sourceLocation = sourceLocation,
    parameters = mappedParameters,
    mergedSemantics = mappedMergedSemantics,
    unmergedSemantics = mappedUnmergedSemantics,
  )
}

/** Helper to map Composable proto parameters to framework-agnostic domain ComposeParameters. */
private fun convertParameterToComposeParameter(
  param: LayoutInspectorComposeProtocol.Parameter,
  stringTable: Map<Int, String>,
): UiNode.ComposeParameter {
  val name = stringTable[param.name] ?: "unknown"
  return if (param.elementsCount > 0) {
    val allAnonymous = param.elementsList.all { it.name == 0 }
    val elements = param.elementsList.map { convertParameterToComposeParameter(it, stringTable) }
    UiNode.ComposeParameter.Group(name, elements, allAnonymous)
  } else {
    UiNode.ComposeParameter.Single(name, getParameterValue(param, stringTable))
  }
}

private fun getParameterValue(
  param: LayoutInspectorComposeProtocol.Parameter,
  stringTable: Map<Int, String>,
): UiNode.ComposeParameter.Value {
  return when (param.type) {
    LayoutInspectorComposeProtocol.Parameter.Type.STRING,
    LayoutInspectorComposeProtocol.Parameter.Type.ITERABLE -> {
      val stringValue = if (param.int32Value != 0) stringTable[param.int32Value] ?: "" else ""
      UiNode.ComposeParameter.Value.StringVal(stringValue)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.BOOLEAN -> {
      UiNode.ComposeParameter.Value.BooleanVal(param.int32Value == 1)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DOUBLE -> {
      UiNode.ComposeParameter.Value.NumberVal(param.doubleValue)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.FLOAT -> {
      UiNode.ComposeParameter.Value.NumberVal(param.floatValue)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_DP -> {
      UiNode.ComposeParameter.Value.DimensionVal(param.floatValue, UiNode.ComposeParameter.DimensionUnit.DP)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_SP -> {
      UiNode.ComposeParameter.Value.DimensionVal(param.floatValue, UiNode.ComposeParameter.DimensionUnit.SP)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.DIMENSION_EM -> {
      UiNode.ComposeParameter.Value.DimensionVal(param.floatValue, UiNode.ComposeParameter.DimensionUnit.EM)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.INT32 -> {
      UiNode.ComposeParameter.Value.NumberVal(param.int32Value)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.INT64 -> {
      UiNode.ComposeParameter.Value.NumberVal(param.int64Value)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.COLOR -> {
      UiNode.ComposeParameter.Value.ColorVal(param.int32Value)
    }
    LayoutInspectorComposeProtocol.Parameter.Type.RESOURCE -> {
      if (param.hasResourceValue()) {
        val res = param.resourceValue
        val namespace = stringTable[res.namespace]
        val type = stringTable[res.type]
        val resName = stringTable[res.name] ?: "unknown"
        UiNode.ComposeParameter.Value.ResourceVal(namespace, type, resName)
      } else {
        UiNode.ComposeParameter.Value.NullVal
      }
    }
    LayoutInspectorComposeProtocol.Parameter.Type.LAMBDA,
    LayoutInspectorComposeProtocol.Parameter.Type.FUNCTION_REFERENCE -> {
      if (param.hasLambdaValue()) {
        val lambda = param.lambdaValue
        val fileName = stringTable[lambda.fileName]
        UiNode.ComposeParameter.Value.LambdaVal(fileName = fileName, startLineNumber = lambda.startLineNumber)
      } else {
        UiNode.ComposeParameter.Value.LambdaVal(fileName = null, startLineNumber = null)
      }
    }
    else -> UiNode.ComposeParameter.Value.NullVal
  }
}

/** Converts a protobuf [ViewInspectorProtocol.Configuration] into a domain [DeviceConfiguration]. */
internal fun convertConfiguration(config: ViewInspectorProtocol.Configuration, stringTable: Map<Int, String>): DeviceConfiguration {
  val locale =
    if (config.hasLocale()) {
      DeviceLocale(
        language = stringTable[config.locale.language],
        country = stringTable[config.locale.country],
        variant = stringTable[config.locale.variant],
        script = stringTable[config.locale.script],
      )
    } else {
      null
    }

  return DeviceConfiguration(
    // In Android Configuration, 0 / 0.0f represent sentinel constants for UNDEFINED values.
    // We map them to null to cleanly express absent/unspecified attributes in the domain layer.
    fontScale = if (config.fontScale != 0.0f) config.fontScale else null,
    countryCode = if (config.countryCode != 0) config.countryCode else null,
    networkCode = if (config.networkCode != 0) config.networkCode else null,
    locale = locale,
    screenLayoutSize =
      when (config.screenLayoutSize) {
        ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_SMALL -> ScreenLayoutSize.SMALL
        ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_NORMAL -> ScreenLayoutSize.NORMAL
        ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_LARGE -> ScreenLayoutSize.LARGE
        ViewInspectorProtocol.ScreenLayoutSize.SCREEN_LAYOUT_SIZE_XLARGE -> ScreenLayoutSize.XLARGE
        else -> null
      },
    screenLayoutLong =
      when (config.screenLayoutLong) {
        ViewInspectorProtocol.ScreenLayoutLong.SCREEN_LAYOUT_LONG_NO -> ScreenLayoutLong.NO
        ViewInspectorProtocol.ScreenLayoutLong.SCREEN_LAYOUT_LONG_YES -> ScreenLayoutLong.YES
        else -> null
      },
    layoutDirection =
      when (config.layoutDirection) {
        ViewInspectorProtocol.LayoutDirection.LAYOUT_DIRECTION_LTR -> LayoutDirection.LTR
        ViewInspectorProtocol.LayoutDirection.LAYOUT_DIRECTION_RTL -> LayoutDirection.RTL
        else -> null
      },
    screenLayoutRound =
      when (config.screenLayoutRound) {
        ViewInspectorProtocol.ScreenLayoutRound.SCREEN_LAYOUT_ROUND_NO -> ScreenLayoutRound.NO
        ViewInspectorProtocol.ScreenLayoutRound.SCREEN_LAYOUT_ROUND_YES -> ScreenLayoutRound.YES
        else -> null
      },
    colorModeWideGamut =
      when (config.colorModeWideGamut) {
        ViewInspectorProtocol.ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_NO -> ColorModeWideGamut.NO
        ViewInspectorProtocol.ColorModeWideGamut.COLOR_MODE_WIDE_GAMUT_YES -> ColorModeWideGamut.YES
        else -> null
      },
    colorModeHdr =
      when (config.colorModeHdr) {
        ViewInspectorProtocol.ColorModeHdr.COLOR_MODE_HDR_NO -> ColorModeHdr.NO
        ViewInspectorProtocol.ColorModeHdr.COLOR_MODE_HDR_YES -> ColorModeHdr.YES
        else -> null
      },
    touchScreen =
      when (config.touchScreen) {
        ViewInspectorProtocol.TouchScreen.TOUCH_SCREEN_NOTOUCH -> TouchScreen.NOTOUCH
        ViewInspectorProtocol.TouchScreen.TOUCH_SCREEN_STYLUS -> TouchScreen.STYLUS
        ViewInspectorProtocol.TouchScreen.TOUCH_SCREEN_FINGER -> TouchScreen.FINGER
        else -> null
      },
    keyboard =
      when (config.keyboard) {
        ViewInspectorProtocol.Keyboard.KEYBOARD_NOKEYS -> Keyboard.NOKEYS
        ViewInspectorProtocol.Keyboard.KEYBOARD_QWERTY -> Keyboard.QWERTY
        ViewInspectorProtocol.Keyboard.KEYBOARD_12KEY -> Keyboard.KEY_12
        else -> null
      },
    keyboardHidden =
      when (config.keyboardHidden) {
        ViewInspectorProtocol.KeyboardHidden.KEYBOARD_HIDDEN_NO -> KeyboardHidden.NO
        ViewInspectorProtocol.KeyboardHidden.KEYBOARD_HIDDEN_YES -> KeyboardHidden.YES
        else -> null
      },
    hardKeyboardHidden =
      when (config.hardKeyboardHidden) {
        ViewInspectorProtocol.HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_NO -> HardKeyboardHidden.NO
        ViewInspectorProtocol.HardKeyboardHidden.HARD_KEYBOARD_HIDDEN_YES -> HardKeyboardHidden.YES
        else -> null
      },
    navigation =
      when (config.navigation) {
        ViewInspectorProtocol.Navigation.NAVIGATION_NONAV -> Navigation.NONAV
        ViewInspectorProtocol.Navigation.NAVIGATION_DPAD -> Navigation.DPAD
        ViewInspectorProtocol.Navigation.NAVIGATION_TRACKBALL -> Navigation.TRACKBALL
        ViewInspectorProtocol.Navigation.NAVIGATION_WHEEL -> Navigation.WHEEL
        else -> null
      },
    navigationHidden =
      when (config.navigationHidden) {
        ViewInspectorProtocol.NavigationHidden.NAVIGATION_HIDDEN_NO -> NavigationHidden.NO
        ViewInspectorProtocol.NavigationHidden.NAVIGATION_HIDDEN_YES -> NavigationHidden.YES
        else -> null
      },
    uiModeType =
      when (config.uiModeType) {
        ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_NORMAL -> UiModeType.NORMAL
        ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_DESK -> UiModeType.DESK
        ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_CAR -> UiModeType.CAR
        ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_TELEVISION -> UiModeType.TELEVISION
        ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_APPLIANCE -> UiModeType.APPLIANCE
        ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_WATCH -> UiModeType.WATCH
        ViewInspectorProtocol.UiModeType.UI_MODE_TYPE_VR_HEADSET -> UiModeType.VR_HEADSET
        else -> null
      },
    uiModeNight =
      when (config.uiModeNight) {
        ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_NO -> UiModeNight.NO
        ViewInspectorProtocol.UiModeNight.UI_MODE_NIGHT_YES -> UiModeNight.YES
        else -> null
      },
    smallestScreenWidthDp = if (config.smallestScreenWidthDp != 0) Dimension.Dp(config.smallestScreenWidthDp) else null,
    density = if (config.density != 0) Dimension.Dpi(config.density) else null,
    orientation =
      when (config.orientation) {
        ViewInspectorProtocol.Orientation.ORIENTATION_PORTRAIT -> Orientation.PORTRAIT
        ViewInspectorProtocol.Orientation.ORIENTATION_LANDSCAPE -> Orientation.LANDSCAPE
        ViewInspectorProtocol.Orientation.ORIENTATION_SQUARE -> Orientation.SQUARE
        else -> null
      },
    screenWidthDp = if (config.screenWidthDp != 0) Dimension.Dp(config.screenWidthDp) else null,
    screenHeightDp = if (config.screenHeightDp != 0) Dimension.Dp(config.screenHeightDp) else null,
    grammaticalGender =
      when (config.grammaticalGender) {
        ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_NEUTRAL -> GrammaticalGender.NEUTRAL
        ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_FEMININE -> GrammaticalGender.FEMININE
        ViewInspectorProtocol.GrammaticalGender.GRAMMATICAL_GENDER_MASCULINE -> GrammaticalGender.MASCULINE
        else -> null
      },
  )
}

/** Converts a protobuf [ViewInspectorProtocol.AppContext] into a domain [AppContext]. */
internal fun convertAppContext(appContext: ViewInspectorProtocol.AppContext, stringTable: Map<Int, String>): AppContext {
  val theme = stringTable[appContext.theme]
  val displays =
    appContext.displayInfoList.map { display ->
      DisplayInfo(id = display.id, widthPx = display.widthPx, heightPx = display.heightPx, orientation = display.orientation)
    }
  return AppContext(theme = theme, displays = displays)
}
