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

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.android.tools.ui.inspector.inspectors.view.property.PropertyCache
import com.android.tools.ui.inspector.inspectors.view.property.ProtoAttributeReader
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.AppContext
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Display
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute

/** Models the configuration for attribute extraction. */
internal sealed class AttributeExtraction {
  data class Enabled(
    val propertyCache: PropertyCache<View>,
    val layoutParamsPropertyCache: PropertyCache<ViewGroup.LayoutParams>,
    val includeResolutionStack: Boolean,
  ) : AttributeExtraction()

  object Disabled : AttributeExtraction()
}

/** Flattens a view hierarchy into a [ViewNode] proto. */
internal fun View.toViewNode(
  stringTable: StringTable,
  includeAttributes: Boolean = true,
  includeResolutionStack: Boolean = false,
): ViewNode {
  val attributeExtraction =
    if (includeAttributes || includeResolutionStack) {
      AttributeExtraction.Enabled(
        PropertyCache.createViewPropertyCache(),
        PropertyCache.createLayoutParamsPropertyCache(),
        includeResolutionStack,
      )
    } else {
      AttributeExtraction.Disabled
    }
  return createViewNode(view = this, stringTable = stringTable, attributeExtraction = attributeExtraction).build()
}

/**
 * Internal recursive implementation to flatten the view hierarchy.
 *
 * Returns a [ViewNode.Builder] to allow the parent to add it directly to its children list without eager building, optimizing memory
 * allocations during traversal.
 */
private fun createViewNode(view: View, stringTable: StringTable, attributeExtraction: AttributeExtraction): ViewNode.Builder {
  val viewClass = view::class.java

  val location = IntArray(2)
  view.getLocationOnScreen(location)
  val absPosX = location[0]
  val absPosY = location[1]

  return ViewNode.newBuilder().apply {
    id = view.uniqueDrawingId
    val name = viewClass.simpleName.ifEmpty { viewClass.name.substringAfterLast('.') }
    className = stringTable.put(name)
    val pkg = viewClass.`package`
    if (pkg != null) {
      packageName = stringTable.put(pkg.name)
    }

    bounds =
      Rect.newBuilder()
        .apply {
          x = absPosX
          y = absPosY
          width = view.width
          height = view.height
        }
        .build()

    // Create and set view id resource
    val res = view.resolveResourceToString(view.id)
    if (res != null) {
      idResource = stringTable.put(res)
    }

    // Create and set source layout id
    val layoutRes = view.resolveResourceToString(view.sourceLayoutResId)
    if (layoutRes != null) {
      layoutResource = stringTable.put(layoutRes)
    }

    when (attributeExtraction) {
      is AttributeExtraction.Enabled ->
        populateAttributes(
          this,
          view,
          stringTable,
          attributeExtraction.propertyCache,
          attributeExtraction.layoutParamsPropertyCache,
          attributeExtraction.includeResolutionStack,
        )
      AttributeExtraction.Disabled -> {}
    }

    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        addChildren(createViewNode(view = view.getChildAt(i), stringTable = stringTable, attributeExtraction = attributeExtraction))
      }
    }
  }
}

/**
 * Resolves and populates attributes into the [ViewNode.Builder].
 *
 * Traverses the View hierarchy recursively using the [PropertyCache] and writes mapped values as simplified [ViewNode.Attribute] key-value
 * string pairs.
 */
private fun populateAttributes(
  viewNodeBuilder: ViewNode.Builder,
  view: View,
  stringTable: StringTable,
  viewPropertyCache: PropertyCache<View>,
  layoutParamsPropertyCache: PropertyCache<ViewGroup.LayoutParams>,
  includeResolutionStack: Boolean,
) {
  view.forEachProtoAttribute(viewPropertyCache.getOrResolve(view), stringTable, includeResolutionStack, viewNodeBuilder::addAttributes)

  val layoutParams = view.layoutParams
  if (layoutParams != null) {
    val layoutParamsPropertyData = layoutParamsPropertyCache.getOrResolve(layoutParams)
    val reader =
      ProtoAttributeReader(
        view = view,
        properties = layoutParamsPropertyData.properties,
        stringTable = stringTable,
        includeResolutionStack = includeResolutionStack,
        onAttributeResolved = viewNodeBuilder::addAttributes,
      )
    for (companion in layoutParamsPropertyData.companions) {
      companion.readProperties(layoutParams, reader)
    }
  }
}

