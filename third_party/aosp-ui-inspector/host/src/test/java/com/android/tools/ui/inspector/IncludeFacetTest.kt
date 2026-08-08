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

class IncludeFacetTest {

  @Test
  fun testExpandIncludeFacetsEmpty() {
    assertThat(expandIncludeFacets(emptyList())).isEmpty()
  }

  @Test
  fun testExpandIncludeFacetsAllBecomesConcreteFacets() {
    val expanded = expandIncludeFacets(listOf(IncludeFacet.ALL))
    assertThat(expanded)
      .containsExactly(IncludeFacet.ATTRIBUTES, IncludeFacet.SEMANTICS, IncludeFacet.RESOLUTION_STACK, IncludeFacet.SYSTEM_COMPOSABLES)
  }

  @Test
  fun testExpandIncludeFacetsResolutionStackImpliesAttributes() {
    assertThat(expandIncludeFacets(listOf(IncludeFacet.RESOLUTION_STACK)))
      .containsExactly(IncludeFacet.RESOLUTION_STACK, IncludeFacet.ATTRIBUTES)
  }

  @Test
  fun testExpandIncludeFacetsIsIdempotentForAllPlusDuplicates() {
    val expanded = expandIncludeFacets(listOf(IncludeFacet.ALL, IncludeFacet.SEMANTICS, IncludeFacet.SEMANTICS))
    assertThat(expanded)
      .containsExactly(IncludeFacet.ATTRIBUTES, IncludeFacet.SEMANTICS, IncludeFacet.RESOLUTION_STACK, IncludeFacet.SYSTEM_COMPOSABLES)
  }

  @Test
  fun testIncludeFacetConverterTrimsWhitespace() {
    val converter = IncludeFacetConverter()
    assertThat(converter.convert("  semantics ")).isEqualTo(IncludeFacet.SEMANTICS)
    assertThat(converter.convert("\tattributes\n")).isEqualTo(IncludeFacet.ATTRIBUTES)
  }
}
