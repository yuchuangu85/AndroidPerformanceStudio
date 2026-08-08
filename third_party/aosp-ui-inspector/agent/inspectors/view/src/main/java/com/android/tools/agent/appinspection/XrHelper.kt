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

package com.android.tools.agent.appinspection

import android.view.View
import androidx.inspection.InspectorEnvironment
import com.android.tools.ui.inspector.inspectors.view.xr.getXrViews

/**
 * Handle with care: this class is accessed through reflection by the compose inspector, so any changes to either package name, class name
 * or signature of [getXrViews] will break the compose inspector.
 *
 * It needs to match the structure seen in dynamic-layout-inspector because the compose inspector is shared by both ui-inspector and
 * dynamic-layout-inspector.
 *
 * This file is almost a mirror of the one found in dynamic-layout-inspection.
 */
class XrHelper(private val environment: InspectorEnvironment) {
  var enabled = true

  /** Get all the views from XR. */
  fun getXrViews(): List<View> {
    if (!enabled) {
      return emptyList()
    }

    return runCatching { getXrViews(environment) }.getOrNull() ?: emptyList()
  }
}
