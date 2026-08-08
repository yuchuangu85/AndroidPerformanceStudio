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

package com.android.tools.ui.inspector.printer.json

import com.android.tools.ui.inspector.AppContext
import com.android.tools.ui.inspector.DeviceConfiguration
import com.android.tools.ui.inspector.DeviceLocale
import com.android.tools.ui.inspector.UiDump
import com.android.tools.ui.inspector.UiNode
import com.android.tools.ui.inspector.printer.UiDumpPrinter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private val GSON_COMPACT: Gson = GsonBuilder().serializeNulls().create()
private val GSON_PRETTY: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

/**
 * Runs [block] with a JSON printer targeting the destination selected by [output]: the given file, or standard output when null. File
 * streams are closed on completion and write failures are reported as [IOException].
 */
internal fun withJsonPrinter(output: Path?, prettyPrint: Boolean, block: (UiDumpPrinter) -> Unit) {
  if (output == null) {
    val stdout = System.out
    block(JsonUiDumpPrinter(out = stdout, prettyPrint = prettyPrint))
    if (stdout.checkError()) {
      throw IOException("Failed to write output to standard output")
    }
    return
  }
  if (Files.isDirectory(output)) {
    throw IOException("Output path is a directory, expected a file: $output")
  }
  val fileOut = PrintStream(Files.newOutputStream(output), false, Charsets.UTF_8)
  fileOut.use { block(JsonUiDumpPrinter(out = it, prettyPrint = prettyPrint)) }
  // Checked after close so that errors recorded while closing are observed too.
  if (fileOut.checkError()) {
    throw IOException("Failed to write output to: $output")
  }
}

/**
 * Explicit JSON key constants defining the external serialization schema contract. Keys are hardcoded constants to ensure internal Kotlin
 * domain model refactorings do not accidentally break the public JSON schema and to avoid runtime reflection overhead.
 */
private object JsonKeys {
  const val APP_CONTEXT = "appContext"
  const val CONFIGURATION = "configuration"
  const val ROOTS = "roots"
  const val TYPE = "type"
  const val VIEW_NODE = "ViewNode"
  const val COMPOSE_NODE = "ComposeNode"
  const val ID = "id"
  const val CLASS_NAME = "className"
  const val BOUNDS = "bounds"
  const val ID_RESOURCE = "idResource"
  const val LAYOUT_RESOURCE = "layoutResource"
  const val ATTRIBUTES = "attributes"
  const val SOURCE_LOCATION = "sourceLocation"
  const val PARAMETERS = "parameters"
  const val MERGED_SEMANTICS = "mergedSemantics"
  const val UNMERGED_SEMANTICS = "unmergedSemantics"
  const val CHILDREN = "children"
  const val X = "x"
  const val Y = "y"
  const val WIDTH = "width"
  const val HEIGHT = "height"
  const val NAME = "name"
  const val VALUE = "value"
  const val DIRECT_SOURCE = "directSource"
  const val STYLE_CHAIN = "styleChain"
  const val IS_COLLECTION = "isCollection"
  const val ELEMENTS = "elements"
  const val UNIT = "unit"
  const val NAMESPACE = "namespace"
  const val FILE_NAME = "fileName"
  const val LINE_NUMBER = "lineNumber"
  const val START_LINE_NUMBER = "startLineNumber"
  const val THEME = "theme"
  const val DISPLAYS = "displays"
  const val WIDTH_PX = "widthPx"
  const val HEIGHT_PX = "heightPx"
  const val ORIENTATION = "orientation"
  const val DP = "dp"
  const val SP = "sp"

