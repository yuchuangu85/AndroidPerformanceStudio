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

import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComposeInspectorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun writeResponse(output: java.io.OutputStream, response: UiInspectorProtocol.Response) {
    val agentMessage = UiInspectorProtocol.AgentMessage.newBuilder().setResponse(response).build()
    FramingProtocol.writeMessage(output, agentMessage.toByteArray())
  }

  private val deviceSerial = "123"
  private val packageName = "com.example"

  @Test
  fun testGetComposeArtifactId_legacyAndKmpVersions() {
    // Legacy (Pre-KMP) versions should return "ui"
    assertThat(getComposeArtifactId("1.4.3")).isEqualTo("ui")
    assertThat(getComposeArtifactId("1.0.0")).isEqualTo("ui")
    assertThat(getComposeArtifactId("0.9.0")).isEqualTo("ui")

    // Modern (KMP) versions should return "ui-android"
    assertThat(getComposeArtifactId("1.5.0")).isEqualTo("ui-android")
    assertThat(getComposeArtifactId("1.6.0-rc01")).isEqualTo("ui-android")
    assertThat(getComposeArtifactId("2.0.0-alpha01")).isEqualTo("ui-android")

    // Malformed/empty versions should gracefully fallback to "ui"
    assertThat(getComposeArtifactId("invalid")).isEqualTo("ui")
    assertThat(getComposeArtifactId("")).isEqualTo("ui")
    assertThat(getComposeArtifactId("1")).isEqualTo("ui")
  }

  @Test
  fun testCreateComposeInspector_endToEndOrchestration() = runBlocking {
    // 1. Setup Background Server to simulate JVM agent
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val versionCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val createCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        versionCmdReceived.complete(cmd1)

        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        createCmdReceived.complete(cmd2)

        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()

    val agentPathResolver = { abi: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Trigger injectAndAttach so we populate the appDataDir internal states
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")

    val baseAgentSetupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, baseAgentSetupCmd, "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\"",
      "",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )
    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    // 3. Execute E2E Orchestrator with dynamic lambda jar resolution mock
    CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
      createComposeInspector(
        commandSender = commandSender,
        injectionManager = injectionManager,
        resolveJar = {
          val fixedJar = tempFolder.newFile("compose-inspector.jar")
          fixedJar.writeText("fake pre-compiled compose dex classes")
          fixedJar
        },
      )
    }

    // 5. Assertions
    // A. Check version detection command
    val vCmd = versionCmdReceived.await()
    assertThat(vCmd.getVersion.libraryIdsList).containsExactly(ProtocolConstants.COMPOSE_UI_LIBRARY_ID)

    // B. Check inspector jar file was pushed to simulated device
    val remoteFilePushed =
      testDeviceServices.recordedSyncSends.any { it.remoteFilePath == "/data/local/tmp/ui-inspector/compose-inspector.jar.test.tmp" }
    assertThat(remoteFilePushed).isTrue()

    // C. Check CreateInspectorCommand parameters
    val cCmd = createCmdReceived.await()
    assertThat(cCmd.createInspector.inspectorId).isEqualTo(ProtocolConstants.COMPOSE_INSPECTOR_ID)
    assertThat(cCmd.createInspector.dexPath).isEqualTo("/data/local/tmp/ui-inspector/compose-inspector.jar")

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testViewInspectorDump_GetComposablesAndMerge() = runBlocking {
    // 1. Setup Background Server to simulate dynamic responses
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val dumpCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val composeCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)

        // C. Handle ViewInspector Message (DumpViewsCommand)
        val cmdBytes3 = FramingProtocol.readMessage(input)
        val cmd3 = UiInspectorProtocol.Command.parseFrom(cmdBytes3)
        dumpCmdReceived.complete(cmd3)

        // Build a mock View tree: FrameLayout (ID 1000) -> AndroidComposeView (ID 2000)
        val viewNode1 =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(1000)
            .setClassName(1) // index for FrameLayout
            .setBounds(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                .setX(0)
                .setY(0)
                .setWidth(1080)
                .setHeight(1920)
            )
            .addChildren(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                .setId(2000)
                .setClassName(2) // index for AndroidComposeView
                .setBounds(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                    .setX(0)
                    .setY(0)
                    .setWidth(1080)
                    .setHeight(1920)
                )
            )
            .build()

        val viewResponse =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response.newBuilder()
            .setDumpViewsResponse(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse.newBuilder()
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(1)
                    .setValue("android.widget.FrameLayout")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(2)
                    .setValue("androidx.compose.ui.platform.AndroidComposeView")
                )
                .addNodes(viewNode1)
            )
            .build()

        val viewMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd3.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        writeResponse(output, viewMsgResponse)

        // D. Handle COMPOSE_COMMAND (GetComposablesCommand)
        val cmdBytes4 = FramingProtocol.readMessage(input)
        val cmd4 = UiInspectorProtocol.Command.parseFrom(cmdBytes4)
        composeCmdReceived.complete(cmd4)

        // Build a mock Compose tree: Column (ID 3000) -> Text (ID 4000)
        val composeRoot =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
            .setViewId(2000)
            .addNodes(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                .setId(3000)
                .setName(1) // index for Column
                .setBounds(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                    .setLayout(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                        .setX(0)
                        .setY(0)
                        .setW(1080)
                        .setH(200)
                    )
                )
                .addChildren(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                    .setId(4000)
                    .setName(2) // index for Text
                    .setBounds(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                        .setLayout(
                          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                            .setX(10)
                            .setY(10)
                            .setW(100)
                            .setH(50)
                        )
                    )
                )
            )
            .build()

        val composeResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetComposablesResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(1).setStr("Column")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("Text")
                )
                .addRoots(composeRoot)
            )
            .build()

        val composeMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd4.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(composeResponse.toByteArray()))
            )
            .build()
        writeResponse(output, composeMsgResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()

    val agentPathResolver = { abi: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Trigger injectAndAttach so we populate the appDataDir internal states
    val baseAgentSetupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, baseAgentSetupCmd, "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\"",
      "",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )
    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    val uiDump =
      CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
        // Inject Compose Inspector with a mock resolveJar lambda returning a dummy file
        val composeInspectorConnected =
          createComposeInspector(
            commandSender = commandSender,
            injectionManager = injectionManager,
            resolveJar = {
              val fixedJar = tempFolder.newFile("compose-inspector.jar")
              fixedJar.writeText("fake pre-compiled compose dex classes")
              fixedJar
            },
          )
        assertThat(composeInspectorConnected).isTrue()

        fetchUiDump(
          commandSender = commandSender,
          includeAttributes = false,
          includeResolutionStack = false,
          composeInspectorConnected = composeInspectorConnected,
          skipSystemComposables = true,
          includeSemantics = false,
        )
      }

    // 4. Assertions
    val roots = uiDump.roots
    assertThat(roots).hasSize(1)
    val viewRoot = roots[0]
    assertThat(viewRoot.className).isEqualTo("android.widget.FrameLayout")
    val composeView = viewRoot.children[0]
    assertThat(composeView.className).isEqualTo("androidx.compose.ui.platform.AndroidComposeView")
    val column = composeView.children[0]
    assertThat(column.className).isEqualTo("Column")
    val text = column.children[0]
    assertThat(text.className).isEqualTo("Text")

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testViewInspectorDump_GetComposablesAndMerge_withParameters() = runBlocking {
    // 1. Setup Background Server to simulate dynamic responses
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val dumpCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val composeCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val allParamsCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand (Compose)
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)

        // C. Handle ViewInspector Message (DumpViewsCommand)
        val cmdBytes3 = FramingProtocol.readMessage(input)
        val cmd3 = UiInspectorProtocol.Command.parseFrom(cmdBytes3)
        dumpCmdReceived.complete(cmd3)

        // Build a mock View tree: FrameLayout (ID 1000) -> AndroidComposeView (ID 2000)
        val viewNode1 =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(1000)
            .setClassName(1) // FrameLayout
            .setBounds(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                .setX(0)
                .setY(0)
                .setWidth(1080)
                .setHeight(1920)
            )
            .addChildren(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                .setId(2000)
                .setClassName(2) // AndroidComposeView
                .setBounds(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                    .setX(0)
                    .setY(0)
                    .setWidth(1080)
                    .setHeight(1920)
                )
            )
            .build()

        val viewResponse =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response.newBuilder()
            .setDumpViewsResponse(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse.newBuilder()
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(1)
                    .setValue("android.widget.FrameLayout")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(2)
                    .setValue("androidx.compose.ui.platform.AndroidComposeView")
                )
                .addNodes(viewNode1)
            )
            .build()

        val viewMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd3.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        writeResponse(output, viewMsgResponse)

        // D. Handle COMPOSE_COMMAND (GetComposablesCommand)
        val cmdBytes4 = FramingProtocol.readMessage(input)
        val cmd4 = UiInspectorProtocol.Command.parseFrom(cmdBytes4)
        composeCmdReceived.complete(cmd4)

        // Build a mock Compose tree: Column (ID 3000) -> Text (ID 4000)
        val composeRoot =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
            .setViewId(2000)
            .addNodes(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                .setId(3000)
                .setName(1) // Column
                .setBounds(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                    .setLayout(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                        .setX(0)
                        .setY(0)
                        .setW(1080)
                        .setH(200)
                    )
                )
                .addChildren(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                    .setId(4000)
                    .setName(2) // Text
                    .setBounds(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                        .setLayout(
                          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                            .setX(10)
                            .setY(10)
                            .setW(100)
                            .setH(50)
                        )
                    )
                )
            )
            .build()

        val composeResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetComposablesResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(1).setStr("Column")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("Text")
                )
                .addRoots(composeRoot)
            )
            .build()

        val composeMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd4.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(composeResponse.toByteArray()))
            )
            .build()
        writeResponse(output, composeMsgResponse)

        // E. Handle COMPOSE_COMMAND (GetAllParametersCommand)
        val cmdBytes5 = FramingProtocol.readMessage(input)
        val cmd5 = UiInspectorProtocol.Command.parseFrom(cmdBytes5)
        allParamsCmdReceived.complete(cmd5)

        val paramGroup =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(4000)
            .addParameter(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(3) // "text"
                .setType(layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(4) // index for "Hello"
                .build()
            )
            .build()

        val allParamsResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetAllParametersResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("text")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("Hello")
                )
                .addParameterGroups(paramGroup)
            )
            .build()

        val paramsMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd5.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(allParamsResponse.toByteArray()))
            )
            .build()
        writeResponse(output, paramsMsgResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()

    val agentPathResolver = { abi: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Trigger injectAndAttach so we populate the appDataDir internal states
    val baseAgentSetupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, baseAgentSetupCmd, "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\"",
      "",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )
    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    val uiDump =
      CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
        // Inject Compose Inspector with a mock resolveJar lambda returning a dummy file
        val composeInspectorConnected =
          createComposeInspector(
            commandSender = commandSender,
            injectionManager = injectionManager,
            resolveJar = {
              val fixedJar = tempFolder.newFile("compose-inspector.jar")
              fixedJar.writeText("fake pre-compiled compose dex classes")
              fixedJar
            },
          )
        assertThat(composeInspectorConnected).isTrue()

        fetchUiDump(
          commandSender = commandSender,
          includeAttributes = true,
          includeResolutionStack = false,
          composeInspectorConnected = composeInspectorConnected,
          skipSystemComposables = false,
          includeSemantics = false,
        )
      }

    // 4. Assertions
    val roots = uiDump.roots
    assertThat(roots).hasSize(1)
    val viewRoot = roots[0]
    assertThat(viewRoot.className).isEqualTo("android.widget.FrameLayout")
    val composeView = viewRoot.children[0]
    assertThat(composeView.className).isEqualTo("androidx.compose.ui.platform.AndroidComposeView")
    val column = composeView.children[0]
    assertThat(column.className).isEqualTo("Column")
    val textNode = column.children[0] as UiNode.ComposeNode
    assertThat(textNode.className).isEqualTo("Text")

    // Check parameters
    val p = textNode.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(p.name).isEqualTo("text")
    assertThat((p.value as UiNode.ComposeParameter.Value.StringVal).value).isEqualTo("Hello")

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testViewInspectorDump_GetComposablesAndMerge_withSemantics(): Unit = runBlocking {
    // 1. Setup Background Server to simulate dynamic responses
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val dumpCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val composeCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val allParamsCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)

        // C. Handle ViewInspector Message (DumpViewsCommand)
        val cmdBytes3 = FramingProtocol.readMessage(input)
        val cmd3 = UiInspectorProtocol.Command.parseFrom(cmdBytes3)
        dumpCmdReceived.complete(cmd3)

        // Build a mock View tree: FrameLayout (ID 1000) -> AndroidComposeView (ID 2000)
        val viewNode1 =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(1000)
            .setClassName(1) // index for FrameLayout
            .setBounds(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                .setX(0)
                .setY(0)
                .setWidth(1080)
                .setHeight(1920)
            )
            .addChildren(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                .setId(2000)
                .setClassName(2) // AndroidComposeView
                .setBounds(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                    .setX(0)
                    .setY(0)
                    .setWidth(1080)
                    .setHeight(1920)
                )
            )
            .build()

        val viewResponse =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response.newBuilder()
            .setDumpViewsResponse(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse.newBuilder()
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(1)
                    .setValue("android.widget.FrameLayout")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(2)
                    .setValue("androidx.compose.ui.platform.AndroidComposeView")
                )
                .addNodes(viewNode1)
            )
            .build()

        val viewMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd3.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        writeResponse(output, viewMsgResponse)

        // D. Handle COMPOSE_COMMAND (GetComposablesCommand)
        val cmdBytes4 = FramingProtocol.readMessage(input)
        val cmd4 = UiInspectorProtocol.Command.parseFrom(cmdBytes4)
        composeCmdReceived.complete(cmd4)

        // Build a mock Compose tree: Column (ID 3000) -> Text (ID 4000)
        val composeRoot =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
            .setViewId(2000)
            .addNodes(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                .setId(3000)
                .setName(1) // Column
                .setBounds(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                    .setLayout(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                        .setX(0)
                        .setY(0)
                        .setW(1080)
                        .setH(200)
                    )
                )
                .addChildren(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                    .setId(4000)
                    .setName(2) // Text
                    .setBounds(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                        .setLayout(
                          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                            .setX(10)
                            .setY(10)
                            .setW(100)
                            .setH(50)
                        )
                    )
                )
            )
            .build()

        val composeResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetComposablesResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(1).setStr("Column")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("Text")
                )
                .addRoots(composeRoot)
            )
            .build()

        val composeMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd4.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(composeResponse.toByteArray()))
            )
            .build()
        writeResponse(output, composeMsgResponse)

        // E. Handle COMPOSE_COMMAND (GetAllParametersCommand)
        val cmdBytes5 = FramingProtocol.readMessage(input)
        val cmd5 = UiInspectorProtocol.Command.parseFrom(cmdBytes5)
        allParamsCmdReceived.complete(cmd5)

        val paramGroup =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(4000)
            .addParameter(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(3) // "text"
                .setType(layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(4) // index for "Hello"
                .build()
            )
            .addMergedSemantics(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(5) // "contentDescription"
                .setType(layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(6) // index for "My Button"
                .build()
            )
            .build()

        val allParamsResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetAllParametersResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("text")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("Hello")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder()
                    .setId(5)
                    .setStr("contentDescription")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(6).setStr("My Button")
                )
                .addParameterGroups(paramGroup)
            )
            .build()

        val allParamsMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd5.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(allParamsResponse.toByteArray()))
            )
            .build()
        writeResponse(output, allParamsMsgResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val expectedSetupCmd =
      "run-as $packageName sh -c 'rm -f compose-inspector.jar && " +
        "cat /data/local/tmp/compose-inspector.jar > compose-inspector.jar && " +
        "chmod 444 compose-inspector.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, expectedSetupCmd, "")

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()

    val agentPathResolver = { _: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Trigger injectAndAttach so we populate the appDataDir internal states
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")

    val baseAgentSetupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, baseAgentSetupCmd, "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\"",
      "",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )
    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    val originalFactory = sessionFactory
    sessionFactory = { testSession }

    val uiDump =
      try {
        CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
          val composeInspectorConnected =
            createComposeInspector(
              commandSender = commandSender,
              injectionManager = injectionManager,
              resolveJar = {
                val fixedJar = tempFolder.newFile("compose-inspector.jar")
                fixedJar.writeText("fake pre-compiled compose dex classes")
                fixedJar
              },
            )
          assertThat(composeInspectorConnected).isTrue()

          fetchUiDump(
            commandSender = commandSender,
            includeAttributes = true,
            includeResolutionStack = false,
            composeInspectorConnected = composeInspectorConnected,
            skipSystemComposables = false,
            includeSemantics = true,
          )
        }
      } finally {
        sessionFactory = originalFactory
      }

    // 4. Assertions
    val roots = uiDump.roots
    assertThat(roots).hasSize(1)
    val viewRoot = roots[0]
    assertThat(viewRoot.className).isEqualTo("android.widget.FrameLayout")
    val composeView = viewRoot.children[0]
    assertThat(composeView.className).isEqualTo("androidx.compose.ui.platform.AndroidComposeView")
    val column = composeView.children[0]
    assertThat(column.className).isEqualTo("Column")
    val textNode = column.children[0] as UiNode.ComposeNode
    assertThat(textNode.className).isEqualTo("Text")

    // Check parameters
    val p = textNode.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(p.name).isEqualTo("text")
    assertThat((p.value as UiNode.ComposeParameter.Value.StringVal).value).isEqualTo("Hello")

    // Check semantics
    val s = textNode.mergedSemantics[0] as UiNode.ComposeParameter.Single
    assertThat(s.name).isEqualTo("contentDescription")
    assertThat((s.value as UiNode.ComposeParameter.Value.StringVal).value).isEqualTo("My Button")

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  private fun configureAtomicMoveCommands(fakeSession: FakeAdbSession, deviceSelector: DeviceSelector) {
    listOf(
        "/data/local/tmp/lib_ui_inspector_agent.so",
        "/data/local/tmp/lib_ui_inspector_service.jar",
        "/data/local/tmp/lib_ui_inspector_payload.jar",
        "/data/local/tmp/ui-inspector/compose-inspector.jar",
        "/data/local/tmp/compose-inspector.jar",
      )
      .forEach { target ->
        fakeSession.deviceServices.configureShellCommand(deviceSelector, "mv -f '$target.test.tmp' '$target'", "")
        fakeSession.deviceServices.configureShellCommand(deviceSelector, "rm -f '$target.test.tmp'", "")
      }
  }
}
