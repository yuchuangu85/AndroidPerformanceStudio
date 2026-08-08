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

import android.app.Activity
import android.graphics.Color
import android.view.View
import com.android.tools.ui.inspector.inspectors.view.StringTable
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProtoAttributeReaderTest {

  @Test
  fun testReadBoolean() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata = AttributeMetadata("clickable", 0, PropertyType.BOOLEAN)
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readBoolean(0, true)

    assertThat(resolved).hasSize(1)
    assertThat(stringTable.getString(resolved[0].name)).isEqualTo("clickable")
    assertThat(resolved[0].type).isEqualTo(Attribute.Type.BOOLEAN)
    assertThat(resolved[0].int32Value).isEqualTo(1)
  }

  @Test
  fun testReadInt() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata = AttributeMetadata("width", 0, PropertyType.INT32)
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readInt(0, 100)

    assertThat(resolved).hasSize(1)
    assertThat(resolved[0].type).isEqualTo(Attribute.Type.INT32)
    assertThat(resolved[0].int32Value).isEqualTo(100)
  }

  @Test
  fun testReadColor_resolvesToHex() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata = AttributeMetadata("textColor", 0, PropertyType.COLOR)
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readColor(0, 0xFFFF0000.toInt())

    assertThat(resolved).hasSize(1)
    assertThat(resolved[0].type).isEqualTo(Attribute.Type.COLOR)
    assertThat(resolved[0].int32Value).isEqualTo(0xFFFF0000.toInt())
  }

  @Test
  fun testReadIntEnum_resolvesWithMapping() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata =
      AttributeMetadata("visibility", 0, PropertyType.INT_ENUM, enumMapping = { value -> if (value == 0) "VISIBLE" else "INVISIBLE" })
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readIntEnum(0, 0)

    assertThat(resolved).hasSize(1)
    assertThat(resolved[0].type).isEqualTo(Attribute.Type.INT_ENUM)
    assertThat(stringTable.getString(resolved[0].int32Value)).isEqualTo("VISIBLE")
  }

  @Test
  fun testReadIntFlag_resolvesWithMapping() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata =
      AttributeMetadata("flags", 0, PropertyType.INT_FLAG, flagMapping = { value -> if (value and 1 != 0) setOf("flag1") else emptySet() })
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readIntFlag(0, 1)

    assertThat(resolved).hasSize(1)
    assertThat(resolved[0].type).isEqualTo(Attribute.Type.INT_FLAG)
    assertThat(stringTable.getString(resolved[0].int32Value)).isEqualTo("flag1")
  }

  @Test
  fun testEmit_ignoresNullValues() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata = AttributeMetadata("text", 0, PropertyType.STRING)
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readObject(0, null)

    assertThat(resolved).isEmpty()
  }

  @Test
  fun testReadColor_colorLong() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata = AttributeMetadata("textColor", 0, PropertyType.COLOR)
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readColor(0, Color.pack(0xFFFF0000.toInt()))

    assertThat(resolved).hasSize(1)
    assertThat(resolved[0].type).isEqualTo(Attribute.Type.COLOR)
    assertThat(resolved[0].int32Value).isEqualTo(0xFFFF0000.toInt())
  }

  @Test
  fun testReadColor_colorObject() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()
    val resolved = mutableListOf<Attribute>()

    val metadata = AttributeMetadata("textColor", 0, PropertyType.COLOR)
    val reader = ProtoAttributeReader(view, listOf(metadata), stringTable) { resolved.add(it) }

    reader.readColor(0, Color.valueOf(0xFFFF0000.toInt()))

    assertThat(resolved).hasSize(1)
    assertThat(resolved[0].type).isEqualTo(Attribute.Type.COLOR)
    assertThat(resolved[0].int32Value).isEqualTo(0xFFFF0000.toInt())
  }
}
