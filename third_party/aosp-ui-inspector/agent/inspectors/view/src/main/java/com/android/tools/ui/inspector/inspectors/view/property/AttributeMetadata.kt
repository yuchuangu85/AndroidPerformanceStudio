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

import java.util.function.IntFunction

/**
 * Categorizes property types read from the Android framework.
 *
 * Used to guide dynamic type formatting in the proto layer, determining how raw values like colors, flags, and resource IDs are resolved to
 * strings.
 */
internal enum class PropertyType {
  UNSPECIFIED,
  STRING,
  BOOLEAN,
  BYTE,
  CHAR,
  DOUBLE,
  FLOAT,
  INT16,
  INT32,
  INT64,
  OBJECT,
  COLOR,
  GRAVITY,
  INT_ENUM,
  INT_FLAG,
  RESOURCE,
  DRAWABLE,
  ANIM,
  ANIMATOR,
  INTERPOLATOR,
  DIMENSION,
}

/** Metadata associated with an attribute. */
internal class AttributeMetadata(
  /** The name of the property (e.g., `"visibility"`). */
  val name: String,
  /** The sequential index ID assigned by the mapper to this property. */
  val attributeId: Int,
  /** The categorized type of the property, guiding its formatting. */
  val type: PropertyType,
  /** Optional mapping function used to resolve raw integer enum values into human-readable strings. */
  val enumMapping: IntFunction<String>? = null,
  /** Optional mapping function used to resolve bitmask integer values into sets of string flags. */
  val flagMapping: IntFunction<@JvmSuppressWildcards Set<String>>? = null,
)