  // DeviceConfiguration keys
  const val FONT_SCALE = "fontScale"
  const val COUNTRY_CODE = "countryCode"
  const val NETWORK_CODE = "networkCode"
  const val LOCALE = "locale"
  const val SCREEN_LAYOUT_SIZE = "screenLayoutSize"
  const val SCREEN_LAYOUT_LONG = "screenLayoutLong"
  const val LAYOUT_DIRECTION = "layoutDirection"
  const val SCREEN_LAYOUT_ROUND = "screenLayoutRound"
  const val COLOR_MODE_WIDE_GAMUT = "colorModeWideGamut"
  const val COLOR_MODE_HDR = "colorModeHdr"
  const val TOUCH_SCREEN = "touchScreen"
  const val KEYBOARD = "keyboard"
  const val KEYBOARD_HIDDEN = "keyboardHidden"
  const val HARD_KEYBOARD_HIDDEN = "hardKeyboardHidden"
  const val NAVIGATION = "navigation"
  const val NAVIGATION_HIDDEN = "navigationHidden"
  const val UI_MODE_TYPE = "uiModeType"
  const val UI_MODE_NIGHT = "uiModeNight"
  const val SMALLEST_SCREEN_WIDTH_DP = "smallestScreenWidthDp"
  const val DENSITY = "density"
  const val SCREEN_WIDTH_DP = "screenWidthDp"
  const val SCREEN_HEIGHT_DP = "screenHeightDp"
  const val GRAMMATICAL_GENDER = "grammaticalGender"
}

/**
 * JSON implementation of [UiDumpPrinter].
 *
 * @param out Target output stream for printed dumps.
 * @param prettyPrint Whether to format JSON output with indentation.
 */
internal class JsonUiDumpPrinter(private val out: PrintStream, private val prettyPrint: Boolean) : UiDumpPrinter {

  override fun printDump(uiDump: UiDump) {
    writeJson(serializeUiDump(uiDump))
  }

  /** Serializes [jsonObject] to the output stream. */
  private fun writeJson(jsonObject: JsonObject) {
    normalizeNonFiniteNumbers(jsonObject)
    val gson = if (prettyPrint) GSON_PRETTY else GSON_COMPACT
    val writer = OutputStreamWriter(out, Charsets.UTF_8)
    gson.toJson(jsonObject, writer)
    writer.flush()
    out.println()
  }

  /**
   * Replaces every non-finite numeric primitive in the tree with JSON null, in place. JSON has no NaN or Infinity token, Compose routinely
   * reports Float.NaN for unspecified values (e.g. Dp.Unspecified), and Gson would write them verbatim, producing output every strict
   * parser rejects.
   */
  private fun normalizeNonFiniteNumbers(element: JsonElement) {
    when (element) {
      is JsonObject ->
        for (entry in element.entrySet()) {
          if (entry.value.isNonFiniteNumber()) entry.setValue(JsonNull.INSTANCE) else normalizeNonFiniteNumbers(entry.value)
        }
      is JsonArray ->
        for (i in 0 until element.size()) {
          if (element.get(i).isNonFiniteNumber()) element.set(i, JsonNull.INSTANCE) else normalizeNonFiniteNumbers(element.get(i))
        }
      else -> Unit
    }
  }

  // Only Float and Double can carry non-finite values; other Number types (including arbitrary-precision ones whose doubleValue()
  // overflows to Infinity, like BigDecimal("1e400")) are always valid JSON numbers and must pass through untouched.
  private fun JsonElement.isNonFiniteNumber(): Boolean {
    if (this !is JsonPrimitive || !isNumber) return false
    return when (val number = asNumber) {
      is Float -> !number.isFinite()
      is Double -> !number.isFinite()
      else -> false
    }
  }

  private fun serializeUiDump(uiDump: UiDump): JsonObject {
    val root = JsonObject()

    uiDump.appContext?.let { appContext -> root.add(JsonKeys.APP_CONTEXT, serializeAppContext(appContext)) }

    uiDump.configuration?.let { config -> root.add(JsonKeys.CONFIGURATION, serializeDeviceConfiguration(config)) }

    val rootsArray = JsonArray()
    uiDump.roots.forEach { viewRoot -> rootsArray.add(serializeNodeTree(viewRoot)) }
    root.add(JsonKeys.ROOTS, rootsArray)

    return root
  }

