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
import android.view.View
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PropertyCacheTest {

  @Test
  fun testViewPropertyCache() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val viewCache = PropertyCache.createViewPropertyCache()

    // 1. Verify first lookup compiles metadata successfully
    val view = View(activity)
    val viewData = viewCache.getOrResolve(view)
    assertThat(viewData).isNotNull()
    assertThat(viewData.properties).isNotEmpty()
    assertThat(viewData.companions).isNotEmpty()

    // Verify standard View properties are mapped correctly
    val alphaProp = viewData.properties.firstOrNull { it.name == "alpha" }
    assertThat(alphaProp).isNotNull()
    assertThat(alphaProp!!.type).isEqualTo(PropertyType.FLOAT)

    // 2. Verify caching (memoization) returns the exact same instance in O(1)
    val secondViewData = viewCache.getOrResolve(view)
    assertThat(secondViewData).isSameAs(viewData)

    // 3. Verify inheritance walk (TextView compiles View properties + TextView properties)
    val textView = TextView(activity)
    val textViewData = viewCache.getOrResolve(textView)
    assertThat(textViewData).isNotNull()
    assertThat(textViewData.properties.size).isGreaterThan(viewData.properties.size)

    // Verify TextView specific properties are resolved correctly.
    // Note: "text" is registered as PropertyType.OBJECT because getText() returns CharSequence.
    val textProp = textViewData.properties.firstOrNull { it.name == "text" }
    assertThat(textProp).isNotNull()
    assertThat(textProp!!.type).isEqualTo(PropertyType.OBJECT)
  }
}
