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

import com.android.adblib.AdbSession
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.RemoteFileMode
import com.android.adblib.ShellCommandOutput
import com.android.adblib.SocketSpec
import com.android.adblib.shellAsText
import com.android.adblib.syncSend
import com.android.tools.ui.inspector.common.ProtocolConstants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.nameWithoutExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The name of the agent binary file. */
private const val AGENT_FILE_NAME = "lib_ui_inspector_agent.so"

/** The name of the service jar file. */
private const val SERVICE_JAR_FILE_NAME = "lib_ui_inspector_service.jar"

/**
 * A globally writable directory on the device used as a staging area for pushing agent binaries before they are moved to the app's private
 * directory.
 */
private const val DEVICE_TMP_DIR = "/data/local/tmp"

/** Subdirectory in the device temporary directory for dynamically pushed inspector jars. */
private const val DEVICE_TMP_INSPECTORS_DIR = "$DEVICE_TMP_DIR/ui-inspector"

/** The name of the payload jar file. */
private const val PAYLOAD_JAR_FILE_NAME = "lib_ui_inspector_payload.jar"

/** The relative path to the payload jar in the runfiles. */
private const val HOST_PAYLOAD_JAR_PATH = "tools/base/ui-inspector/agent/payload/$PAYLOAD_JAR_FILE_NAME"

/** The temporary path on the device where the payload jar is first pushed. */
private const val DEVICE_TMP_PAYLOAD_JAR_PATH = "$DEVICE_TMP_DIR/$PAYLOAD_JAR_FILE_NAME"

/** The relative path to the agent directory in the source tree or runfiles. */
private const val HOST_AGENT_PATH = "tools/base/ui-inspector/agent/native/$AGENT_FILE_NAME"

/** The relative path to the service jar in the runfiles. */
private const val HOST_SERVICE_JAR_PATH = "tools/base/ui-inspector/agent/service/$SERVICE_JAR_FILE_NAME"

/** The temporary path on the device where the agent is first pushed. */
private const val DEVICE_TMP_AGENT_PATH = "$DEVICE_TMP_DIR/$AGENT_FILE_NAME"

/** The temporary path on the device where the service jar is first pushed. */
private const val DEVICE_TMP_SERVICE_JAR_PATH = "$DEVICE_TMP_DIR/$SERVICE_JAR_FILE_NAME"

/** Default resolver that locates the agent binary in the Bazel runfiles directory. It uses the device ABI to find the correct binary. */
private val DEFAULT_AGENT_PATH_RESOLVER: (String) -> Path = { abi -> Paths.get(HOST_AGENT_PATH, abi, AGENT_FILE_NAME) }

/** The per-app setting that makes the platform expose attribute resolution stacks for a single package. */
private const val DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING = "debug_view_attributes_application_package"

/** Marker separating the two `settings get` outputs when both settings are read in a single shell invocation. */
private const val SETTINGS_OUTPUT_SEPARATOR = "__UI_INSPECTOR_SETTINGS_SEPARATOR__"

/** Reads the global and per-app debug-view-attributes settings in one shell invocation. */
private const val READ_DEBUG_VIEW_ATTRIBUTES_CMD =
  "settings get global debug_view_attributes ; echo $SETTINGS_OUTPUT_SEPARATOR ; settings get global $DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING"

/**
 * Manages the lifecycle of injecting and attaching the native agent to a target application.
 *
 * @param adbSession The [AdbSession] to use for device communication.
 * @param agentPathResolver A function that takes a device ABI string and returns the [Path] to the agent binary on the host.
 * @param tempFileSuffixGenerator A function that generates unique suffixes for temporary files pushed to the device.
 */