  private fun serializeNodeTree(node: UiNode): JsonObject {
    val obj = JsonObject()
    when (node) {
      is UiNode.ViewNode -> {
        obj.addProperty(JsonKeys.TYPE, JsonKeys.VIEW_NODE)
        obj.addProperty(JsonKeys.ID, node.id)
        obj.addProperty(JsonKeys.CLASS_NAME, node.className)
        obj.add(JsonKeys.BOUNDS, serializeBounds(node.bounds))
        node.idResource?.let { if (it.isNotEmpty()) obj.addProperty(JsonKeys.ID_RESOURCE, it) }
        node.layoutResource?.let { if (it.isNotEmpty()) obj.addProperty(JsonKeys.LAYOUT_RESOURCE, it) }

        if (node.attributes.isNotEmpty()) {
          val attrsArray = JsonArray()
          node.attributes.forEach { attr -> attrsArray.add(serializeAttribute(attr)) }
          obj.add(JsonKeys.ATTRIBUTES, attrsArray)
        }
      }
      is UiNode.ComposeNode -> {
        obj.addProperty(JsonKeys.TYPE, JsonKeys.COMPOSE_NODE)
        obj.addProperty(JsonKeys.ID, node.id)
        obj.addProperty(JsonKeys.CLASS_NAME, node.className)
        obj.add(JsonKeys.BOUNDS, serializeBounds(node.bounds))
        node.sourceLocation?.let { loc ->
          val locObj = JsonObject()
          locObj.addProperty(JsonKeys.FILE_NAME, loc.filename)
          locObj.addProperty(JsonKeys.LINE_NUMBER, loc.lineNumber)
          obj.add(JsonKeys.SOURCE_LOCATION, locObj)
        }

        if (node.parameters.isNotEmpty()) {
          val paramsArray = JsonArray()
          node.parameters.forEach { param -> paramsArray.add(serializeComposeParameter(param)) }
          obj.add(JsonKeys.PARAMETERS, paramsArray)
        }

        if (node.mergedSemantics.isNotEmpty()) {
          val semanticsArray = JsonArray()
          node.mergedSemantics.forEach { param -> semanticsArray.add(serializeComposeParameter(param)) }
          obj.add(JsonKeys.MERGED_SEMANTICS, semanticsArray)
        }

        if (node.unmergedSemantics.isNotEmpty()) {
          val semanticsArray = JsonArray()
          node.unmergedSemantics.forEach { param -> semanticsArray.add(serializeComposeParameter(param)) }
          obj.add(JsonKeys.UNMERGED_SEMANTICS, semanticsArray)
        }
      }
    }

    if (node.children.isNotEmpty()) {
      val childrenArray = JsonArray()
      node.children.forEach { child -> childrenArray.add(serializeNodeTree(child)) }
      obj.add(JsonKeys.CHILDREN, childrenArray)
    }

    return obj
  }

  private fun serializeBounds(bounds: UiNode.Bounds): JsonObject {
    val obj = JsonObject()
    obj.addProperty(JsonKeys.X, bounds.x)
    obj.addProperty(JsonKeys.Y, bounds.y)
    obj.addProperty(JsonKeys.WIDTH, bounds.width)
    obj.addProperty(JsonKeys.HEIGHT, bounds.height)
    return obj
  }

  private fun serializeAttribute(attr: UiNode.Attribute): JsonObject {
    val obj = JsonObject()
    obj.addProperty(JsonKeys.NAME, attr.name)
    obj.add(JsonKeys.VALUE, serializeAttributeValue(attr.value))
    attr.directSource?.let { if (it.isNotEmpty()) obj.addProperty(JsonKeys.DIRECT_SOURCE, it) }
    if (attr.styleChain.isNotEmpty()) {
      val chainArray = JsonArray()
      attr.styleChain.forEach { chainArray.add(it) }
      obj.add(JsonKeys.STYLE_CHAIN, chainArray)
    }
    return obj
  }

