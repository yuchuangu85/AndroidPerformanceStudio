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
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.printer.UiDumpPrinter
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InjectionManagerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var fakeSession: FakeAdbSession
  private lateinit var testDeviceServices: TestAdbDeviceServices
  private lateinit var testHostServices: TestAdbHostServices
  private lateinit var testSession: com.android.adblib.AdbSession
  private lateinit var dummyAgent: Path
  private lateinit var dummyJar: Path
  private lateinit var dummyPayload: Path
  private lateinit var agentPathResolver: (String) -> Path

  private val deviceSerial = "123"
  private val packageName = "com.example"
  private val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
  private val settingsSeparator = "__UI_INSPECTOR_SETTINGS_SEPARATOR__"
  private val readSettingsCmd =
    "settings get global debug_view_attributes ; echo $settingsSeparator ; settings get global debug_view_attributes_application_package"
  private val putSettingsCmd = "settings put global debug_view_attributes_application_package $packageName"

  @Before
  fun setUp() {
    fakeSession = FakeAdbSession()
    testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    testHostServices = TestAdbHostServices(fakeSession.hostServices)
    testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
    dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()

    agentPathResolver = { abi -> dummyAgent }

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "date +\"%m-%d %H:%M:%S.000\"", "06-24 15:44:32.000\n")
    listOf(
        "/data/local/tmp/lib_ui_inspector_agent.so",
        "/data/local/tmp/lib_ui_inspector_service.jar",
        "/data/local/tmp/lib_ui_inspector_payload.jar",
        "/data/local/tmp/ui-inspector/my-inspector.jar",
      )
      .forEach { target ->
        fakeSession.deviceServices.configureShellCommand(deviceSelector, "mv -f '$target.test.tmp' '$target'", "")
        fakeSession.deviceServices.configureShellCommand(deviceSelector, "rm -f '$target.test.tmp'", "")
      }
  }

  @Test
  fun testInjectAndAttach() = runTest {
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
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    // Mock expected shell commands for the injection flow
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    val setupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
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

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false)
    assertThat(port).isEqualTo("12345")

    // Verify that syncSend was called with correct parameters
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted())
      .containsExactly(
        "/data/local/tmp/lib_ui_inspector_agent.so.test.tmp",
        "/data/local/tmp/lib_ui_inspector_payload.jar.test.tmp",
        "/data/local/tmp/lib_ui_inspector_service.jar.test.tmp",
      )
  }

  @Test
  fun testInjectAndAttach_CommandFails() = runTest {
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
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val setupCmd =
      "run-as com.example sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"

    // Configure this command to FAIL!
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      setupCmd,
      stdout = "",
      stderr = "Package is not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected IllegalStateException was not thrown")
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Command '$setupCmd' failed with exit code 1. Stderr: Package is not debuggable")
    }

    // Verify that both files were pushed to /data/local/tmp before the setup command failed.
    // The push operations occur concurrently and complete before the copy/setup step is executed.
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted())
      .containsExactly(
        "/data/local/tmp/lib_ui_inspector_agent.so.test.tmp",
        "/data/local/tmp/lib_ui_inspector_payload.jar.test.tmp",
        "/data/local/tmp/lib_ui_inspector_service.jar.test.tmp",
      )
  }

  @Test
  fun testInjectAndAttach_FastFailLogcatDiagnostics() = runTest {
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
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    // Mock expected shell commands for the injection flow
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val setupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\"",
      "",
    )

    // 1. Configure the socket check to fail (socket is not created by the agent)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix | grep ui_inspector_1234 || true", "")

    // 2. Configure logcat command to return a mock agent crash log
    val logcatCmd = "logcat -d -t '06-24 15:44:32.000' --pid=1234 *:E"
    val mockErrorLog =
      "06-24 15:44:32.764  7191  7191 E studio.ui-inspector.InspectorService: Error in InspectorService initialization\n" +
        "06-24 15:44:32.764  7191  7191 E studio.ui-inspector.InspectorService: java.lang.RuntimeException: Simulated bootstrap failure"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, logcatCmd, mockErrorLog)

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected IllegalStateException due to agent bootstrap failure")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Failed to attach UI Inspector agent. Agent error in logcat:")
      assertThat(e.message).contains("Error in InspectorService initialization")
      assertThat(e.message).contains("java.lang.RuntimeException: Simulated bootstrap failure")
    }
  }

  @Test
  fun testPushInspectorPayload() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
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
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    // Mock injectAndAttach dependencies so we can initialize appDataDir
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )
    val agentSetupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, agentSetupCmd, "")
    val attachCmd =
      "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\""
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCmd, "")

    // Initialize appDataDir
    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val inspector = InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar)
    val remotePath = injectionManager.pushInspectorPayload(inspector)

    assertThat(remotePath).isEqualTo("/data/local/tmp/ui-inspector/my-inspector.jar")

    // Verify the inspector jar was pushed to tmp
    val pushedPaths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(pushedPaths).contains("/data/local/tmp/ui-inspector/my-inspector.jar.test.tmp")
  }

  @Test
  fun testQueryAppDataDir_Fails() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")

    // Configure run-as pwd to fail
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "run-as $packageName pwd",
      stdout = "",
      stderr = "run-as: package not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected IllegalStateException for failing run-as pwd")
    } catch (e: IllegalStateException) {
      assertThat(e.message)
        .contains(
          "Failed to access the application '$packageName'. Please make sure the app is installed, debuggable, and running under the current user."
        )
    }
  }

  @Test
  fun testGetPid_Fails() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")

    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    // Configure pgrep to fail
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'",
      stdout = "",
      stderr = "",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected IllegalStateException for failing pidof")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("The application '$packageName' is not running on the device. Please start the app and try again.")
    }
  }

  @Test
  fun testGetPid_adbException_propagates() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    // Note: pgrep shell command is deliberately unconfigured so FakeAdbDeviceServices throws an exception simulating an ADB failure.

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected exception for unconfigured pgrep command")
    } catch (e: Exception) {
      assertThat(e.message).doesNotContain("The application '$packageName' is not running on the device")
      assertThat(e.message).contains("Command not setup")
    }
  }

  @Test
  fun testInvalidPackageName_Throws() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, deviceSerial, "com.example; id", agentPathResolver, dummyJar, dummyPayload)
      }
    assertThat(exception.message).contains("Invalid package name")
  }

  @Test
  fun testLongPackageName_Throws() {
    val longPackageName = "a".repeat(256)
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, deviceSerial, longPackageName, agentPathResolver, dummyJar, dummyPayload)
      }
    assertThat(exception.message).contains("Invalid package name")
  }

  @Test
  fun testInvalidSerial_Throws() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, "serial; rm -rf /", packageName, agentPathResolver, dummyJar, dummyPayload)
      }
    assertThat(exception.message).contains("Invalid serial number")
  }

  @Test
  fun testUnsupportedApi_Throws() = runTest {
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n27\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected IllegalStateException for unsupported API level")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("The UI Inspector only supports API level ${ProtocolConstants.MIN_SUPPORTED_API_LEVEL} and above")
      assertThat(e.message).contains("running API level 27")
    }
  }

  @Test
  fun testFailedToRetrieveSdkVersion_Throws() = runTest {
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\ninvalid_sdk\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected IllegalStateException for failed SDK version retrieval")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Failed to retrieve device SDK API level")
    }
  }

  @Test
  fun testFailedToRetrieveAbi_Throws() = runTest {
    val injectionManager = InjectionManager(testSession, deviceSerial, packageName, agentPathResolver, dummyJar, dummyPayload)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "\n30\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected IllegalStateException for failed CPU ABI retrieval")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Failed to retrieve device CPU ABI")
    }
  }

  @Test
  fun testPushInspectorPayload_Cancelled_cleansUpTempFile() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
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
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    // Mock injectAndAttach dependencies so we can initialize appDataDir
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix | grep ui_inspector_1234 || true",
      "ui_inspector_1234\n",
    )

    val agentSetupCmd =
      "run-as $packageName sh -c 'rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, agentSetupCmd, "")
    val attachCmd =
      "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=/data/data/$packageName/lib_ui_inspector_service.jar;/data/data/$packageName/lib_ui_inspector_payload.jar;1234\""
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCmd, "")

    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val inspector = InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar)

    // Configure rm command for the temp file and simulate cancellation during syncSend
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "rm -f '/data/local/tmp/ui-inspector/my-inspector.jar.test.tmp'", "")
    testDeviceServices.throwOnSyncSend = true

    var exceptionThrown = false
    try {
      injectionManager.pushInspectorPayload(inspector)
    } catch (e: CancellationException) {
      exceptionThrown = true
    }
    assertThat(exceptionThrown).isTrue()

    // Verify rm command was still invoked despite cancellation
    val rmRequests =
      fakeSession.deviceServices.shellV2Requests.filter { it.command == "rm -f '/data/local/tmp/ui-inspector/my-inspector.jar.test.tmp'" }
    assertThat(rmRequests).isNotEmpty()
  }

  @Test
  fun testInjectAndAttach_multiProcess_topActivitySelection() = runTest {
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        agentPathResolver = agentPathResolver,
        serviceJarPath = dummyJar,
        payloadJarPath = dummyPayload,
        tempFileSuffixGenerator = { "test.tmp" },
      )
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    // pgrep returns two PIDs: 5678 (secondary process) and 1234 (main UI process)
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'",
      "5678\n1234\n",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "dumpsys activity processes | grep top-activity",
      "  Proc #0: adj=top /F/TOP TRM=0 1234:$packageName/u0a123 (top-activity)\n",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    val setupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
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

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false)
    assertThat(port).isEqualTo("12345")
  }

  @Test
  fun testInjectAndAttach_withoutResolutionStackDemand_leavesSettingsUntouched() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()

    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    val settingsCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings ") }
    assertThat(settingsCommands).isEmpty()
  }

  @Test
  fun testInjectAndAttach_globalDebugViewAttributesAlreadyEnabled_skipsWrite() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "1\n$settingsSeparator\nnull\n")

    injectionManager.injectAndAttach(needsDebugViewAttributes = true)

    val settingsCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings ") }
    assertThat(settingsCommands).containsExactly(readSettingsCmd)
  }

  @Test
  fun testInjectAndAttach_perAppSettingAlreadyNamesPackage_skipsWrite() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\n$packageName\n")

    injectionManager.injectAndAttach(needsDebugViewAttributes = true)

    val settingsCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings ") }
    assertThat(settingsCommands).containsExactly(readSettingsCmd)
  }

  @Test
  fun testInjectAndAttach_enablesPerAppDebugViewAttributes_andPrintsNotice() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\nnull\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, putSettingsCmd, "")

    val stderr = captureStderr { injectionManager.injectAndAttach(needsDebugViewAttributes = true) }

    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands).contains(putSettingsCmd)
    assertThat(stderr).contains("settings delete global debug_view_attributes_application_package")
    // The pid is captured before the settings flip: the activity relaunch keeps the process alive, and reading the pid first avoids
    // mutating settings when the target is not running.
    val pgrepIndex = commands.indexOfFirst { it.startsWith("pgrep ") }
    val settingsReadIndex = commands.indexOf(readSettingsCmd)
    assertThat(pgrepIndex).isAtLeast(0)
    assertThat(pgrepIndex).isLessThan(settingsReadIndex)
  }

  @Test
  fun testInjectAndAttach_settingsReadFails_failsWithoutWriting() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, stdout = "", stderr = "boom", exitCode = 1)

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = true)
      fail("Expected IllegalStateException for failing settings read")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("failed with exit code 1")
    }

    val putCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings put ") }
    assertThat(putCommands).isEmpty()
  }

  @Test
  fun testInjectAndAttach_malformedSettingsReadOutput_failsWithoutWriting() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "garbage without the separator\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = true)
      fail("Expected IllegalStateException for malformed settings output")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Unexpected output while reading debug-view-attributes settings")
    }

    val putCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings put ") }
    assertThat(putCommands).isEmpty()
  }

  @Test
  fun testInjectAndAttach_settingsPutFails_throwsWithoutNotice() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\nnull\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, putSettingsCmd, stdout = "", stderr = "denied", exitCode = 1)

    val stderr = captureStderr {
      try {
        injectionManager.injectAndAttach(needsDebugViewAttributes = true)
        fail("Expected IllegalStateException for failing settings put")
      } catch (e: IllegalStateException) {
        assertThat(e.message).contains("failed with exit code 1")
      }
    }

    assertThat(stderr).doesNotContain("settings delete global")
  }

  @Test
  fun testRemoveAdbForward_killsForwardOnce() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    injectionManager.injectAndAttach(needsDebugViewAttributes = false)

    injectionManager.removeAdbForward()
    // A second call is a no-op: the forward was already removed.
    injectionManager.removeAdbForward()

    assertThat(testHostServices.recordedKillForwardCalls).hasSize(1)
    val (device, localSpec) = testHostServices.recordedKillForwardCalls.single()
    assertThat(device.toString()).contains(deviceSerial)
    assertThat(localSpec.toQueryString()).isEqualTo("tcp:12345")
  }

  @Test
  fun testRemoveAdbForward_noopWhenInjectionFailedBeforeForward() = runTest {
    val injectionManager = createInjectionManager()
    // Nothing configured: injectAndAttach fails at the first device query, before any forward is created.
    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false)
      fail("Expected injection to fail")
    } catch (e: Exception) {}

    injectionManager.removeAdbForward()

    assertThat(testHostServices.recordedKillForwardCalls).isEmpty()
  }

  @Test
  fun testDoDumpUi_removesForwardWhenConnectionFails() = runTest {
    configureSuccessfulInjection()
    // Forward to a port that is guaranteed closed, so the CLI's socket connection fails right after a successful
    // injection: the cleanup in runWithConnectedInspectors must still remove the forward.
    testHostServices.forwardedPort = findClosedPort().toString()

    try {
      doDumpUiWithNoopPrinter()
      fail("Expected connection failure")
    } catch (e: Exception) {}

    assertThat(testHostServices.recordedKillForwardCalls).hasSize(1)
    assertThat(testHostServices.recordedKillForwardCalls.single().second.toQueryString()).isEqualTo("tcp:${testHostServices.forwardedPort}")
  }

  @Test
  fun testDoDumpUi_killForwardFailure_preservesPrimaryFailure() = runTest {
    configureSuccessfulInjection()
    testHostServices.forwardedPort = findClosedPort().toString()
    testHostServices.throwOnKillForward = true

    var thrown: Exception? = null
    val stderr = captureStderr {
      try {
        doDumpUiWithNoopPrinter()
        fail("Expected connection failure")
      } catch (e: Exception) {
        thrown = e
      }
    }

    // The connection failure stays the primary error; the cleanup failure is only a warning.
    assertThat(thrown!!.message).doesNotContain("Simulated killForward failure")
    assertThat(stderr).contains("failed to remove adb forward")
  }

  /**
   * Runs a plain [doDumpUi] with all facets off and a printer that discards output, routing [injectionManagerFactory] to this test's dummy
   * artifact paths for the duration of the call.
   */
  private suspend fun doDumpUiWithNoopPrinter() {
    val noopPrinter =
      object : UiDumpPrinter {
        override fun printDump(uiDump: UiDump) {}
      }
    doDumpUi(
      adbSession = testSession,
      serial = deviceSerial,
      packageName = packageName,
      includeAttributes = false,
      includeResolutionStack = false,
      includeSystemComposables = false,
      includeSemantics = false,
      composeInspectorJarPath = null,
      printer = noopPrinter,
      injectionManagerFactory = { session, serial, pkg ->
        InjectionManager(session, serial, pkg, agentPathResolver, dummyJar, dummyPayload, tempFileSuffixGenerator = { "test.tmp" })
      },
    )
  }

  /** Returns a local TCP port that nothing is listening on. */
  private fun findClosedPort(): Int = ServerSocket(0).use { it.localPort }

  @Test
  fun testRemoveAdbForward_killFailure_warnsInsteadOfThrowing() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    injectionManager.injectAndAttach(needsDebugViewAttributes = false)
    testHostServices.throwOnKillForward = true

    val stderr = captureStderr { injectionManager.removeAdbForward() }

    assertThat(stderr).contains("failed to remove adb forward")
    assertThat(testHostServices.recordedKillForwardCalls).isEmpty()
  }

  private fun createInjectionManager() =
    InjectionManager(
      testSession,
      deviceSerial,
      packageName,
      agentPathResolver,
      dummyJar,
      dummyPayload,
      tempFileSuffixGenerator = { "test.tmp" },
    )

  /** Configures every shell command of the happy-path injection flow, except the debug-view-attributes settings commands. */
  private fun configureSuccessfulInjection() {
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pgrep -f '^${packageName.replace(".", "\\.")}(:.*)?$'", "1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    val setupCmd =
      "run-as $packageName sh -c '" +
        "rm -f lib_ui_inspector_agent.so lib_ui_inspector_service.jar lib_ui_inspector_payload.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_agent.so > lib_ui_inspector_agent.so && " +
        "cat /data/local/tmp/lib_ui_inspector_service.jar > lib_ui_inspector_service.jar && " +
        "cat /data/local/tmp/lib_ui_inspector_payload.jar > lib_ui_inspector_payload.jar && " +
        "chmod 444 lib_ui_inspector_agent.so && " +
        "chmod 444 lib_ui_inspector_service.jar && " +
        "chmod 444 lib_ui_inspector_payload.jar'"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
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
  }

  /** Runs [block] with [System.err] redirected and returns everything it printed. */
  private inline fun captureStderr(block: () -> Unit): String {
    val originalErr = System.err
    val buffer = ByteArrayOutputStream()
    System.setErr(PrintStream(buffer))
    try {
      block()
    } finally {
      System.setErr(originalErr)
    }
    return buffer.toString()
  }
}
