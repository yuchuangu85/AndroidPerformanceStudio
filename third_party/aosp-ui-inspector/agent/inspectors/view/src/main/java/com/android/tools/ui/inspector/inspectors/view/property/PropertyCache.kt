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
import android.view.ViewGroup
import android.view.inspector.InspectionCompanion
import android.view.inspector.StaticInspectionCompanionProvider

/**
 * A cache that dynamically loads Android property metadata and inspection companions using reflection, then caches them to prevent
 * expensive runtime reflection lookups during view hierarchy dumps.
 *
 * Loads companions lazily using the platform's companion provider the first time a class type is encountered, caching them to enable O(1)
 * lookups during recursive view hierarchy traversals.
 *
 * @param rootClass The base [Class] instance (e.g., [View] or [ViewGroup.LayoutParams]) used as the stop condition when recursively
 *   resolving inherited properties up the class hierarchy.
 */
internal class PropertyCache<T : Any> private constructor(private val rootClass: Class<T>) {

  /**
   * Container holding cached property data for a specific class type.
   *
   * @property properties The resolved list of [AttributeMetadata]s for this class type.
   * @property companions The active list of [InspectionCompanion]s used to read properties.
   */
  internal data class PropertyData<T : Any>(val properties: List<AttributeMetadata>, val companions: List<InspectionCompanion<T>>)

  companion object {
    /** Creates a new cache instance for resolving standard and custom [View] hierarchy properties. */
    fun createViewPropertyCache() = PropertyCache(View::class.java)

    /** Creates a new cache instance for resolving layout-specific [ViewGroup.LayoutParams] properties. */
    fun createLayoutParamsPropertyCache() = PropertyCache(ViewGroup.LayoutParams::class.java)
  }

  /** Framework provider used to lazily load [InspectionCompanion]s via reflection based on class names. */
  private val provider = StaticInspectionCompanionProvider()
  /** Internal map caching resolved [PropertyData] against specific class types to prevent redundant reflection. */
  private val cache = mutableMapOf<Class<*>, PropertyData<T>>()

  /** Retrieves or builds the cached property data for the given [inspectable] instance. */
  fun getOrResolve(inspectable: T): PropertyData<T> {
    return getImpl(inspectable::class.java)
  }

  /**
   * Internal helper recursively walking up the class hierarchy to load, map, and flatly compile property metadata and companions, caching
   * the results to ensure subsequent lookups are O(1).
   */
  private fun getImpl(inspectable: Class<*>): PropertyData<T> {
    val cachedType = cache[inspectable]
    if (cachedType != null) {
      return cachedType
    }

    val superTypePropertyData =
      if (inspectable != rootClass) {
        getImpl(inspectable.superclass)
      } else {
        null
      }

    val companion = loadInspectionCompanion(inspectable)
    val companions = mutableListOf<InspectionCompanion<T>>()
    if (superTypePropertyData != null) {
      companions.addAll(superTypePropertyData.companions)
    }
    if (companion != null) {
      companions.add(companion)
    }

    val baseProperties = superTypePropertyData?.properties ?: emptyList()
    val properties =
      if (companion != null) {
        val mapper = PropertyTypeMapper(baseProperties)
        companion.mapProperties(mapper)
        mapper.properties
      } else {
        baseProperties
      }

    val type = PropertyData(properties, companions)
    cache[inspectable] = type

    return type
  }

  private fun loadInspectionCompanion(javaClass: Class<*>): InspectionCompanion<T>? {
    return provider.provide(javaClass) as? InspectionCompanion<T>
  }
}
