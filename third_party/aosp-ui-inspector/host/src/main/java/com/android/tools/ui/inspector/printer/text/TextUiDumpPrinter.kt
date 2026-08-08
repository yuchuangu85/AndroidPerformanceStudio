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

import com.android.tools.ui.inspector.UiDump
import com.android.tools.ui.inspector.printer.SemanticsDisplayMode
import com.android.tools.ui.inspector.printer.UiDumpPrinter
import java.io.PrintStream

/**
 * Human-readable plain text console implementation of [UiDumpPrinter].
 *
 * @param out Target output stream for printed dumps.
 * @param semanticsMode Strategy for displaying Compose accessibility semantics properties.
 */
internal class TextUiDumpPrinter(
  private val out: PrintStream = System.out,
  private val semanticsMode: SemanticsDisplayMode = SemanticsDisplayMode.BOTH,
) : UiDumpPrinter {
  override fun printDump(uiDump: UiDump) {
    uiDump.appContext?.let { printAppContext(it, out) }
    uiDump.configuration?.let { printDeviceConfiguration(it, out) }
    uiDump.roots.forEach { printUiTree(it, 0, out, semanticsMode) }
  }
}
