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

import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

/** Walks the View tree recursively to graft the Composable nodes under the matching AndroidComposeView node. */
internal fun attachComposeTree(
  viewNode: UiNode.ViewNode,
  targetViewId: Long,
  composeNodes: List<LayoutInspectorComposeProtocol.ComposableNode>,
  stringTable: Map<Int, String>,
  viewsToSkip: List<Long>,
  parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse?,
  includeParameters: Boolean,
  includeSemantics: Boolean,
): Boolean {
  if (viewNode.id == targetViewId) {
    viewNode.children.removeAll { child -> child is UiNode.ViewNode && viewsToSkip.contains(child.id) }

    val hostedViewIds = collectHostedViewIds(composeNodes)
    val hostedViewsMap = mutableMapOf<Long, UiNode.ViewNode>()
    // Extract hosted ViewNodes from this AndroidComposeView so they can be grafted under their Composable parents.
    viewNode.children.removeAll { child ->
      if (child is UiNode.ViewNode && hostedViewIds.contains(child.id)) {
        hostedViewsMap[child.id] = child
        true
      } else {
        false
      }
    }

    composeNodes.forEach { composeNode ->
      val parsedComposeNode = convertComposeNode(composeNode, stringTable, hostedViewsMap, parameters, includeParameters, includeSemantics)
      viewNode.children.add(parsedComposeNode)
    }
    return true
  }
  for (child in viewNode.children) {
    if (child is UiNode.ViewNode) {
      if (attachComposeTree(child, targetViewId, composeNodes, stringTable, viewsToSkip, parameters, includeParameters, includeSemantics)) {
        return true
      }
    }
  }
  return false
}

/**
 * Recursively collects the IDs of all Android views hosted within the given Composable nodes.
 *
 * A hosted view ID represents an Android View embedded in a Composable hierarchy (e.g., via an `AndroidView` composable).
 */
private fun collectHostedViewIds(nodes: List<LayoutInspectorComposeProtocol.ComposableNode>): Set<Long> {
  val ids = mutableSetOf<Long>()
  nodes.forEach { collectHostedViewIds(it, ids) }
  return ids
}

/** Traverses the Composable node hierarchy to collect hosted view IDs. */
private fun collectHostedViewIds(node: LayoutInspectorComposeProtocol.ComposableNode, accumulator: MutableSet<Long>) {
  if (node.viewId != 0L) {
    accumulator.add(node.viewId)
  }
  node.childrenList.forEach { collectHostedViewIds(it, accumulator) }
}
