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

/** Shell command listing device processes filtered to the ones hosting the top (foreground) activity. */
internal const val TOP_ACTIVITY_SHELL_COMMAND = "dumpsys activity processes | grep top-activity"

/** Matches `<pid>:<processName>/<user> (...top-activity)` entries in the output of [TOP_ACTIVITY_SHELL_COMMAND]. */
private val TOP_ACTIVITY_REGEX = Regex("(\\d+):([^/\\s]+)/\\S+\\s+\\(.*top-activity\\)")

/** A process currently hosting the top (foreground) activity. */
internal data class TopActivityProcess(val pid: String, val processName: String) {
  /**
   * The application package name derived from [processName] by stripping any `android:process` suffix (`com.app:ui` -> `com.app`). A
   * manifest can assign a process name unrelated to the package name; such apps are not supported by this derivation.
   */
  val packageName: String
    get() = processName.substringBefore(':')
}

/** Parses the output of [TOP_ACTIVITY_SHELL_COMMAND] into the processes hosting the top activity. */
internal fun parseTopActivityProcesses(dumpsysOutput: String): List<TopActivityProcess> =
  dumpsysOutput.lines().mapNotNull { line ->
    TOP_ACTIVITY_REGEX.find(line)?.let { TopActivityProcess(pid = it.groupValues[1], processName = it.groupValues[2]) }
  }