  private fun serializeAttributeValue(valObj: UiNode.AttributeValue): JsonElement {
    return when (valObj) {
      is UiNode.AttributeValue.StringVal -> JsonPrimitive(valObj.value)
      is UiNode.AttributeValue.BooleanVal -> JsonPrimitive(valObj.value)
      is UiNode.AttributeValue.NumberVal -> JsonPrimitive(valObj.value)
      is UiNode.AttributeValue.ColorVal -> JsonPrimitive(String.format(Locale.ROOT, "#%08X", valObj.colorInt))
      is UiNode.AttributeValue.DimensionVal -> {
        val obj = JsonObject()
        obj.addProperty(JsonKeys.VALUE, valObj.value)
        valObj.dp?.let { obj.addProperty(JsonKeys.DP, it) }
        valObj.sp?.let { obj.addProperty(JsonKeys.SP, it) }
        obj
      }
      UiNode.AttributeValue.NullVal -> JsonNull.INSTANCE
    }
  }

  private fun serializeComposeParameter(param: UiNode.ComposeParameter): JsonObject {
    val obj = JsonObject()
    obj.addProperty(JsonKeys.NAME, param.name)
    when (param) {
      is UiNode.ComposeParameter.Single -> {
        obj.add(JsonKeys.VALUE, serializeComposeParameterValue(param.value))
      }
      is UiNode.ComposeParameter.Group -> {
        obj.addProperty(JsonKeys.IS_COLLECTION, param.isCollection)
        val elementsArray = JsonArray()
        param.elements.forEach { elem -> elementsArray.add(serializeComposeParameter(elem)) }
        obj.add(JsonKeys.ELEMENTS, elementsArray)
      }
    }
    return obj
  }

  private fun serializeComposeParameterValue(valObj: UiNode.ComposeParameter.Value): JsonElement {
    return when (valObj) {
      is UiNode.ComposeParameter.Value.StringVal -> JsonPrimitive(valObj.value)
      is UiNode.ComposeParameter.Value.BooleanVal -> JsonPrimitive(valObj.value)
      is UiNode.ComposeParameter.Value.NumberVal -> JsonPrimitive(valObj.value)
      is UiNode.ComposeParameter.Value.ColorVal -> JsonPrimitive(String.format(Locale.ROOT, "#%08X", valObj.colorInt))
      is UiNode.ComposeParameter.Value.DimensionVal -> {
        val obj = JsonObject()
        obj.addProperty(JsonKeys.VALUE, valObj.value)
        obj.addProperty(JsonKeys.UNIT, valObj.unit.name)
        obj
      }
      is UiNode.ComposeParameter.Value.ResourceVal -> {
        val obj = JsonObject()
        valObj.namespace?.let { obj.addProperty(JsonKeys.NAMESPACE, it) }
        valObj.type?.let { obj.addProperty(JsonKeys.TYPE, it) }
        obj.addProperty(JsonKeys.NAME, valObj.name)
        obj
      }
      is UiNode.ComposeParameter.Value.LambdaVal -> {
        val obj = JsonObject()
        valObj.fileName?.let { obj.addProperty(JsonKeys.FILE_NAME, it) }
        valObj.startLineNumber?.let { obj.addProperty(JsonKeys.START_LINE_NUMBER, it) }
        obj
      }
      UiNode.ComposeParameter.Value.NullVal -> JsonNull.INSTANCE
    }
  }

