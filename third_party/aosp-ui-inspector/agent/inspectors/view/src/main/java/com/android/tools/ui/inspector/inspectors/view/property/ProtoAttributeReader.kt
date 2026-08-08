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

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.inspector.PropertyReader
import com.android.tools.ui.inspector.inspectors.view.StringTable
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute

/**
 * An implementation of the Android SDK framework's [PropertyReader] interface.
 *
 * Resolves and streams property values from framework companions directly to Attribute proto, avoiding intermediate object allocations.
 *
 * @param view The target [View] instance from which properties are being read.
 * @param propertyData The container holding the properties metadata list and inspection companions.
 * @param stringTable The lookup table used to deduplicate and intern attribute names and values.
 * @param includeResolutionStack Whether to include the attribute resolution stack. Requires debug_view_attributes flag to be enabled on the
 *   device.
 * @param onAttributeResolved Callback invoked when a property is successfully resolved and converted to a [Attribute] proto.
 */
internal class ProtoAttributeReader(
  private val view: View,
  private val properties: List<AttributeMetadata>,
  private val stringTable: StringTable,
  private val includeResolutionStack: Boolean = false,
  private val onAttributeResolved: (Attribute) -> Unit,
) : PropertyReader {

  /**
   * Cached copy of the view's attribute source resource map. We cache it here to avoid calling the expensive framework method
   * `View.getAttributeSourceResourceMap()` (which creates a new map on every call) for every property.
   */
  private val resourceMap: Map<Int, Int> = view.attributeSourceResourceMap

  override fun readBoolean(id: Int, b: Boolean) {
    emit(id, b)
  }

  override fun readByte(id: Int, b: Byte) {
    emit(id, b.toInt())
  }

  override fun readChar(id: Int, c: Char) {
    emit(id, c.toInt())
  }

  override fun readDouble(id: Int, d: Double) {
    emit(id, d)
  }

  override fun readFloat(id: Int, f: Float) {
    emit(id, f)
  }

  override fun readInt(id: Int, i: Int) {
    emit(id, i)
  }

  override fun readLong(id: Int, l: Long) {
    emit(id, l)
  }

  override fun readShort(id: Int, s: Short) {
    emit(id, s.toInt())
  }

  override fun readObject(id: Int, o: Any?) {
    when (o) {
      is ColorStateList -> emit(id, o.getColorForState(view.drawableState, o.defaultColor))
      is ColorDrawable -> emit(id, o.color)
      else -> emit(id, o)
    }
  }

  override fun readColor(id: Int, color: Int) {
    emit(id, color)
  }

  override fun readColor(id: Int, color: Long) {
    emit(id, Color.toArgb(color))
  }

  override fun readColor(id: Int, color: Color?) {
    emit(id, color?.toArgb())
  }

  override fun readGravity(id: Int, value: Int) {
    readIntFlag(id, value)
  }

  override fun readIntEnum(id: Int, value: Int) {
    val metadata = properties.getOrNull(id) ?: return
    val mappedValue = metadata.enumMapping?.apply(value)
    emit(id, mappedValue ?: value)
  }

  override fun readIntFlag(id: Int, value: Int) {
    val metadata = properties.getOrNull(id) ?: return
    metadata.flagMapping?.let { mapping -> emit(id, mapping.apply(value)) }
  }

  override fun readResourceId(id: Int, value: Int) {
    emit(id, value)
  }

  private fun emit(id: Int, value: Any?) {
    if (value == null) return
    val metadata = properties.getOrNull(id) ?: return
    val protoAttribute = metadata.toProtoAttribute(stringTable, view, value, resourceMap, includeResolutionStack)
    if (protoAttribute != null) {
      onAttributeResolved(protoAttribute)
    }
  }
}
