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

package com.android.tools.ui.inspector.inspectors.view.property

import android.view.View
import com.android.tools.ui.inspector.inspectors.view.StringTable
import com.android.tools.ui.inspector.inspectors.view.resolveResourceToString
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute
import java.util.Locale

/** Resolves the framework value and builds a simplified [Attribute] proto message. */
internal fun AttributeMetadata.toProtoAttribute(
  stringTable: StringTable,
  view: View,
  value: Any,
  sourceMap: Map<Int, Int>,
  includeResolutionStack: Boolean,
): Attribute? {
  val builder = Attribute.newBuilder().setName(stringTable.put(name))
  val isLayoutSize = name == "layout_width" || name == "layout_height"
  val isNegativeLayoutSize = isLayoutSize && value is Number && value.toInt() < 0

  val protoType =
    if (isLayoutSize && value is Number && !isNegativeLayoutSize) {
      // Platform companions map layout_width/height to INT_ENUM. For positive sizes, override this to DIMENSION.
      Attribute.Type.DIMENSION
    } else if (isNegativeLayoutSize) {
      // Keep negative sizes (e.g. -1, -2) mapped as INT_ENUM to support MATCH_PARENT and WRAP_CONTENT, in case the platform's enum mapping
      // failed to resolve them to strings (e.g. due to custom layout parameters or companions stripped by Proguard/R8).
      Attribute.Type.INT_ENUM
    } else {
      type.toProtoType()
    }
  builder.type = protoType

  // Populate value based on type
  when (protoType) {
    Attribute.Type.STRING -> {
      builder.int32Value = stringTable.put((value as? String) ?: value.toString())
    }
    Attribute.Type.BOOLEAN -> {
      val boolVal =
        when (value) {
          is Boolean -> value
          is Number -> value.toInt() != 0
          else -> value.toString().toBoolean()
        }
      builder.int32Value = if (boolVal) 1 else 0
    }
    Attribute.Type.INT32,
    Attribute.Type.INT16,
    Attribute.Type.BYTE,
    Attribute.Type.CHAR -> {
      builder.int32Value = (value as? Number)?.toInt() ?: (value as? Char)?.code ?: value.toString().toIntOrNull() ?: 0
    }
    Attribute.Type.INT64 -> {
      builder.int64Value = (value as? Number)?.toLong() ?: value.toString().toLongOrNull() ?: 0L
    }
    Attribute.Type.DOUBLE -> {
      builder.doubleValue = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull() ?: 0.0
    }
    Attribute.Type.FLOAT,
    Attribute.Type.DIMENSION -> {
      builder.floatValue = (value as? Number)?.toFloat() ?: value.toString().toFloatOrNull() ?: 0f
    }
    Attribute.Type.COLOR -> {
      builder.int32Value = (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: 0
    }
    Attribute.Type.INT_ENUM -> {
      val stringVal =
        when {
          value is String -> value
          isNegativeLayoutSize -> {
            when ((value as? Number)?.toInt()) {
              -1 -> "MATCH_PARENT"
              -2 -> "WRAP_CONTENT"
              else -> value.toString()
            }
          }
          else -> value.toString()
        }
      builder.int32Value = stringTable.put(stringVal)
    }
    Attribute.Type.GRAVITY,
    Attribute.Type.INT_FLAG -> {
      val flagsString = (value as? Set<*>)?.joinToString("|") ?: value.toString()
      builder.int32Value = stringTable.put(flagsString)
    }
    Attribute.Type.RESOURCE -> {
      val resourceString =
        if (value is Int) {
          view.resolveResourceToString(value) ?: String.format(Locale.US, "0x%08X", value)
        } else {
          value.toString()
        }
      builder.int32Value = stringTable.put(resourceString)
    }
    Attribute.Type.DRAWABLE,
    Attribute.Type.ANIM,
    Attribute.Type.ANIMATOR,
    Attribute.Type.INTERPOLATOR -> {
      builder.int32Value = stringTable.put(value.javaClass.name)
    }
    else -> {
      builder.int32Value = stringTable.put(value.toString())
    }
  }

  // Set direct source if present in the map
  sourceMap[attributeId]?.let { sourceResId ->
    view.resolveResourceToString(sourceResId)?.let { resourceStr -> builder.setDirectSource(stringTable.put(resourceStr)) }
  }

  if (includeResolutionStack) {
    // Requires debug_view_attributes flag to be enabled on the device to return non-empty stacks.
    val stack = view.getAttributeResolutionStack(attributeId)
    for (resId in stack) {
      view.resolveResourceToString(resId)?.let { resourceStr -> builder.addStyleChain(stringTable.put(resourceStr)) }
    }
  }

  return builder.build()
}

private fun PropertyType.toProtoType(): Attribute.Type {
  return when (this) {
    PropertyType.UNSPECIFIED -> Attribute.Type.UNSPECIFIED
    PropertyType.STRING -> Attribute.Type.STRING
    PropertyType.BOOLEAN -> Attribute.Type.BOOLEAN
    PropertyType.BYTE -> Attribute.Type.BYTE
    PropertyType.CHAR -> Attribute.Type.CHAR
    PropertyType.DOUBLE -> Attribute.Type.DOUBLE
    PropertyType.FLOAT -> Attribute.Type.FLOAT
    PropertyType.INT16 -> Attribute.Type.INT16
    PropertyType.INT32 -> Attribute.Type.INT32
    PropertyType.INT64 -> Attribute.Type.INT64
    PropertyType.OBJECT -> Attribute.Type.OBJECT
    PropertyType.COLOR -> Attribute.Type.COLOR
    PropertyType.GRAVITY -> Attribute.Type.GRAVITY
    PropertyType.INT_ENUM -> Attribute.Type.INT_ENUM
    PropertyType.INT_FLAG -> Attribute.Type.INT_FLAG
    PropertyType.RESOURCE -> Attribute.Type.RESOURCE
    PropertyType.DRAWABLE -> Attribute.Type.DRAWABLE
    PropertyType.ANIM -> Attribute.Type.ANIM
    PropertyType.ANIMATOR -> Attribute.Type.ANIMATOR
    PropertyType.INTERPOLATOR -> Attribute.Type.INTERPOLATOR
    PropertyType.DIMENSION -> Attribute.Type.DIMENSION
  }
}