class InjectionManager(
  private val adbSession: AdbSession,
  val serial: String,
  val packageName: String,
  private val agentPathResolver: (String) -> Path = DEFAULT_AGENT_PATH_RESOLVER,
  private val serviceJarPath: Path = Paths.get(HOST_SERVICE_JAR_PATH),
  private val payloadJarPath: Path = Paths.get(HOST_PAYLOAD_JAR_PATH),
  internal val tempFileSuffixGenerator: () -> String = { "${UUID.randomUUID()}.tmp" },
) {
  init {
    validateSerial(serial)
    validatePackageName(packageName)
  }

  private val deviceSelector = DeviceSelector.fromSerialNumber(serial)

  /**
   * The absolute path to the application's private data directory on the device, queried via `run-as`. This is queried at runtime to
   * correctly support multi-user environments (e.g. /data/user/10/) where the standard /data/data/ prefix is not applicable.
   */
  @Volatile private var appDataDir: String? = null

  /** The local TCP spec of the adb forward created by [injectAndAttach]. Cleared by [removeAdbForward]. */
  private val forwardedPortSpec = AtomicReference<SocketSpec.Tcp?>(null)

  /**
   * Push the agent to the device and attach it to the specified app.
   *
   * @param needsDebugViewAttributes Whether the platform must expose attribute resolution stacks for the inspected app, i.e. whether the
   *   `resolution-stack` facet was requested. When true, the per-app debug-view-attributes setting is enabled (see
   *   [enableDebugViewAttributes]); when false, device settings are left untouched.
   * @return The forwarded TCP port number on the host. Connect to this port to communicate with the agent.
   */
  suspend fun injectAndAttach(needsDebugViewAttributes: Boolean): String = coroutineScope {
    val (deviceAbi, sdkVersion) = retrieveDeviceMetadata(deviceSelector)
    if (sdkVersion < ProtocolConstants.MIN_SUPPORTED_API_LEVEL) {
      throw IllegalStateException(
        "The UI Inspector only supports API level ${ProtocolConstants.MIN_SUPPORTED_API_LEVEL} and above. The target device is running API level $sdkVersion."
      )
    }

    // Query app data dir and pid synchronously at the beginning to verify that the app is installed and running. The pid is deliberately
    // captured before any debug-view-attributes flip: the flip only relaunches activities within the existing process, so the pid stays
    // valid, and reading it first avoids mutating device settings when the target is not running.
    appDataDir = queryAppDataDir(deviceSelector, packageName)
    val pid = getPid(deviceSelector, packageName)

    // Resolve local paths before touching device settings, so a missing host artifact cannot restart the app's activities for nothing.
    val agentLocalPath = getAgentLocalPath(deviceAbi)
    val serviceJarLocalPath = getServiceJarLocalPath()
    val payloadJarLocalPath = getPayloadJarLocalPath()

    val debugViewAttributesSetup = async { if (needsDebugViewAttributes) enableDebugViewAttributes() }

    val agentPush = async { pushFileToDevice(deviceSelector, agentLocalPath, DEVICE_TMP_AGENT_PATH) }
    val jarPush = async { pushFileToDevice(deviceSelector, serviceJarLocalPath, DEVICE_TMP_SERVICE_JAR_PATH) }
    val payloadPush = async { pushFileToDevice(deviceSelector, payloadJarLocalPath, DEVICE_TMP_PAYLOAD_JAR_PATH) }

    val agentRemoteTmpPath = agentPush.await()
    val serviceJarRemoteTmpPath = jarPush.await()
    val payloadRemoteTmpPath = payloadPush.await()
    debugViewAttributesSetup.await()

    copyAndSetupFiles(deviceSelector, packageName, agentRemoteTmpPath, serviceJarRemoteTmpPath, payloadRemoteTmpPath)

    val deviceTime = queryDeviceTime(deviceSelector)
    attachAgent(deviceSelector, pid)

    val socketName = ProtocolConstants.getSocketName(pid)
    waitForAgentSocket(deviceSelector, socketName, pid, deviceTime)

    setupAdbForward(deviceSelector, socketName)
  }

  /**
   * Makes the platform expose attribute resolution stacks for the inspected app by enabling the per-app
   * [DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING] setting.
   *
   * The setting is read before writing: if the global `debug_view_attributes` is already enabled (developer options, or residue from older
   * builds of this tool that set it globally) or the per-app setting already names [packageName], no device mutation happens. When the
   * setting is actually flipped it is left set. Changing it in either direction restarts the app's activities, so clearing it per run would
   * pay two restarts. Set-and-leave confines the restart to the first resolution-stack request per app.
   */
  private suspend fun enableDebugViewAttributes() {
    val output = runShellCommand(deviceSelector, READ_DEBUG_VIEW_ATTRIBUTES_CMD).stdout
    val values = output.split(SETTINGS_OUTPUT_SEPARATOR)
    if (values.size != 2) {
      throw IllegalStateException("Unexpected output while reading debug-view-attributes settings: $output")
    }
    // `settings get` prints the literal "null" for an unset key; exact comparisons below treat it as any other non-matching value.
    val global = values[0].trim()
    val perApp = values[1].trim()
    if (global == "1" || perApp == packageName) {
      return
    }
    runShellCommand(deviceSelector, "settings put global $DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING $packageName")
    System.err.println(
      "Enabled view-attribute debugging for $packageName: its activities will restart now, and the setting stays enabled for this app. " +
        "Clear it with: adb shell settings delete global $DEBUG_VIEW_ATTRIBUTES_PACKAGE_SETTING"
    )
  }

  /** Sets up adb port forwarding to the agent. */
  private suspend fun setupAdbForward(deviceSelector: DeviceSelector, socketName: String): String {
    val localSpec = SocketSpec.Tcp()
    val remoteSpec = SocketSpec.LocalAbstract(socketName)
    val port =
      adbSession.hostServices.forward(deviceSelector, localSpec, remoteSpec) ?: throw IllegalStateException("Failed to set up adb forward")
    forwardedPortSpec.set(SocketSpec.Tcp(port.toInt()))
    return port
  }

  /**
   * Removes the adb forward created by [injectAndAttach], if any. Forwards outlive the CLI process, so every run must remove its own on
   * success, failure, and cancellation alike.
   */
  suspend fun removeAdbForward() {
    val localSpec = forwardedPortSpec.getAndSet(null) ?: return
    try {
      adbSession.hostServices.killForward(deviceSelector, localSpec)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      System.err.println("Warning: failed to remove adb forward ${localSpec.toQueryString()}: ${e.message}")
    }
  }

  /**
   * Waits for the abstract socket to appear in /proc/net/unix. Since the agent acts as the server, we must wait for it to create the socket
   * before the host can connect to it.
   */
  private suspend fun waitForAgentSocket(deviceSelector: DeviceSelector, socketName: String, pid: String, deviceTime: String?) {
    val maxAttempts = 10
    var attempts = 0
    var delayMs = 100L
    while (attempts < maxAttempts) {
      val cmd = "cat /proc/net/unix | grep $socketName || true"
      val output = runShellCommand(deviceSelector, cmd).stdout
      if (output.contains(socketName)) {
        return
      }

      if (attempts >= 2) {
        // If the agent already logged a bootstrap error, throw it immediately, to avoid exponential backoff.
        val agentError = readAgentErrorFromLogcat(deviceSelector, pid, deviceTime)
        if (agentError != null) {
          throw IllegalStateException("Failed to attach UI Inspector agent. Agent error in logcat:\n$agentError")
        }
      }

      attempts++
      delay(delayMs)
      delayMs = (delayMs * 2).coerceAtMost(1000L)
    }
    throw IllegalStateException("Timed out waiting for agent socket $socketName")
  }

  /**
   * Queries logcat for error logs produced by the UI Inspector agent. Filters logs by the target app's PID and the agent's known logging
   * tags.
   */
  private suspend fun readAgentErrorFromLogcat(deviceSelector: DeviceSelector, pid: String, deviceTime: String?): String? {
    try {
      // Query error logs for this process ID, optionally filtering since the start of this injection attempt
      val timeFilter = if (deviceTime != null) " -t '$deviceTime'" else ""
      val cmd = "logcat -d$timeFilter --pid=$pid *:E"
      val output = adbSession.deviceServices.shellAsText(deviceSelector, cmd).stdout.trim()
      if (output.isEmpty()) return null

      val uiInspectorLogs = output.lines().filter { line -> line.contains(ProtocolConstants.LOG_TAG_PREFIX) }

      return if (uiInspectorLogs.isNotEmpty()) uiInspectorLogs.joinToString("\n") else null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return null
    }
  }

  /** Queries the device for its current time in a logcat-compatible format. */
  private suspend fun queryDeviceTime(deviceSelector: DeviceSelector): String? {
    return try {
      // Format matching logcat timestamp: "MM-DD HH:MM:SS.000"
      val result = adbSession.deviceServices.shellAsText(deviceSelector, "date +\"%m-%d %H:%M:%S.000\"")
      val stdout = result.stdout.trim()
      if (result.exitCode == 0 && stdout.isNotEmpty()) stdout else null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
  }

  /** Queries the device for the PID of the target application. */
  private suspend fun getPid(deviceSelector: DeviceSelector, packageName: String): String {
    val candidatePids = getCandidatePids(deviceSelector, packageName)
    if (candidatePids.isEmpty()) {
      throw IllegalStateException("The application '$packageName' is not running on the device. Please start the app and try again.")
    }
    if (candidatePids.size == 1) {
      // If packageName has only one pid associated to it, use that.
      // This is going to be the case for most apps.
      return candidatePids[0]
    }

    // For multi-process applications, check if any of the pids is the pid of the foreground activity
    val topActivityPid = getTopActivityPid(deviceSelector, candidatePids)
    if (topActivityPid != null) {
      return topActivityPid
    }

    // Fall back to the first candidate PID
    return candidatePids[0]
  }

  /** Queries the device for candidate process IDs matching the given package name via `pgrep`. */
  private suspend fun getCandidatePids(deviceSelector: DeviceSelector, packageName: String): List<String> {
    val escapedPackage = packageName.replace(".", "\\.")
    val result = adbSession.deviceServices.shellAsText(deviceSelector, "pgrep -f '^$escapedPackage(:.*)?$'")
    val stdout = result.stdout.trim()
    if (result.exitCode == 0 && stdout.isNotEmpty()) {
      return stdout.split("\\s+".toRegex()).filter { it.isNotEmpty() }.distinct()
    }
    return emptyList()
  }

  /** Queries `dumpsys activity processes` to identify the PID currently hosting the top (foreground) activity. */
  private suspend fun getTopActivityPid(deviceSelector: DeviceSelector, candidatePids: List<String>): String? {
    return try {
      val output = adbSession.deviceServices.shellAsText(deviceSelector, TOP_ACTIVITY_SHELL_COMMAND).stdout
      val candidateSet = candidatePids.toSet()
      parseTopActivityProcesses(output).firstOrNull { it.pid in candidateSet }?.pid
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  /** Queries the device for the absolute path to the app's data directory. */
  private suspend fun queryAppDataDir(deviceSelector: DeviceSelector, packageName: String): String {
    val result = adbSession.deviceServices.shellAsText(deviceSelector, "run-as $packageName pwd")
    val stdout = result.stdout.trim()
    if (result.exitCode != 0 || stdout.isEmpty()) {
      throw IllegalStateException(
        "Failed to access the application '$packageName'. Please make sure the app is installed, debuggable, and running under the current user."
      )
    }
    return stdout
  }

  /** Queries the device for its CPU ABI and SDK API level in a single shell invocation. */
  private suspend fun retrieveDeviceMetadata(deviceSelector: DeviceSelector): DeviceMetadata {
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    val output = runShellCommand(deviceSelector, metadataCmd).stdout
    val lines = output.lines()
    val abi = lines.getOrNull(0)?.trim()
    if (abi.isNullOrEmpty()) {
      throw IllegalStateException("Failed to retrieve device CPU ABI.")
    }
    val sdkVersionStr = lines.getOrNull(1)?.trim()
    val sdkVersion = sdkVersionStr?.toIntOrNull() ?: throw IllegalStateException("Failed to retrieve device SDK API level.")
    return DeviceMetadata(abi, sdkVersion)
  }

  /** Resolves the local path to the agent binary for the given ABI. */
  private fun getAgentLocalPath(deviceAbi: String): Path {
    return resolveLocalPathOrExtractFromClasspath(agentPathResolver(deviceAbi))
  }

  /** Resolves the local path to the service jar. */
  private fun getServiceJarLocalPath(): Path {
    return resolveLocalPathOrExtractFromClasspath(serviceJarPath)
  }

  /** Resolves the local path to the payload jar. */
  private fun getPayloadJarLocalPath(): Path {
    return resolveLocalPathOrExtractFromClasspath(payloadJarPath)
  }

  private fun resolveLocalPathOrExtractFromClasspath(path: Path): Path {
    if (path.toFile().exists()) {
      // Use direct filesystem path when running from local builds, Bazel runfiles, or tests.
      return path
    }
    val resourcePath = "/" + path.invariantSeparatorsPathString
    return extractedResourcesCache.computeIfAbsent(resourcePath) { pathStr ->
      val stream =
        javaClass.getResourceAsStream(pathStr)
          ?: throw IllegalStateException("File not found on filesystem at $path nor in classpath resources at $pathStr")
      val prefix = "ui_inspector_${path.nameWithoutExtension}_"
      val suffix = if (path.extension.isNotEmpty()) ".${path.extension}" else ".tmp"
      val tempFile = Files.createTempFile(prefix, suffix)
      tempFile.toFile().deleteOnExit()
      stream.use { input -> Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING) }
      tempFile
    }
  }

  private suspend fun copyAndSetupFiles(
    deviceSelector: DeviceSelector,
    packageName: String,
    agentRemoteTmpPath: String,
    serviceJarRemoteTmpPath: String,
    payloadRemoteTmpPath: String,
  ) {
    val setupCmd =
      "run-as $packageName sh -c '" +
        // Delete previous versions of the files
        "rm -f $AGENT_FILE_NAME $SERVICE_JAR_FILE_NAME $PAYLOAD_JAR_FILE_NAME && " +
        // Copy new versions from tmp
        "cat $agentRemoteTmpPath > $AGENT_FILE_NAME && " +
        "cat $serviceJarRemoteTmpPath > $SERVICE_JAR_FILE_NAME && " +
        "cat $payloadRemoteTmpPath > $PAYLOAD_JAR_FILE_NAME && " +
        // Set permissions to 444
        "chmod 444 $AGENT_FILE_NAME && " +
        "chmod 444 $SERVICE_JAR_FILE_NAME && " +
        "chmod 444 $PAYLOAD_JAR_FILE_NAME'"
    runShellCommand(deviceSelector, setupCmd)
  }

  /** Pushes a file to a temporary location on the device using an atomic rename to prevent concurrent read/write corruption. */
  // TODO: Add a check to verify file hash on device before pushing to avoid redundant pushes if the file is already there.
  private suspend fun pushFileToDevice(deviceSelector: DeviceSelector, localPath: Path, remoteTmpPath: String): String {
    // App needs read permission to copy it from /data/local/tmp (run-as uses a different user).
    // Setting read-only permissions (444) directly during syncSend also satisfies ART W^X read-only
    // dex file requirements on API 34+ without needing an extra chmod shell round trip.
    val permissions =
      RemoteFileMode.fromPosixPermissions(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)
    val tempRemotePath = "$remoteTmpPath.${tempFileSuffixGenerator()}"
    var moveSuccessful = false
    try {
      adbSession.deviceServices.syncSend(deviceSelector, localPath, tempRemotePath, permissions)
      // Moving a file within the same Linux filesystem is an atomic rename (rename() syscall).
      // Pushing to unique temporary files prevents concurrent transfers from interleaved writing or
      // exposing half-written data. When renamed, the last writer atomically wins without disrupting
      // active readers of the old file inode.
      runShellCommand(deviceSelector, "mv -f '$tempRemotePath' '$remoteTmpPath'")
      moveSuccessful = true
    } finally {
      if (!moveSuccessful) {
        withContext(NonCancellable) {
          try {
            adbSession.deviceServices.shellAsText(deviceSelector, "rm -f '$tempRemotePath'")
          } catch (_: Exception) {}
        }
      }
    }
    return remoteTmpPath
  }

  /**
   * Pushes an inspector payload jar to the device and sets it up in the app's data directory.
   *
   * @param inspector The metadata of the inspector to push.
   * @return The full remote path to the pushed jar file on the device.
   */
  suspend fun pushInspectorPayload(inspector: InspectorMetadata): String {
    val remoteFileName = inspector.localJarPath.fileName.toString()
    val remoteTmpPath = "$DEVICE_TMP_INSPECTORS_DIR/$remoteFileName"

    val actualLocalPath = resolveLocalPathOrExtractFromClasspath(inspector.localJarPath)

    // Push to tmp folder
    pushFileToDevice(deviceSelector, actualLocalPath, remoteTmpPath)

    return remoteTmpPath
  }

  private suspend fun attachAgent(deviceSelector: DeviceSelector, pid: String) {
    val appPath = "$appDataDir/$AGENT_FILE_NAME"
    val appJarPath = "$appDataDir/$SERVICE_JAR_FILE_NAME"
    val appPayloadJarPath = "$appDataDir/$PAYLOAD_JAR_FILE_NAME"
    val attachCmd = "cmd activity attach-agent $pid \"$appPath=$appJarPath;$appPayloadJarPath;$pid\""
    runShellCommand(deviceSelector, attachCmd)
  }

  /** Runs a shell command and throws an exception if it fails (exit code != 0). */
  private suspend fun runShellCommand(deviceSelector: DeviceSelector, command: String): ShellCommandOutput {
    val result = adbSession.deviceServices.shellAsText(deviceSelector, command)
    if (result.exitCode != 0) {
      throw IllegalStateException("Command '$command' failed with exit code ${result.exitCode}. Stderr: ${result.stderr}")
    }
    return result
  }

  companion object {
    /** Cache of resources extracted to temporary disk files, ensuring each resource is only extracted once per JVM lifecycle. */
    private val extractedResourcesCache = ConcurrentHashMap<String, Path>()
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
    private val SERIAL_REGEX = Regex("^[a-zA-Z0-9.:_-]+$")

    private fun validatePackageName(packageName: String) {
      require(packageName.length <= 255 && PACKAGE_NAME_REGEX.matches(packageName)) { "Invalid package name: $packageName" }
    }

    private fun validateSerial(serial: String) {
      require(SERIAL_REGEX.matches(serial)) { "Invalid serial number: $serial" }
    }
  }
}

private data class DeviceMetadata(val abi: String, val sdkVersion: Int)
