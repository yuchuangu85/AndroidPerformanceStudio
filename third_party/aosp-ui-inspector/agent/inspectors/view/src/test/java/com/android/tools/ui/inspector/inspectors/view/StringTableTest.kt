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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StringTableTest {

  @Test
  fun testPut_addsNewString() {
    val table = StringTable()
    val id = table.put("hello")
    assertThat(id).isEqualTo(1)
  }

  @Test
  fun testPut_deduplicatesStrings() {
    val table = StringTable()
    val id1 = table.put("hello")
    val id2 = table.put("hello")
    assertThat(id1).isEqualTo(id2)
    assertThat(id1).isEqualTo(1)
  }

  @Test
  fun testPut_emptyStringReturnsZero() {
    val table = StringTable()
    val id = table.put("")
    assertThat(id).isEqualTo(0)
  }

  @Test
  fun testPut_distinctStringsGetDifferentIds() {
    val table = StringTable()
    val id1 = table.put("hello")
    val id2 = table.put("world")
    assertThat(id1).isNotEqualTo(id2)
    assertThat(id1).isEqualTo(1)
    assertThat(id2).isEqualTo(2)
  }

  @Test
  fun testToStringEntries_returnsAllEntries() {
    val table = StringTable()
    table.put("hello")
    table.put("world")

    val entries = table.toStringEntries()
    assertThat(entries).hasSize(2)

    val map = entries.associate { it.id to it.value }
    assertThat(map[1]).isEqualTo("hello")
    assertThat(map[2]).isEqualTo("world")
  }

  @Test
  fun testToStringEntries_emptyTableReturnsEmptyList() {
    val table = StringTable()
    val entries = table.toStringEntries()
    assertThat(entries).isEmpty()
  }

  @Test
  fun testGetString_zeroIdReturnsEmptyString() {
    val table = StringTable()
    assertThat(table.getString(0)).isEqualTo("")
  }

  @Test
  fun testGetString_validIdReturnsCorrectString() {
    val table = StringTable()
    val id = table.put("hello")
    assertThat(table.getString(id)).isEqualTo("hello")
  }

  @Test
  fun testGetString_invalidIdReturnsNull() {
    val table = StringTable()
    assertThat(table.getString(999)).isNull()
  }
}
