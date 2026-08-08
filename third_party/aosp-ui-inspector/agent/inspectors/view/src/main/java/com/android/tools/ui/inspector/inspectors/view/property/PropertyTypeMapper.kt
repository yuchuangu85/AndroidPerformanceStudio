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

import android.view.inspector.PropertyMapper
import com.android.tools.ui.inspector.inspectors.view.GravityIntMapping
import java.util.function.IntFunction

/**
 * An implementation of the Android SDK framework's [PropertyMapper] interface.
 *
 * Used by framework inspection companions to map property names to sequential integer IDs. It accumulates property metadata (types, enums,
 * and flags) into a list of [AttributeMetadata].
 */
internal class PropertyTypeMapper(existingProperties: List<AttributeMetadata>) : PropertyMapper {

  private val mutableProperties = existingProperties.toMutableList()

  val properties: List<AttributeMetadata>
    get() = mutableProperties

  override fun mapBoolean(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.BOOLEAN)

  override fun mapByte(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.BYTE)

  override fun mapChar(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.CHAR)

  override fun mapDouble(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.DOUBLE)

  override fun mapFloat(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.FLOAT)

  override fun mapInt(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.INT32)

  override fun mapLong(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.INT64)

  override fun mapShort(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.INT16)

  override fun mapObject(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.OBJECT)

  override fun mapColor(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.COLOR)

  override fun mapResourceId(name: String, attributeId: Int): Int = map(name, attributeId, PropertyType.RESOURCE)

  override fun mapGravity(name: String, attributeId: Int): Int =
    map(name, attributeId, PropertyType.GRAVITY, flagMapping = GravityIntMapping())

  override fun mapIntEnum(name: String, attributeId: Int, mapping: IntFunction<String>): Int =
    map(name, attributeId, PropertyType.INT_ENUM, enumMapping = mapping)

  override fun mapIntFlag(name: String, attributeId: Int, mapping: IntFunction<@JvmSuppressWildcards Set<String>>): Int =
    map(name, attributeId, PropertyType.INT_FLAG, flagMapping = mapping)

  /**
   * Internal helper that instantiates [AttributeMetadata], registers it in the accumulated list, and returns its sequential index position
   * representing its mapped unique ID.
   */
  private fun map(
    name: String,
    attributeId: Int,
    type: PropertyType,
    enumMapping: IntFunction<String>? = null,
    flagMapping: IntFunction<@JvmSuppressWildcards Set<String>>? = null,
  ): Int {
    val id = mutableProperties.size
    val metadata = AttributeMetadata(name, attributeId, type, enumMapping, flagMapping)
    mutableProperties.add(metadata)
    return id
  }
}