  private fun serializeAppContext(appContext: AppContext): JsonObject {
    val obj = JsonObject()
    appContext.theme?.let { obj.addProperty(JsonKeys.THEME, it) }
    if (appContext.displays.isNotEmpty()) {
      val displaysArray = JsonArray()
      appContext.displays.forEach { display ->
        val displayObj = JsonObject()
        displayObj.addProperty(JsonKeys.ID, display.id)
        displayObj.addProperty(JsonKeys.WIDTH_PX, display.widthPx)
        displayObj.addProperty(JsonKeys.HEIGHT_PX, display.heightPx)
        displayObj.addProperty(JsonKeys.ORIENTATION, display.orientation)
        displaysArray.add(displayObj)
      }
      obj.add(JsonKeys.DISPLAYS, displaysArray)
    }
    return obj
  }

  private fun serializeDeviceConfiguration(config: DeviceConfiguration): JsonObject {
    val obj = JsonObject()
    config.colorModeHdr?.let { obj.addProperty(JsonKeys.COLOR_MODE_HDR, it.name.lowercase()) }
    config.colorModeWideGamut?.let { obj.addProperty(JsonKeys.COLOR_MODE_WIDE_GAMUT, it.name.lowercase()) }
    config.countryCode?.let { obj.addProperty(JsonKeys.COUNTRY_CODE, it) }
    config.density?.let { obj.addProperty(JsonKeys.DENSITY, it.value) }
    config.fontScale?.let { obj.addProperty(JsonKeys.FONT_SCALE, it) }
    config.grammaticalGender?.let { obj.addProperty(JsonKeys.GRAMMATICAL_GENDER, it.name.lowercase()) }
    config.hardKeyboardHidden?.let { obj.addProperty(JsonKeys.HARD_KEYBOARD_HIDDEN, it.name.lowercase()) }
    config.keyboard?.let { obj.addProperty(JsonKeys.KEYBOARD, it.name.lowercase()) }
    config.keyboardHidden?.let { obj.addProperty(JsonKeys.KEYBOARD_HIDDEN, it.name.lowercase()) }
    config.layoutDirection?.let { obj.addProperty(JsonKeys.LAYOUT_DIRECTION, it.name.lowercase()) }
    config.locale?.listOfNotNullFormat()?.let { if (it.isNotEmpty()) obj.addProperty(JsonKeys.LOCALE, it) }
    config.navigation?.let { obj.addProperty(JsonKeys.NAVIGATION, it.name.lowercase()) }
    config.navigationHidden?.let { obj.addProperty(JsonKeys.NAVIGATION_HIDDEN, it.name.lowercase()) }
    config.networkCode?.let { obj.addProperty(JsonKeys.NETWORK_CODE, it) }
    config.orientation?.let { obj.addProperty(JsonKeys.ORIENTATION, it.name.lowercase()) }
    config.screenHeightDp?.let { obj.addProperty(JsonKeys.SCREEN_HEIGHT_DP, it.value) }
    config.screenLayoutLong?.let { obj.addProperty(JsonKeys.SCREEN_LAYOUT_LONG, it.name.lowercase()) }
    config.screenLayoutRound?.let { obj.addProperty(JsonKeys.SCREEN_LAYOUT_ROUND, it.name.lowercase()) }
    config.screenLayoutSize?.let { obj.addProperty(JsonKeys.SCREEN_LAYOUT_SIZE, it.name.lowercase()) }
    config.screenWidthDp?.let { obj.addProperty(JsonKeys.SCREEN_WIDTH_DP, it.value) }
    config.smallestScreenWidthDp?.let { obj.addProperty(JsonKeys.SMALLEST_SCREEN_WIDTH_DP, it.value) }
    config.touchScreen?.let { obj.addProperty(JsonKeys.TOUCH_SCREEN, it.name.lowercase()) }
    config.uiModeNight?.let { obj.addProperty(JsonKeys.UI_MODE_NIGHT, it.name.lowercase()) }
    config.uiModeType?.let { obj.addProperty(JsonKeys.UI_MODE_TYPE, it.name.lowercase()) }
    return obj
  }

  private fun DeviceLocale.listOfNotNullFormat(): String =
    listOfNotNull(language, country, variant, script).filter { it.isNotEmpty() }.joinToString("-")
}
