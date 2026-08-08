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
import com.android.tools.ui.inspector.Dimension
import com.android.tools.ui.inspector.DisplayInfo
import com.android.tools.ui.inspector.Orientation
import com.android.tools.ui.inspector.UiDump
import com.android.tools.ui.inspector.UiNode
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonUiDumpPrinterTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var outputStream: ByteArrayOutputStream
  private lateinit var printStream: PrintStream

  @Before
  fun setUp() {
    outputStream = ByteArrayOutputStream()
    printStream = PrintStream(outputStream)
  }

  @Test
  fun testWithJsonPrinterWritesToFile() {
    val outputFile = tempFolder.root.toPath().resolve("dump.json")

    withJsonPrinter(output = outputFile, prettyPrint = false) { printer -> printer.printDump(EMPTY_DUMP) }

    assertThat(String(Files.readAllBytes(outputFile), Charsets.UTF_8).trim()).isEqualTo("""{"roots":[]}""")
  }

  @Test
  fun testWithJsonPrinterOverwritesExistingFile() {
    val outputFile = tempFolder.newFile("dump.json").toPath()
    Files.write(outputFile, "previous content longer than the new dump".toByteArray(Charsets.UTF_8))

    withJsonPrinter(output = outputFile, prettyPrint = false) { printer -> printer.printDump(EMPTY_DUMP) }

    assertThat(String(Files.readAllBytes(outputFile), Charsets.UTF_8).trim()).isEqualTo("""{"roots":[]}""")
  }

  @Test
  fun testWithJsonPrinterDefaultsToStdout() {
    val capturedOut = ByteArrayOutputStream()
    val originalOut = System.out
    System.setOut(PrintStream(capturedOut))
    try {
      withJsonPrinter(output = null, prettyPrint = false) { printer -> printer.printDump(EMPTY_DUMP) }
    } finally {
      System.setOut(originalOut)
    }

    assertThat(capturedOut.toString(Charsets.UTF_8.name()).trim()).isEqualTo("""{"roots":[]}""")
  }

  @Test
  fun testWithJsonPrinterReportsStdoutWriteFailure() {
    val failingStream =
      PrintStream(
        object : OutputStream() {
          override fun write(b: Int) {
            throw IOException("stdout unavailable")
          }
        }
      )
    val originalOut = System.out
    System.setOut(failingStream)
    try {
      assertThrows(IOException::class.java) {
        withJsonPrinter(output = null, prettyPrint = false) { printer -> printer.printDump(EMPTY_DUMP) }
      }
    } finally {
      System.setOut(originalOut)
    }
  }

  @Test
  fun testWithJsonPrinterRejectsDirectory() {
    val exception = assertThrows(IOException::class.java) { withJsonPrinter(output = tempFolder.root.toPath(), prettyPrint = false) {} }

    assertThat(exception).hasMessageThat().contains("directory")
  }

  @Test
  fun testPrintDumpWithViewAndComposeNodes() {
    val viewNode =
      UiNode.ViewNode(
        id = 1L,
        className = "android.widget.TextView",
        bounds = UiNode.Bounds(0, 0, 100, 50),
        idResource = "title_id",
        layoutResource = "activity_main",
        attributes =
          listOf(
            UiNode.Attribute("text", UiNode.AttributeValue.StringVal("Hello")),
            UiNode.Attribute("enabled", UiNode.AttributeValue.BooleanVal(true)),
            UiNode.Attribute("count", UiNode.AttributeValue.NumberVal(42)),
            UiNode.Attribute("color", UiNode.AttributeValue.ColorVal(0xFF00FF00.toInt())),
            UiNode.Attribute("padding", UiNode.AttributeValue.DimensionVal(16f, dp = 16f, sp = null)),
            UiNode.Attribute("empty", UiNode.AttributeValue.NullVal),
          ),
      )

    val composeNode =
      UiNode.ComposeNode(
        id = 2L,
        className = "androidx.compose.material3.Text",
        bounds = UiNode.Bounds(10, 20, 200, 100),
        sourceLocation = UiNode.SourceLocation("MainScreen.kt", 42),
        parameters =
          listOf(
            UiNode.ComposeParameter.Single("text", UiNode.ComposeParameter.Value.StringVal("Compose Text")),
            UiNode.ComposeParameter.Single(
              "dim",
              UiNode.ComposeParameter.Value.DimensionVal(12f, UiNode.ComposeParameter.DimensionUnit.DP),
            ),
            UiNode.ComposeParameter.Single("res", UiNode.ComposeParameter.Value.ResourceVal("com.app", "string", "label")),
            UiNode.ComposeParameter.Single("lambda", UiNode.ComposeParameter.Value.LambdaVal("Screen.kt", 10)),
            UiNode.ComposeParameter.Group(
              name = "items",
              elements = listOf(UiNode.ComposeParameter.Single("item1", UiNode.ComposeParameter.Value.StringVal("val1"))),
              isCollection = true,
            ),
          ),
        mergedSemantics = listOf(UiNode.ComposeParameter.Single("Role", UiNode.ComposeParameter.Value.StringVal("Button"))),
        unmergedSemantics = emptyList(),
      )

    viewNode.children.add(composeNode)

    val appContext = AppContext(theme = "AppTheme", displays = listOf(DisplayInfo(0, 1080, 1920, 0)))

    val config = DeviceConfiguration(density = Dimension.Dpi(420), fontScale = 1.0f, locale = DeviceLocale("en", "US", null, null))

    val uiDump = UiDump(roots = listOf(viewNode), configuration = config, stringTable = emptyMap(), appContext = appContext)

    val printer = JsonUiDumpPrinter(out = printStream, prettyPrint = true)
    printer.printDump(uiDump)

    val jsonString = outputStream.toString(Charsets.UTF_8)
    assertThat(jsonString).isNotEmpty()

    val json = JsonParser.parseString(jsonString).asJsonObject
    assertThat(json.has("appContext")).isTrue()
    assertThat(json.has("configuration")).isTrue()
    assertThat(json.has("roots")).isTrue()

    val roots = json.getAsJsonArray("roots")
    assertThat(roots.size()).isEqualTo(1)

    val rootNode = roots[0].asJsonObject
    assertThat(rootNode.get("type").asString).isEqualTo("ViewNode")
    assertThat(rootNode.get("id").asLong).isEqualTo(1L)
    assertThat(rootNode.get("idResource").asString).isEqualTo("title_id")

    val children = rootNode.getAsJsonArray("children")
    assertThat(children.size()).isEqualTo(1)

    val childNode = children[0].asJsonObject
    assertThat(childNode.get("type").asString).isEqualTo("ComposeNode")
    assertThat(childNode.get("id").asLong).isEqualTo(2L)
    assertThat(childNode.getAsJsonObject("sourceLocation").get("fileName").asString).isEqualTo("MainScreen.kt")
  }

  @Test
  fun testPrintDumpCompactFormatting() {
    val node =
      UiNode.ViewNode(
        id = 1L,
        className = "android.view.View",
        bounds = UiNode.Bounds(0, 0, 10, 10),
        idResource = null,
        layoutResource = null,
        attributes = emptyList(),
      )
    val uiDump = UiDump(roots = listOf(node), configuration = null, stringTable = emptyMap(), appContext = null)

    val printer = JsonUiDumpPrinter(out = printStream, prettyPrint = false)
    printer.printDump(uiDump)

    val jsonString = outputStream.toString(Charsets.UTF_8).trim()
    assertThat(jsonString).doesNotContain("\n")
    val json = JsonParser.parseString(jsonString).asJsonObject
    assertThat(json.has("roots")).isTrue()
  }

  @Test
  fun testAllAttributeValueTypesSerialization() {
    val attrString =
      UiNode.Attribute("str", UiNode.AttributeValue.StringVal("val"), directSource = "layout.xml", styleChain = listOf("AppTheme"))
    val attrBool = UiNode.Attribute("bool", UiNode.AttributeValue.BooleanVal(false))
    val attrNum = UiNode.Attribute("num", UiNode.AttributeValue.NumberVal(3.14))
    val attrColor = UiNode.Attribute("color", UiNode.AttributeValue.ColorVal(0xFFFF0000.toInt()))
    val attrDim = UiNode.Attribute("dim", UiNode.AttributeValue.DimensionVal(24f, dp = 24f, sp = 18f))
    val attrNull = UiNode.Attribute("nullVal", UiNode.AttributeValue.NullVal)

    val viewNode =
      UiNode.ViewNode(
        id = 10L,
        className = "android.widget.ImageView",
        bounds = UiNode.Bounds(0, 0, 50, 50),
        idResource = null,
        layoutResource = null,
        attributes = listOf(attrString, attrBool, attrNum, attrColor, attrDim, attrNull),
      )

    val uiDump = UiDump(roots = listOf(viewNode), configuration = null, stringTable = emptyMap(), appContext = null)

    val printer = JsonUiDumpPrinter(out = printStream, prettyPrint = false)
    printer.printDump(uiDump)

    val json = JsonParser.parseString(outputStream.toString(Charsets.UTF_8)).asJsonObject
    val rootObj = json.getAsJsonArray("roots")[0].asJsonObject
    val attrs = rootObj.getAsJsonArray("attributes")

    assertThat(attrs.size()).isEqualTo(6)

    val strAttrObj = attrs[0].asJsonObject
    assertThat(strAttrObj.get("name").asString).isEqualTo("str")
    assertThat(strAttrObj.get("value").asString).isEqualTo("val")
    assertThat(strAttrObj.get("directSource").asString).isEqualTo("layout.xml")
    assertThat(strAttrObj.getAsJsonArray("styleChain")[0].asString).isEqualTo("AppTheme")

    val colorAttrObj = attrs[3].asJsonObject
    assertThat(colorAttrObj.get("value").asString).isEqualTo("#FFFF0000")

    val dimAttrObj = attrs[4].asJsonObject
    val dimVal = dimAttrObj.getAsJsonObject("value")
    assertThat(dimVal.get("value").asFloat).isEqualTo(24f)
    assertThat(dimVal.get("dp").asFloat).isEqualTo(24f)
    assertThat(dimVal.get("sp").asFloat).isEqualTo(18f)

    val nullAttrObj = attrs[5].asJsonObject
    assertThat(nullAttrObj.get("value").isJsonNull).isTrue()
  }

  @Test
  fun testAllComposeParameterValueTypesSerialization() {
    val pString = UiNode.ComposeParameter.Single("pStr", UiNode.ComposeParameter.Value.StringVal("text"))
    val pBool = UiNode.ComposeParameter.Single("pBool", UiNode.ComposeParameter.Value.BooleanVal(true))
    val pNum = UiNode.ComposeParameter.Single("pNum", UiNode.ComposeParameter.Value.NumberVal(100))
    val pColor = UiNode.ComposeParameter.Single("pColor", UiNode.ComposeParameter.Value.ColorVal(0xFF0000FF.toInt()))
    val pDim =
      UiNode.ComposeParameter.Single("pDim", UiNode.ComposeParameter.Value.DimensionVal(8f, UiNode.ComposeParameter.DimensionUnit.SP))
    val pRes = UiNode.ComposeParameter.Single("pRes", UiNode.ComposeParameter.Value.ResourceVal("android", "drawable", "icon"))
    val pLambda = UiNode.ComposeParameter.Single("pLambda", UiNode.ComposeParameter.Value.LambdaVal("Comp.kt", 15))
    val pNull = UiNode.ComposeParameter.Single("pNull", UiNode.ComposeParameter.Value.NullVal)
    val pGroup = UiNode.ComposeParameter.Group("pGroup", listOf(pString), isCollection = false)

    val rootNode =
      UiNode.ViewNode(
        id = 1L,
        className = "android.view.View",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        idResource = null,
        layoutResource = null,
        attributes = emptyList(),
      )
    val composeNode =
      UiNode.ComposeNode(
        id = 20L,
        className = "androidx.compose.foundation.layout.Box",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        sourceLocation = null,
        parameters = listOf(pString, pBool, pNum, pColor, pDim, pRes, pLambda, pNull, pGroup),
        mergedSemantics = emptyList(),
        unmergedSemantics = listOf(UiNode.ComposeParameter.Single("ContentDescription", UiNode.ComposeParameter.Value.StringVal("Icon"))),
      )
    rootNode.children.add(composeNode)

    val uiDump = UiDump(roots = listOf(rootNode), configuration = null, stringTable = emptyMap(), appContext = null)

    val printer = JsonUiDumpPrinter(out = printStream, prettyPrint = false)
    printer.printDump(uiDump)

    val json = JsonParser.parseString(outputStream.toString(Charsets.UTF_8)).asJsonObject
    val rootObj = json.getAsJsonArray("roots")[0].asJsonObject
    val nodeObj = rootObj.getAsJsonArray("children")[0].asJsonObject
    val params = nodeObj.getAsJsonArray("parameters")

    assertThat(params.size()).isEqualTo(9)

    val colorObj = params[3].asJsonObject
    assertThat(colorObj.get("value").asString).isEqualTo("#FF0000FF")

    val dimObj = params[4].asJsonObject.getAsJsonObject("value")
    assertThat(dimObj.get("value").asFloat).isEqualTo(8f)
    assertThat(dimObj.get("unit").asString).isEqualTo("SP")

    val resObj = params[5].asJsonObject.getAsJsonObject("value")
    assertThat(resObj.get("namespace").asString).isEqualTo("android")
    assertThat(resObj.get("type").asString).isEqualTo("drawable")
    assertThat(resObj.get("name").asString).isEqualTo("icon")

    val lambdaObj = params[6].asJsonObject.getAsJsonObject("value")
    assertThat(lambdaObj.get("fileName").asString).isEqualTo("Comp.kt")
    assertThat(lambdaObj.get("startLineNumber").asInt).isEqualTo(15)

    val groupObj = params[8].asJsonObject
    assertThat(groupObj.get("isCollection").asBoolean).isFalse()
    assertThat(groupObj.getAsJsonArray("elements").size()).isEqualTo(1)

    val unmerged = nodeObj.getAsJsonArray("unmergedSemantics")
    assertThat(unmerged.size()).isEqualTo(1)
    assertThat(unmerged[0].asJsonObject.get("name").asString).isEqualTo("ContentDescription")
  }

  @Test
  fun testPrintDumpNormalizesNonFiniteNumbersToNull() {
    val viewNode =
      UiNode.ViewNode(
        id = 1L,
        className = "android.view.View",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        idResource = null,
        layoutResource = null,
        attributes =
          listOf(
            UiNode.Attribute("nan", UiNode.AttributeValue.NumberVal(Float.NaN)),
            UiNode.Attribute("negInf", UiNode.AttributeValue.NumberVal(Double.NEGATIVE_INFINITY)),
            UiNode.Attribute("dim", UiNode.AttributeValue.DimensionVal(Float.NaN, dp = Float.POSITIVE_INFINITY, sp = 18f)),
            UiNode.Attribute("finite", UiNode.AttributeValue.NumberVal(42)),
            // Finite but beyond Double range: doubleValue() overflows to Infinity, yet this is a valid JSON number and must survive.
            UiNode.Attribute("huge", UiNode.AttributeValue.NumberVal(BigDecimal("1e400"))),
          ),
      )
    val composeNode =
      UiNode.ComposeNode(
        id = 2L,
        className = "androidx.compose.foundation.layout.Box",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        sourceLocation = null,
        parameters =
          listOf(
            UiNode.ComposeParameter.Single("alpha", UiNode.ComposeParameter.Value.NumberVal(Float.NaN)),
            UiNode.ComposeParameter.Single(
              "size",
              UiNode.ComposeParameter.Value.DimensionVal(Float.NaN, UiNode.ComposeParameter.DimensionUnit.DP),
            ),
          ),
        mergedSemantics = emptyList(),
        unmergedSemantics = emptyList(),
      )
    viewNode.children.add(composeNode)

    val config = DeviceConfiguration(fontScale = Float.NaN)
    val uiDump = UiDump(roots = listOf(viewNode), configuration = config, stringTable = emptyMap(), appContext = null)

    val printer = JsonUiDumpPrinter(out = printStream, prettyPrint = false)
    printer.printDump(uiDump)

    val jsonString = outputStream.toString(Charsets.UTF_8)
    // The real contract: a spec-compliant parser accepts the document (bare NaN/Infinity tokens would be rejected).
    assertParsesStrictly(jsonString)

    val json = JsonParser.parseString(jsonString).asJsonObject
    assertThat(json.getAsJsonObject("configuration").get("fontScale").isJsonNull).isTrue()

    val rootObj = json.getAsJsonArray("roots")[0].asJsonObject
    val attrs = rootObj.getAsJsonArray("attributes")
    assertThat(attrs[0].asJsonObject.get("value").isJsonNull).isTrue()
    assertThat(attrs[1].asJsonObject.get("value").isJsonNull).isTrue()
    val dimVal = attrs[2].asJsonObject.getAsJsonObject("value")
    assertThat(dimVal.get("value").isJsonNull).isTrue()
    assertThat(dimVal.get("dp").isJsonNull).isTrue()
    assertThat(dimVal.get("sp").asFloat).isEqualTo(18f)
    assertThat(attrs[3].asJsonObject.get("value").asInt).isEqualTo(42)
    assertThat(attrs[4].asJsonObject.get("value").asBigDecimal).isEqualTo(BigDecimal("1e400"))

    val params = rootObj.getAsJsonArray("children")[0].asJsonObject.getAsJsonArray("parameters")
    assertThat(params[0].asJsonObject.get("value").isJsonNull).isTrue()
    val paramDim = params[1].asJsonObject.getAsJsonObject("value")
    assertThat(paramDim.get("value").isJsonNull).isTrue()
    assertThat(paramDim.get("unit").asString).isEqualTo("DP")
  }

  @Test
  fun testPrintDumpConfigurationValueEncodings() {
    fun dumpWith(config: DeviceConfiguration) =
      UiDump(roots = emptyList(), configuration = config, stringTable = emptyMap(), appContext = null)

    val config =
      DeviceConfiguration(
        density = Dimension.Dpi(420),
        fontScale = Float.NaN,
        orientation = Orientation.PORTRAIT,
        locale = DeviceLocale("en", "US", null, null),
      )
    val printer = JsonUiDumpPrinter(out = printStream, prettyPrint = false)
    printer.printDump(dumpWith(config))

    val json = JsonParser.parseString(outputStream.toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject("configuration")
    // The single configuration-value encoding: bare numbers for dimensions, lowercase enum names, joined locale tags,
    // non-finite numbers normalized to null.
    assertThat(json.get("density").asJsonPrimitive.isNumber).isTrue()
    assertThat(json.get("density").asInt).isEqualTo(420)
    assertThat(json.get("orientation").asString).isEqualTo("portrait")
    assertThat(json.get("locale").asString).isEqualTo("en-US")
    assertThat(json.get("fontScale").isJsonNull).isTrue()

    outputStream.reset()
    printer.printDump(dumpWith(DeviceConfiguration(locale = DeviceLocale("", "", null, null))))
    val emptyLocaleJson = JsonParser.parseString(outputStream.toString(Charsets.UTF_8)).asJsonObject.getAsJsonObject("configuration")
    // An empty locale has no representation: the property is omitted entirely.
    assertThat(emptyLocaleJson.has("locale")).isFalse()
  }

  /** Parses [json] with a strict (spec-compliant) reader, failing on any non-JSON token such as a bare NaN or Infinity. */
  private fun assertParsesStrictly(json: String) {
    val reader = JsonReader(StringReader(json))
    reader.strictness = Strictness.STRICT
    JsonParser.parseReader(reader)
    assertThat(reader.peek()).isEqualTo(JsonToken.END_DOCUMENT)
  }

  private companion object {
    val EMPTY_DUMP = UiDump(roots = emptyList(), configuration = null, stringTable = emptyMap(), appContext = null)
  }
}