/** Resolves and iterates over Android framework properties for this view, converting them into Proto [Attribute] messages. */
private fun View.forEachProtoAttribute(
  propertyData: PropertyCache.PropertyData<View>,
  stringTable: StringTable,
  includeResolutionStack: Boolean,
  onAttributeResolved: (Attribute) -> Unit,
) {
  val reader = ProtoAttributeReader(this, propertyData.properties, stringTable, includeResolutionStack, onAttributeResolved)
  for (companion in propertyData.companions) {
    companion.readProperties(this, reader)
  }
}

/** Resolves a framework resource ID directly into its string representation (e.g., `"@id/my_view"`). */
internal fun View.resolveResourceToString(resourceId: Int): String? {
  if (!isValidResourceId(resourceId)) {
    return null
  }
  return try {
    val type = resources.getResourceTypeName(resourceId)
    val pkg = resources.getResourcePackageName(resourceId)
    val name = resources.getResourceEntryName(resourceId)
    if (pkg == "android") {
      "@android:$type/$name"
    } else if (pkg == context.packageName) {
      "@$type/$name"
    } else {
      "@$pkg:$type/$name"
    }
  } catch (ex: Resources.NotFoundException) {
    null
  }
}

/**
 * Performs a fast check to determine if a resource ID is potentially valid.
 *
 * This is a performance optimization to avoid calling expensive resource resolution APIs (which throw [Resources.NotFoundException] and
 * spam logcat for invalid IDs) for views that don't have IDs.
 *
 * All valid Android resource IDs are positive integers.
 *
 * Note: We use strict bitwise checks similar to Layout Inspector to filter out positive integers that are not valid resource IDs,
 * preventing logcat spam (see b/299309384 and b/311414906).
 */
@VisibleForTesting
internal fun isValidResourceId(resourceId: Int): Boolean {
  if (resourceId <= 0) return false

  // A valid resource ID is structured as 0xPPTTEEEE where:
  // PP: Package ID, TT: Type ID, EEEE: Entry ID.
  val packageId = (resourceId shr 24) and 0xFF
  val typeId = (resourceId shr 16) and 0xFF

  // Both package and type must be non-zero.
  // Package ID 0xFF is disallowed in AssetManager2.
  return packageId != 0 && packageId != 0xFF && typeId != 0
}

internal fun View.createAppContext(stringTable: StringTable): AppContext {
  val displayInfo = buildDisplayInfo(context)
  return AppContext.newBuilder()
    .apply {
      val themeResId = context.getThemeResIdReflective()
      resolveResourceToString(themeResId)?.let { themeStr -> theme = stringTable.put(themeStr) }
      addAllDisplayInfo(displayInfo)
    }
    .build()
}

/**
 * Reflectively retrieves the theme resource ID from the [Context]. Accesses the hidden `Context.getThemeResId()` framework API reflectively
 * to avoid adding a compile time dependency to fake-android like layout inspector does.
 */
private fun Context.getThemeResIdReflective(): Int {
  return try {
    // Fast-path: Search the runtime class (e.g. Activity, ContextThemeWrapper) which exposes it as public in API 29+.
    val method = this.javaClass.getMethod("getThemeResId")
    method.invoke(this) as Int
  } catch (e: Exception) {
    try {
      // Fallback: Search the base Context class for the hidden method (safely silenced by our native JVMTI agent at runtime).
      val method = Context::class.java.getDeclaredMethod("getThemeResId")
      method.isAccessible = true
      method.invoke(this) as Int
    } catch (ex: Exception) {
      0
    }
  }
}

private fun buildDisplayInfo(context: Context): List<Display> {
  val displayManager = context.getSystemService(DisplayManager::class.java)
  val displays = displayManager?.displays ?: return emptyList()
  return displays.map { display ->
    val mode = display.mode
    Display.newBuilder()
      .apply {
        id = display.displayId
        orientation =
          when (display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> -1
          }
        widthPx = mode.physicalWidth
        heightPx = mode.physicalHeight
      }
      .build()
  }
}
