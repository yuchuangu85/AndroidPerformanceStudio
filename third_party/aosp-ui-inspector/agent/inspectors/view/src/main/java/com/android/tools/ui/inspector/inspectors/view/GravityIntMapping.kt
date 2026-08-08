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
import android.view.inspector.IntFlagMapping
import java.util.Collections
import java.util.function.IntFunction

/** A functional mapper ([IntFunction]) that decodes packed [Gravity] integer bitmasks into sets of human-readable string tokens. */
class GravityIntMapping : IntFunction<@JvmSuppressWildcards Set<String>> {
  /**
   * Framework standard mapping utility used to decode packed gravity integer bitmasks into sets of human-readable string flags (e.g.,
   * "top", "start").
   */
  private val gravityIntFlagMapping = IntFlagMapping()

  init {
    gravityIntFlagMapping.add(Gravity.FILL, Gravity.FILL, "fill")

    gravityIntFlagMapping.add(Gravity.FILL_VERTICAL, Gravity.FILL_VERTICAL, "fill_vertical")
    gravityIntFlagMapping.add(Gravity.FILL_VERTICAL, Gravity.TOP, "top")
    gravityIntFlagMapping.add(Gravity.FILL_VERTICAL, Gravity.BOTTOM, "bottom")

    gravityIntFlagMapping.add(Gravity.FILL_HORIZONTAL, Gravity.FILL_HORIZONTAL, "fill_horizontal")
    gravityIntFlagMapping.add(Gravity.FILL_HORIZONTAL, Gravity.LEFT, "left")
    gravityIntFlagMapping.add(Gravity.FILL_HORIZONTAL, Gravity.RIGHT, "right")

    gravityIntFlagMapping.add(Gravity.FILL, Gravity.CENTER, "center")
    gravityIntFlagMapping.add(Gravity.FILL_VERTICAL, Gravity.CENTER_VERTICAL, "center_vertical")
    gravityIntFlagMapping.add(Gravity.FILL_HORIZONTAL, Gravity.CENTER_HORIZONTAL, "center_horizontal")

    gravityIntFlagMapping.add(Gravity.CLIP_VERTICAL, Gravity.CLIP_VERTICAL, "clip_vertical")
    gravityIntFlagMapping.add(Gravity.CLIP_HORIZONTAL, Gravity.CLIP_HORIZONTAL, "clip_horizontal")
  }

  override fun apply(value: Int): Set<String> {
    var values = gravityIntFlagMapping.get(value)
    if ((value and Gravity.RELATIVE_LAYOUT_DIRECTION) != 0) {
      // Under the hood, the framework packs relative constants (start/end) with overlapping bit patterns
      // as absolute constants (left/right). If the relative layout direction flag is set, resolve absolute
      // "left"/"right" tokens to their relative LTR/RTL "start"/"end" equivalents.
      values = HashSet(values)
      if (values.remove("left")) {
        values.add("start")
      }
      if (values.remove("right")) {
        values.add("end")
      }
      values = Collections.unmodifiableSet(values)
    }
    return values
  }
}
