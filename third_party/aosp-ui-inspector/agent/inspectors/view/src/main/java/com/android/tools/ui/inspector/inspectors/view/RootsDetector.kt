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

import android.view.View
import android.view.inspector.WindowInspector
import com.android.tools.agent.appinspection.XrHelper

internal object RootsDetector {
  /**
   * Returns all root views currently attached to the window.
   *
   * Note: This relies on [WindowInspector.getGlobalWindowViews], which requires API 29+. This is intentional as the UI Inspector targets
   * modern Android versions.
   */
  fun getRootViews(xrHelper: XrHelper): List<View> {
    val xrViews = xrHelper.getXrViews()
    val views =
      if (xrViews.isNotEmpty()) {
        // If there are xr panels, xrViews already contains both XR panel views and regular
        // window views merged without duplicates.
        xrViews
      } else {
        getAndroidViews()
      }
    return views.filter { it.visibility == View.VISIBLE && it.isAttachedToWindow }.sortedBy { it.z }
  }

  private fun getAndroidViews(): List<View> {
    return WindowInspector.getGlobalWindowViews()
  }
}
