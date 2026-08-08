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

/**
 * Common platform dimension attributes mapped to sp. Hardcoded and non-exhaustive because PropertyMapper only exposes raw pixels and lacks
 * unit metadata.
 */
private val DIMENSION_SP_ATTRIBUTES =
  setOf(
    "textSize",
    "lineHeight",
    "lineSpacingExtra",
    "firstBaselineToTopHeight",
    "lastBaselineToBottomHeight",
    "titleTextSize",
    "subtitleTextSize",
    "tabTextSize",
  )

/**
 * Traverses the unified UI layout tree recursively and resolves raw pixel dimensions to dp and sp values based on density and fontScale.
 */
internal fun UiNode.resolveDimensions(density: Dimension.Dpi?, fontScale: Float?): UiNode {
  val densityDpi = density?.value
  return when (this) {
    is UiNode.ViewNode -> {
      val resolvedAttributes =
        attributes.map { attr ->
          if (attr.value is UiNode.AttributeValue.DimensionVal) {
            val px = attr.value.value
            var dp: Float? = null
            var sp: Float? = null
            if (densityDpi != null && densityDpi > 0) {
              val densityScale = densityDpi.toFloat() / 160.0f
              if (attr.name in DIMENSION_SP_ATTRIBUTES) {
                val scale = densityScale * (fontScale ?: 1.0f)
                if (scale > 0.0f) {
                  sp = px / scale
                }
              } else {
                dp = px / densityScale
              }
            }
            attr.copy(value = UiNode.AttributeValue.DimensionVal(px, dp, sp))
          } else {
            attr
          }
        }
      val resolvedChildren = children.map { it.resolveDimensions(density, fontScale) }.toMutableList()
      this.copy(attributes = resolvedAttributes, children = resolvedChildren)
    }
    is UiNode.ComposeNode -> {
      // Compose parameter dimensions are already resolved on the wire.
      // We only need to recursively resolve potential ViewNodes inside children.
      val resolvedChildren = children.map { it.resolveDimensions(density, fontScale) }.toMutableList()
      this.copy(children = resolvedChildren)
    }
  }
}
