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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DimensionResolverTest {

  @Test
  fun testResolveDimensions_ViewNode() {
    val node =
      UiNode.ViewNode(
        id = 1L,
        className = "TextView",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        idResource = "text_view",
        layoutResource = null,
        attributes =
          listOf(
            UiNode.Attribute(name = "layout_width", value = UiNode.AttributeValue.DimensionVal(10f)),
            UiNode.Attribute(name = "textSize", value = UiNode.AttributeValue.DimensionVal(15f)),
            UiNode.Attribute(name = "text", value = UiNode.AttributeValue.StringVal("Hello")),
          ),
      )

    // 1. Resolve with density scale (densityDpi = 320 -> densityScale = 2.0) and fontScale = 1.5
    val resolved = node.resolveDimensions(density = Dimension.Dpi(320), fontScale = 1.5f) as UiNode.ViewNode

    assertThat(resolved.attributes).hasSize(3)

    val widthAttr = resolved.attributes[0]
    assertThat(widthAttr.name).isEqualTo("layout_width")
    assertThat(widthAttr.value).isEqualTo(UiNode.AttributeValue.DimensionVal(10f, dp = 5f, sp = null))

    val textSizeAttr = resolved.attributes[1]
    assertThat(textSizeAttr.name).isEqualTo("textSize")
    assertThat(textSizeAttr.value).isEqualTo(UiNode.AttributeValue.DimensionVal(15f, dp = null, sp = 5f))

    val textAttr = resolved.attributes[2]
    assertThat(textAttr.value).isEqualTo(UiNode.AttributeValue.StringVal("Hello"))

    // 2. Resolve without density scale (no changes to dimension attributes)
    val unresolved = node.resolveDimensions(density = null, fontScale = null) as UiNode.ViewNode
    assertThat(unresolved.attributes[0].value).isEqualTo(UiNode.AttributeValue.DimensionVal(10f, dp = null, sp = null))
  }

  @Test
  fun testResolveDimensions_ComposeNode() {
    // Compose nodes shouldn't have their parameter dimensions resolved by the DimensionResolver
    // because they are already resolved on the wire.
    // However, it should recursively resolve dimensions for hosted ViewNodes inside children.
    val viewNode =
      UiNode.ViewNode(
        id = 2L,
        className = "View",
        bounds = UiNode.Bounds(0, 0, 10, 10),
        idResource = null,
        layoutResource = null,
        attributes = listOf(UiNode.Attribute(name = "layout_height", value = UiNode.AttributeValue.DimensionVal(20f))),
      )

    val composeNode =
      UiNode.ComposeNode(
        id = 1L,
        className = "MyComposable",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        children = mutableListOf(viewNode),
        parameters = emptyList(),
        mergedSemantics = emptyList(),
        unmergedSemantics = emptyList(),
      )

    val resolved = composeNode.resolveDimensions(density = Dimension.Dpi(320), fontScale = 1.0f) as UiNode.ComposeNode
    val resolvedChild = resolved.children[0] as UiNode.ViewNode
    val heightAttr = resolvedChild.attributes[0]
    assertThat(heightAttr.value).isEqualTo(UiNode.AttributeValue.DimensionVal(20f, dp = 10f, sp = null))
  }

  @Test
  fun testResolveDimensions_InvalidDensity() {
    val node =
      UiNode.ViewNode(
        id = 1L,
        className = "TextView",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        idResource = "text_view",
        layoutResource = null,
        attributes =
          listOf(
            UiNode.Attribute(name = "layout_width", value = UiNode.AttributeValue.DimensionVal(10f)),
            UiNode.Attribute(name = "textSize", value = UiNode.AttributeValue.DimensionVal(15f)),
          ),
      )

    val resolvedZero = node.resolveDimensions(density = Dimension.Dpi(0), fontScale = 1.0f) as UiNode.ViewNode
    assertThat(resolvedZero.attributes[0].value).isEqualTo(UiNode.AttributeValue.DimensionVal(10f, dp = null, sp = null))
    assertThat(resolvedZero.attributes[1].value).isEqualTo(UiNode.AttributeValue.DimensionVal(15f, dp = null, sp = null))

    val resolvedNegative = node.resolveDimensions(density = Dimension.Dpi(-160), fontScale = 1.0f) as UiNode.ViewNode
    assertThat(resolvedNegative.attributes[0].value).isEqualTo(UiNode.AttributeValue.DimensionVal(10f, dp = null, sp = null))
    assertThat(resolvedNegative.attributes[1].value).isEqualTo(UiNode.AttributeValue.DimensionVal(15f, dp = null, sp = null))
  }

  @Test
  fun testResolveDimensions_InvalidFontScale() {
    val node =
      UiNode.ViewNode(
        id = 1L,
        className = "TextView",
        bounds = UiNode.Bounds(0, 0, 100, 100),
        idResource = "text_view",
        layoutResource = null,
        attributes = listOf(UiNode.Attribute(name = "textSize", value = UiNode.AttributeValue.DimensionVal(15f))),
      )

    val resolvedZeroFontScale = node.resolveDimensions(density = Dimension.Dpi(320), fontScale = 0.0f) as UiNode.ViewNode
    assertThat(resolvedZeroFontScale.attributes[0].value).isEqualTo(UiNode.AttributeValue.DimensionVal(15f, dp = null, sp = null))

    val resolvedNegativeFontScale = node.resolveDimensions(density = Dimension.Dpi(320), fontScale = -1.0f) as UiNode.ViewNode
    assertThat(resolvedNegativeFontScale.attributes[0].value).isEqualTo(UiNode.AttributeValue.DimensionVal(15f, dp = null, sp = null))
  }
}
