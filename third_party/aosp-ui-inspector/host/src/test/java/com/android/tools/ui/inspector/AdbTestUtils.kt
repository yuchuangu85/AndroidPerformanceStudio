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

import com.android.adblib.AdbDeviceServices
import com.android.adblib.AdbDeviceSyncServices
import com.android.adblib.AdbHostServices
import com.android.adblib.AdbInputChannel
import com.android.adblib.AdbOutputChannel
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.DirectoryEntry
import com.android.adblib.DirectoryEntryV2
import com.android.adblib.FileStat
import com.android.adblib.FileStatV2
import com.android.adblib.RemoteFileMode
import com.android.adblib.SocketSpec
import com.android.adblib.SyncProgress
import com.android.adblib.testing.FakeAdbDeviceServices
import com.android.adblib.testing.FakeAdbHostServices
import com.android.adblib.testing.FakeAdbSession
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** A custom [AdbSession] for testing that allows overriding [deviceServices] and [hostServices]. */
class TestAdbSession(
  val delegate: FakeAdbSession,
  override val deviceServices: AdbDeviceServices = delegate.deviceServices,
  override val hostServices: AdbHostServices = delegate.hostServices,
) : AdbSession by delegate

/** A custom [AdbHostServices] for testing that provides a fake implementation of [forward]. */
class TestAdbHostServices(val delegate: FakeAdbHostServices) : AdbHostServices by delegate {
  var forwardedPort: String? = "12345"
  val recordedForwardCalls = mutableListOf<Triple<DeviceSelector, SocketSpec, SocketSpec>>()
  val recordedKillForwardCalls = mutableListOf<Pair<DeviceSelector, SocketSpec>>()
  var throwOnKillForward: Boolean = false

  override suspend fun forward(device: DeviceSelector, local: SocketSpec, remote: SocketSpec, rebind: Boolean): String? {
    recordedForwardCalls.add(Triple(device, local, remote))
    return forwardedPort
  }

  override suspend fun killForward(device: DeviceSelector, local: SocketSpec) {
    if (throwOnKillForward) {
      throw IllegalStateException("Simulated killForward failure")
    }
    recordedKillForwardCalls.add(device to local)
  }
}

/**
 * A custom [AdbDeviceServices] for testing that provides a fake implementation of [sync].
 *
 * This is needed because the official [FakeAdbDeviceServices] in `adblib` does not implement [sync].
 *
 * It also records calls to `syncSend` for verification in tests.
 */
class TestAdbDeviceServices(val delegate: FakeAdbDeviceServices) : AdbDeviceServices by delegate {
  data class SyncSendParams(val remoteFilePath: String, val remoteFileMode: RemoteFileMode)

  val recordedSyncSends = mutableListOf<SyncSendParams>()
  var throwOnSyncSend: Boolean = false

  override suspend fun sync(device: DeviceSelector, readAheadBufferSize: Int, writeBackBufferSize: Int): AdbDeviceSyncServices {
    return object : AdbDeviceSyncServices {
      override suspend fun send(
        sourceChannel: AdbInputChannel,
        remoteFilePath: String,
        remoteFileMode: RemoteFileMode,
        remoteFileTime: FileTime?,
        progress: SyncProgress?,
        bufferSize: Int,
      ) {
        if (throwOnSyncSend) {
          throw CancellationException("Simulated syncSend cancellation")
        }
        recordedSyncSends.add(SyncSendParams(remoteFilePath, remoteFileMode))
      }

      override suspend fun shutdown() {}

      override suspend fun recv(remoteFilePath: String, destinationChannel: AdbOutputChannel, progress: SyncProgress?, bufferSize: Int) {
        throw NotImplementedError("Not needed for testing")
      }

      override suspend fun stat(remoteFilePath: String): FileStat? = throw NotImplementedError("Not needed for testing")

      override fun list(remoteFilePath: String, options: AdbDeviceSyncServices.ListOptions): Flow<DirectoryEntry> =
        throw NotImplementedError("Not needed for testing")

      override suspend fun statV2(remoteFilePath: String, options: AdbDeviceSyncServices.StatV2Options): FileStatV2 =
        throw NotImplementedError("Not needed for testing")

      override fun listV2(remoteFilePath: String, options: AdbDeviceSyncServices.ListV2Options): Flow<DirectoryEntryV2> =
        throw NotImplementedError("Not needed for testing")

      override fun close() {}
    }
  }
}
