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

import com.android.adblib.ConnectedDevice
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.serialNumber
import com.android.adblib.tools.createStandaloneSession
import com.android.adblib.tools.debugging.appProcessTracker
import com.android.adblib.tools.debugging.debuggable
import com.android.adblib.tools.debugging.retrieveProcessName
import com.google.common.truth.Truth.assertThat
import java.net.Socket
import java.nio.file.Paths
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val SERVICE_JAR_PATH = "tools/base/ui-inspector/agent/service/lib_ui_inspector_service.jar"
private const val PAYLOAD_JAR_PATH = "tools/base/ui-inspector/agent/inspector/lib_ui_inspector_payload.jar"

/**
 * Integration test for [InjectionManager].
 *
 * This test performs an end-to-end verification of the injection process:
 * 1. Locates connected devices.
 * 2. Pushes the agent and service files to the device.
 * 3. Attaches the agent to the target application.
 * 4. Sets up ADB port forwarding.
 * 5. Verifies connectivity by opening a socket to the forwarded port.
 *
 * **Note**: This test requires a real device or emulator with a running debuggable application. If no devices are connected, the test is
 * skipped. The test will attempt to dynamically find a running debuggable application on the device. If no debuggable application is found,
 * the test is skipped.
 */
class InjectionManagerIntegrationTest {
  @Test
  fun testIntegration() = runTest {
    val adbSession = createStandaloneSession()
    val connectedDevice = adbSession.connectedDevicesTracker.connectedDevices.value.firstOrNull()
    if (connectedDevice == null) {
      // No devices connected! Skipping integration test.
      return@runTest
    }
    val serial = connectedDevice.serialNumber
    val packageName = findDebuggableApp(connectedDevice)
    assertThat(packageName).isNotNull()

    val injectionManager =
      InjectionManager(adbSession = adbSession, serial = serial, packageName = packageName!!, serviceJarPath = Paths.get(SERVICE_JAR_PATH))

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false)
    assertThat(port).isNotEmpty()

    val socket = Socket("localhost", port.toInt())
    assertThat(socket.isConnected).isTrue()
    socket.close()
  }

  private suspend fun findDebuggableApp(device: ConnectedDevice): String? {
    val appTracker = device.appProcessTracker
    val appList = appTracker.appProcessFlow.value
    val debuggableApp = appList.firstOrNull { it.debuggable } ?: return null
    return try {
      debuggableApp.retrieveProcessName()
    } catch (e: Exception) {
      null
    }
  }
}
