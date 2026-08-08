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

package com.android.tools.ui.inspector.printer

/** Defines strategies for displaying Compose merged and unmerged accessibility semantics properties. */
enum class SemanticsDisplayMode {
  /** Always print both merged and unmerged semantics with explicit labels ("merged semantics", "unmerged semantics"). */
  BOTH,

  /** Print merged semantics if present; fall back to unmerged semantics if merged semantics is empty. */
  MERGED_WITH_UNMERGED_FALLBACK,

  /** Print only merged semantics (accessibility tree representation). */
  MERGED_ONLY,

  /** Print only unmerged semantics (properties declared directly on the node). */
  UNMERGED_ONLY,

  /** Do not print semantics properties. */
  NONE,
}
