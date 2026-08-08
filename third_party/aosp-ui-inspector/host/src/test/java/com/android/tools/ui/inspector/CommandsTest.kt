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
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandsTest {

  private val fakeSession = FakeAdbSession()
  private val selector = DeviceSelector.fromSerialNumber("abc")

  private fun configureTopActivityOutput(stdout: String, exitCode: Int = 0) {
    fakeSession.deviceServices.configureShellCommand(selector, TOP_ACTIVITY_SHELL_COMMAND, stdout, exitCode = exitCode)
  }

  @Test
  fun testResolveDeviceSerialReturnsRequestedSerialUnchanged() {
    fakeSession.hostServices.devices = DeviceList(emptyList(), emptyList())

    val serial = runBlocking { resolveDeviceSerial(fakeSession, "requested-serial") }

    assertThat(serial).isEqualTo("requested-serial")
  }

  @Test
  fun testResolveDeviceSerialUsesSoleOnlineDevice() {
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo("abc", DeviceState.ONLINE)), emptyList())

    val serial = runBlocking { resolveDeviceSerial(fakeSession, null) }

    assertThat(serial).isEqualTo("abc")
  }

  @Test
  fun testResolveDeviceSerialIgnoresNonOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("offline-device", DeviceState.OFFLINE), DeviceInfo("online-device", DeviceState.ONLINE)), emptyList())

    val serial = runBlocking { resolveDeviceSerial(fakeSession, null) }

    assertThat(serial).isEqualTo("online-device")
  }

  @Test
  fun testResolveDeviceSerialFailsWithNoDevices() {
    fakeSession.hostServices.devices = DeviceList(emptyList(), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveDeviceSerial(fakeSession, null) } }

    assertThat(exception).hasMessageThat().contains("No connected devices found")
  }

  @Test
  fun testResolveDeviceSerialFailsWithNoOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("xyz", DeviceState.OFFLINE), DeviceInfo("abc", DeviceState.UNAUTHORIZED)), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveDeviceSerial(fakeSession, null) } }

    assertThat(exception).hasMessageThat().contains("No online devices found")
    assertThat(exception).hasMessageThat().contains("abc (unauthorized), xyz (offline)")
  }

  @Test
  fun testResolveDeviceSerialFailsWithMultipleOnlineDevices() {
    fakeSession.hostServices.devices =
      DeviceList(listOf(DeviceInfo("device-b", DeviceState.ONLINE), DeviceInfo("device-a", DeviceState.ONLINE)), emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveDeviceSerial(fakeSession, null) } }

    assertThat(exception).hasMessageThat().contains("Multiple online devices found: device-a, device-b")
    assertThat(exception).hasMessageThat().contains("--device")
  }

  @Test
  fun testResolveTargetPackageReturnsRequestedPackageUnchanged() {
    // No shell command is configured: FakeAdbSession throws on any shell request, so success proves no device query.
    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", "com.requested") }

    assertThat(packageName).isEqualTo("com.requested")
  }

  @Test
  fun testResolveTargetPackageUsesForegroundApp() {
    configureTopActivityOutput("    APP  UID 1234:com.example/u0a123 (top-activity)\n")

    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", null) }

    assertThat(packageName).isEqualTo("com.example")
  }

  @Test
  fun testResolveTargetPackageNormalizesSubprocessNames() {
    configureTopActivityOutput("    APP  UID 1234:com.example:ui/u0a123 (top-activity)\n")

    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", null) }

    assertThat(packageName).isEqualTo("com.example")
  }

  @Test
  fun testResolveTargetPackageAcceptsMultipleProcessesOfSamePackage() {
    configureTopActivityOutput(
      "    APP  UID 1234:com.example/u0a123 (top-activity)\n    APP  UID 5678:com.example:render/u0a123 (top-activity)\n"
    )

    val packageName = runBlocking { resolveTargetPackage(fakeSession, "abc", null) }

    assertThat(packageName).isEqualTo("com.example")
  }

  @Test
  fun testResolveTargetPackageFailsWhenNoForegroundApp() {
    // grep exits with code 1 and empty stdout when nothing matches.
    configureTopActivityOutput("", exitCode = 1)

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveTargetPackage(fakeSession, "abc", null) } }

    assertThat(exception).hasMessageThat().contains("Could not determine the foreground app")
    assertThat(exception).hasMessageThat().contains("--package")
  }

  @Test
  fun testResolveTargetPackageFailsWithMultipleForegroundApps() {
    configureTopActivityOutput("    APP  UID 1234:com.second/u0a123 (top-activity)\n    APP  UID 5678:com.first/u0a124 (top-activity)\n")

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { resolveTargetPackage(fakeSession, "abc", null) } }

    assertThat(exception).hasMessageThat().contains("Multiple foreground apps found: com.first, com.second")
    assertThat(exception).hasMessageThat().contains("--package")
  }
}
