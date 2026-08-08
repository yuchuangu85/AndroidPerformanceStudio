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

import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorFactory
import com.android.tools.agent.appinspection.ViewLayoutInspector
import com.android.tools.agent.appinspection.XrHelper
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Command
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsCommand
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ViewInspectorFactory : InspectorFactory<ViewInspector>(ProtocolConstants.VIEW_INSPECTOR_ID) {
  override fun createInspector(connection: Connection, environment: InspectorEnvironment) = ViewInspector(connection, environment)
}

class ViewInspector(connection: Connection, private val environment: InspectorEnvironment) : Inspector(connection) {
  // Instantiate marker class and hold strong reference so Compose Inspector's reflection contract
  // (javaClass.name == "com.android.tools.agent.appinspection.ViewLayoutInspector")
  // locates this agent's ClassLoader to find XrHelper on the heap without being garbage collected.
  @Suppress("unused") private val viewLayoutInspector = ViewLayoutInspector(connection)
  private val scope = CoroutineScope(SupervisorJob() + environment.executors().primary().asCoroutineDispatcher())
  private val mainDispatcher = MainThreadExecutor().asCoroutineDispatcher()
  private val xrHelper = XrHelper(environment)

  override fun onReceiveCommand(data: ByteArray, callback: CommandCallback) {
    val command = Command.parseFrom(data)
    when (command.specializedCase) {
      Command.SpecializedCase.DUMP_VIEWS_COMMAND -> handleDumpViewsCommand(command.dumpViewsCommand, callback)
      else -> error("Unknown command: ${command.specializedCase}")
    }
  }

  private fun handleDumpViewsCommand(dumpViewsCommand: DumpViewsCommand, callback: CommandCallback) {
    val includeAttributes = dumpViewsCommand.includeAttributes
    val includeResolutionStack = dumpViewsCommand.includeResolutionStack
    scope.launch {
      val stringTable = StringTable()
      val (nodes, systemConfig, appContext) =
        withContext(mainDispatcher) {
          val roots = RootsDetector.getRootViews(xrHelper)
          val config = roots.firstOrNull()?.context?.resources?.configuration
          val appCtx = roots.firstOrNull()?.createAppContext(stringTable)
          val viewNodes = roots.map { it.toViewNode(stringTable, includeAttributes, includeResolutionStack) }
          Triple(viewNodes, config, appCtx)
        }
      callback.reply {
        val builder = DumpViewsResponse.newBuilder().addAllNodes(nodes)
        if (systemConfig != null) {
          builder.configuration = systemConfig.convert(stringTable)
        }
        if (appContext != null) {
          builder.appContext = appContext
        }
        builder.addAllStrings(stringTable.toStringEntries())
        dumpViewsResponse = builder.build()
      }
    }
  }

  override fun onDispose() {}
}

private fun Inspector.CommandCallback.reply(initResponse: Response.Builder.() -> Unit) {
  val response = Response.newBuilder()
  response.initResponse()
  reply(response.build().toByteArray())
}
