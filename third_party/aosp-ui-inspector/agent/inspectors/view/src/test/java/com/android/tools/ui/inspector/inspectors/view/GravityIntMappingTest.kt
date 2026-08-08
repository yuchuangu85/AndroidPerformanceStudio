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

import android.view.Gravity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GravityIntMappingTest {

  private val mapping = GravityIntMapping()

  @Test
  fun testApply_fill() {
    val flags = mapping.apply(Gravity.FILL)
    assertThat(flags).containsExactly("fill", "fill_vertical", "fill_horizontal")
  }

  @Test
  fun testApply_topStart() {
    val flags = mapping.apply(Gravity.TOP or Gravity.START)
    assertThat(flags).containsExactly("top", "start")
  }

  @Test
  fun testApply_bottomEnd() {
    val flags = mapping.apply(Gravity.BOTTOM or Gravity.END)
    assertThat(flags).containsExactly("bottom", "end")
  }

  @Test
  fun testApply_center() {
    val flags = mapping.apply(Gravity.CENTER)
    assertThat(flags).containsExactly("center", "center_vertical", "center_horizontal")
  }

  @Test
  fun testApply_absoluteLeftRight() {
    val flags = mapping.apply(Gravity.LEFT or Gravity.RIGHT)
    assertThat(flags).containsExactly("fill_horizontal")
  }
}
