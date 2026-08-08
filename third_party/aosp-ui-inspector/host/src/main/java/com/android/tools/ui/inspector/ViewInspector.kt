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
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol

/** Sends a command to the agent to load and create the view inspector dynamically. */
internal suspend fun createViewInspector(commandSender: CommandSender, injectionManager: InjectionManager) {
  val inspectorMetadata = InspectorRegistry.VIEW_INSPECTOR

  // Push payload jar on demand and get the remote path
  val dexPath = injectionManager.pushInspectorPayload(inspectorMetadata)
  val createCommand =
    UiInspectorProtocol.Command.newBuilder()
      .setCreateInspector(
        UiInspectorProtocol.CreateInspectorCommand.newBuilder().setInspectorId(inspectorMetadata.id).setDexPath(dexPath).build()
      )
      .build()

  val createResponse = commandSender.sendMessage(createCommand)
  if (createResponse.status != UiInspectorProtocol.Response.Status.SUCCESS) {
    throw IllegalStateException("Failed to create inspector: ${createResponse.errorMessage}")
  }
}

/** Sends a dump command to the view inspector and returns the parsed View tree roots. */
internal suspend fun dumpViews(commandSender: CommandSender, includeAttributes: Boolean, includeResolutionStack: Boolean): UiDump {
  val viewInspectorCommand =
    ViewInspectorProtocol.Command.newBuilder()
      .setDumpViewsCommand(
        ViewInspectorProtocol.DumpViewsCommand.newBuilder()
          .setIncludeAttributes(includeAttributes || includeResolutionStack)
          .setIncludeResolutionStack(includeResolutionStack)
          .build()
      )
      .build()

  val responsePayload = commandSender.sendInspectorCommand(ProtocolConstants.VIEW_INSPECTOR_ID, viewInspectorCommand.toByteArray())
  val viewInspectorResponse = ViewInspectorProtocol.Response.parseFrom(responsePayload)

  if (viewInspectorResponse.specializedCase != ViewInspectorProtocol.Response.SpecializedCase.DUMP_VIEWS_RESPONSE) {
    throw IllegalStateException("Unexpected response: ${viewInspectorResponse.specializedCase}")
  }

  val dumpResponse = viewInspectorResponse.dumpViewsResponse
  val stringTable = dumpResponse.stringsList.associate { it.id to it.value }

  val configuration = if (dumpResponse.hasConfiguration()) convertConfiguration(dumpResponse.configuration, stringTable) else null
  val roots =
    dumpResponse.nodesList
      .map { convertViewNode(it, stringTable, includeResolutionStack) }
      .map { node ->
        val density = configuration?.density
        val fontScale = configuration?.fontScale
        node.resolveDimensions(density, fontScale) as UiNode.ViewNode
      }
  val appContext = if (dumpResponse.hasAppContext()) convertAppContext(dumpResponse.appContext, stringTable) else null
  return UiDump(roots, configuration, stringTable, appContext)
}
