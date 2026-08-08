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

package com.android.tools.ui.inspector.inspectors.view.xr

import android.util.Log
import android.view.View
import android.view.inspector.WindowInspector
import androidx.inspection.InspectorEnvironment
import com.android.tools.ui.inspector.common.ProtocolConstants

private val LOG_TAG = "${ProtocolConstants.LOG_TAG_PREFIX}.XrViewsProvider"
private const val GET_CURRENT_EXTENSIONS_METHOD = "getCurrentExtensions"

private val xrExtensionsClass: Class<*>? by lazy { runCatching { Class.forName("com.android.extensions.xr.XrExtensions") }.getOrNull() }
private var cachedXrExtensions: Any? = null

/**
 * Uses XrExtensions to get the views associated with each panel in the XR app.
 *
 * This file is almost a mirror of the one found in dynamic-layout-inspection.
 */
internal fun getXrViews(environment: InspectorEnvironment): List<View> {
  // Non XR apps will pay the price only once by checking if XrExtensions is present.
  val xrClass = xrExtensionsClass ?: return emptyList()
  val xrExtensions = getXrExtensionsInstance(environment, xrClass)

  if (xrExtensions == null) {
    Log.d(LOG_TAG, "Failed to get XrExtensions instance")
    return emptyList()
  }

  val xrViews = getXrViewsFromExtensions(xrExtensions, xrClass)
  val windowViews = WindowInspector.getGlobalWindowViews()

  // Views for ActivityPanelNodes are not returned by the XrExtensions API. But can be obtained
  // through the WindowInspector, since they are in the main panel. For this reason we merge
  // the views obtained with XrExtensions with the views from the WindowInspector.
  return xrViews.union(windowViews).toList()
}

private fun getXrViewsFromExtensions(xrExtensions: Any, xrExtensionsClass: Class<*>): List<View> {
  val listNodesMethod = xrExtensionsClass.getMethod("listNodesWithSurfacePackages")
  val nodes = listNodesMethod.invoke(xrExtensions) as? List<*> ?: return emptyList()
  return nodes.mapNotNull { node ->
    val getRootViewMethod = node?.javaClass?.getMethod("getRootView")
    getRootViewMethod?.invoke(node) as? View
  }
}

private fun getXrExtensionsUsingReflection(xrExtensionsClass: Class<*>): Any? {
  return runCatching {
      Log.d(LOG_TAG, "Getting XrExtensions using reflection")
      // In agreement with the Xr team, the `getCurrentExtensions` method has been provided as a
      // temporary hack to allow Layout Inspector to get the instance of XrExtensions without having
      // to go through scenecore (which is currently the non hacky-way of doing this, using
      // XrExtensionsProvider).
      // This hack will be removed once the Xr team decides how to expose the instance from the
      // xr extensions library directly. They will notify us before removing the getCurrentExtensions
      // method. See b/411291368.
      val getCurrentExtensionsMethod = xrExtensionsClass.getDeclaredMethod(GET_CURRENT_EXTENSIONS_METHOD)
      getCurrentExtensionsMethod.isAccessible = true
      getCurrentExtensionsMethod.invoke(null)
    }
    .getOrNull()
}

private fun getXrExtensionsInstance(environment: InspectorEnvironment, xrClass: Class<*>): Any? {
  cachedXrExtensions?.let {
    return it
  }

  // Try reflection first as a fast-path to avoid the expensive ART tooling heap sweep in findInstances.
  val instance = getXrExtensionsUsingReflection(xrClass) ?: environment.artTooling().findInstances(xrClass).firstOrNull()
  if (instance != null) {
    cachedXrExtensions = instance
  }
  return instance
}
