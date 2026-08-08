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

import com.android.tools.ui.inspector.common.ProtocolConstants
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Metadata for a specific inspector.
 *
 * @param id The unique ID of the inspector, used in protocol messages.
 * @param localJarPath The full local path on the host to the payload jar file.
 */
data class InspectorMetadata(val id: String, val localJarPath: Path)

private const val PAYLOAD_DIR_PATH = "tools/base/ui-inspector/agent/inspectors/view"
private const val VIEW_INSPECTOR_JAR_NAME = "view-inspector.jar"

object InspectorRegistry {
  val VIEW_INSPECTOR =
    InspectorMetadata(id = ProtocolConstants.VIEW_INSPECTOR_ID, localJarPath = Paths.get("$PAYLOAD_DIR_PATH/$VIEW_INSPECTOR_JAR_NAME"))
}
