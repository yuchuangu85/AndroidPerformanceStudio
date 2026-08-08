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

package com.android.tools.ui.inspector.printer.text

import com.android.tools.ui.inspector.UiNode
import com.android.tools.ui.inspector.printer.SemanticsDisplayMode
import java.io.PrintStream

/**
 * Recursively walks and formats the unified UiNode layout tree to target [PrintStream].
 *
 * @param node The layout root or node to format.
 * @param indent Indentation depth for nested printing.
 * @param out Target output stream.
 * @param semanticsMode Strategy for displaying Compose accessibility semantics properties.
 */
internal fun printUiTree(node: UiNode, indent: Int, out: PrintStream, semanticsMode: SemanticsDisplayMode) {
  if (indent == 0) {
    out.println("View Hierarchy:")
  }
  val prefix = " ".repeat(indent)
  out.println("$prefix${node.formatHeader()}")

  when (node) {
    is UiNode.ViewNode -> {
      node.attributes.forEach { attr ->
        out.println("$prefix ${attr.format()}")

        val sourceStr = attr.directSource ?: ""
        if (sourceStr.isNotEmpty()) {
          out.println("$prefix  Defined in: $sourceStr")
        }

        attr.styleChain.forEach { style -> out.println("$prefix  Inherited from: $style") }
      }
    }
    is UiNode.ComposeNode -> {
      node.parameters.forEach { param ->
        val formatted = param.format()
        if (formatted.isNotEmpty()) {
          out.println("$prefix $formatted")
        }
      }

      val printMerged =
        when (semanticsMode) {
          SemanticsDisplayMode.BOTH,
          SemanticsDisplayMode.MERGED_ONLY -> true
          SemanticsDisplayMode.MERGED_WITH_UNMERGED_FALLBACK -> node.mergedSemantics.isNotEmpty()
          SemanticsDisplayMode.UNMERGED_ONLY,
          SemanticsDisplayMode.NONE -> false
        }

      val printUnmerged =
        when (semanticsMode) {
          SemanticsDisplayMode.BOTH,
          SemanticsDisplayMode.UNMERGED_ONLY -> true
          SemanticsDisplayMode.MERGED_WITH_UNMERGED_FALLBACK -> node.mergedSemantics.isEmpty()
          SemanticsDisplayMode.MERGED_ONLY,
          SemanticsDisplayMode.NONE -> false
        }

      if (printMerged) {
        node.mergedSemantics.forEach { param ->
          val formattedValue = formatComposeParameter(param)
          if (formattedValue.isNotEmpty()) {
            out.println("$prefix merged semantics: ${param.name}=$formattedValue")
          }
        }
      }

      if (printUnmerged) {
        node.unmergedSemantics.forEach { param ->
          val formattedValue = formatComposeParameter(param)
          if (formattedValue.isNotEmpty()) {
            out.println("$prefix unmerged semantics: ${param.name}=$formattedValue")
          }
        }
      }
    }
  }
  node.children.forEach { printUiTree(it, indent + 1, out, semanticsMode) }
}
