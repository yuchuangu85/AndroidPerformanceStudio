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

/** Base class representing any node in the unified layout tree. */
sealed class UiNode {
  abstract val id: Long
  abstract val className: String
  abstract val bounds: Bounds
  abstract val children: MutableList<UiNode>

  data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

  /** Represents a strongly-typed value for a [ViewNode] attribute. */
  sealed class AttributeValue {
    data class StringVal(val value: String) : AttributeValue()

    data class BooleanVal(val value: Boolean) : AttributeValue()

    data class NumberVal(val value: Number) : AttributeValue()

    data class ColorVal(val colorInt: Int) : AttributeValue()

    data class DimensionVal(val value: Float, val dp: Float? = null, val sp: Float? = null) : AttributeValue()

    object NullVal : AttributeValue()
  }

  data class Attribute(
    val name: String,
    val value: AttributeValue,
    val directSource: String? = null,
    val styleChain: List<String> = emptyList(),
  )

  /** Represents the location in the source code where a layout node is defined. */
  data class SourceLocation(val filename: String, val lineNumber: Int)

  /** Represents a standard Android View node. */
  data class ViewNode(
    override val id: Long,
    override val className: String,
    override val bounds: Bounds,
    override val children: MutableList<UiNode> = mutableListOf(),
    val idResource: String?,
    val layoutResource: String?,
    val attributes: List<Attribute>,
  ) : UiNode()

  /** Represents a Jetpack Compose Composable parameter. */
  sealed class ComposeParameter {
    abstract val name: String

    /** A leaf parameter representing a strongly-typed value. */
    data class Single(override val name: String, val value: Value) : ComposeParameter()

    /** A nested group of parameters representing lists or objects. */
    data class Group(override val name: String, val elements: List<ComposeParameter>, val isCollection: Boolean) : ComposeParameter()

    /** Domain values that preserve original data and type information. */
    sealed class Value {
      data class StringVal(val value: String) : Value()

      data class BooleanVal(val value: Boolean) : Value()

      data class NumberVal(val value: Number) : Value()

      data class DimensionVal(val value: Float, val unit: DimensionUnit) : Value()

      data class ColorVal(val colorInt: Int) : Value()

      data class ResourceVal(val namespace: String?, val type: String?, val name: String) : Value()

      data class LambdaVal(val fileName: String?, val startLineNumber: Int?) : Value()

      object NullVal : Value()
    }

    enum class DimensionUnit {
      DP,
      SP,
      EM,
    }
  }

  /**
   * Represents a Jetpack Compose Composable node.
   *
   * @param parameters Standard Composable properties and function parameters.
   * @param mergedSemantics Accessibility properties that aggregate all readable texts and actions from this node's entire subtree into one
   *   focusable block (which is what a screen reader like TalkBack speaks).
   * @param unmergedSemantics Accessibility properties declared directly on this Composable node.
   */
  data class ComposeNode(
    override val id: Long,
    override val className: String,
    override val bounds: Bounds,
    override val children: MutableList<UiNode> = mutableListOf(),
    val sourceLocation: SourceLocation? = null,
    val parameters: List<ComposeParameter>,
    val mergedSemantics: List<ComposeParameter>,
    val unmergedSemantics: List<ComposeParameter>,
  ) : UiNode()
}
