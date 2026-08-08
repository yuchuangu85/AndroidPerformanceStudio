package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.AdbCommandFactory
import com.androidperformancestudio.adb.AdbCommandException
import com.androidperformancestudio.adb.AdbProcessRunner
import com.androidperformancestudio.adb.ProcessResult
import com.androidperformancestudio.adb.ProcessRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicBoolean

data class ComposeInspectionPreflight(
    val serial: String,
    val packageName: String,
    val pid: Int,
    val apiLevel: Int,
    val abi: String,
    val bundleFingerprint: String,
    val composeVersion: String? = null,
    val inspectorSource: String? = null,
    val inspectorDownloadRequired: Boolean = true,
    val appRestartRequired: Boolean = false,
    val performanceNotice: String = "Active inspection targets 1–5 Hz and may add up to 5% CPU and 50 MiB memory.",
)

class ComposeInspectionAuthorization private constructor(
    internal val preflight: ComposeInspectionPreflight,
) {
    companion object {
        fun authorize(preflight: ComposeInspectionPreflight): ComposeInspectionAuthorization =
            ComposeInspectionAuthorization(preflight)
    }
}

class ComposeInjectionManager(
    private val processRunner: ProcessRunner = AdbProcessRunner(),
    private val artifactResolver: ComposeInspectorArtifactResolver? = null,
    private val portAllocator: () -> Int = {
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    },
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    fun preflight(
        serial: String,
        packageName: String,
        bundleRoot: Path,
        requestedPid: Int? = null,
        explicitLocalArtifact: Path? = null,
    ): PreparedComposeInspection {
        val api = checked(AdbCommandFactory.getProperty(serial, "ro.build.version.sdk")).stdout.trim().toIntOrNull()
            ?: error("Unable to read Android API level")
        require(api >= 29) { "Compose inspection requires Android API 29 or newer" }
        val abi = checked(AdbCommandFactory.getProperty(serial, "ro.product.cpu.abi")).stdout.trim()
        val bundle = ComposeAgentBundle.load(bundleRoot, abi)
        val appDataDir = checked(AdbCommandFactory.runAsPwd(serial, packageName)).stdout.trim()
        require(appDataDir.startsWith("/data/") && !appDataDir.split('/').contains("..")) {
            "Target app is not debuggable or returned an unsafe data directory"
        }
        val pids = checked(AdbCommandFactory.pidOf(serial, packageName)).stdout
            .trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull).distinct()
        val pid = requestedPid?.also { require(it in pids) { "Target PID is not running" } }
            ?: pids.singleOrNull()
            ?: error(if (pids.isEmpty()) "Target app is not running" else "Target app has multiple processes; select a PID")
        val socketName = socketName(pid)
        require(!checked(AdbCommandFactory.readUnixSockets(serial)).stdout.contains("@$socketName")) {
            "Another Layout Inspector is already attached to PID $pid"
        }
        val composeVersion = detectComposeVersion(serial, packageName)
            ?: error("Jetpack Compose version metadata was not found in the target APK")
        require(isSupportedComposeVersion(composeVersion)) { "Compose 1.2 or newer is required; found $composeVersion" }
        val artifactPlan = artifactResolver?.plan(composeVersion, explicitLocalArtifact)
        return PreparedComposeInspection(
            preflight = ComposeInspectionPreflight(
                serial = serial,
                packageName = packageName,
                pid = pid,
                apiLevel = api,
                abi = abi,
                bundleFingerprint = bundle.fingerprint,
                composeVersion = composeVersion,
                inspectorSource = artifactPlan?.source,
                inspectorDownloadRequired = artifactPlan?.downloadRequired ?: true,
            ),
            bundle = bundle,
            appDataDir = appDataDir,
        )
    }

    private fun detectComposeVersion(serial: String, packageName: String): String? {
        val remotePaths = checked(AdbCommandFactory.packagePaths(serial, packageName)).stdout.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotBlank() }
            .take(MAX_APK_SPLITS)
            .toList()
        val tempDir = Files.createTempDirectory("aps-compose-version-")
        val versions = mutableSetOf<String>()
        try {
            remotePaths.forEachIndexed { index, remote ->
                val local = tempDir.resolve("$index.apk")
                checked(AdbCommandFactory.pullPackageApk(serial, remote, local.toString()))
                require(Files.size(local) <= MAX_APK_BYTES) { "Target APK is too large to inspect safely" }
                ZipInputStream(Files.newInputStream(local)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && entry.name == COMPOSE_VERSION_ENTRY) {
                            zip.readNBytes(MAX_VERSION_BYTES + 1)
                                .also { require(it.size <= MAX_VERSION_BYTES) { "Invalid Compose version metadata" } }
                                .toString(Charsets.UTF_8).trim().takeIf { it.matches(COMPOSE_VERSION_PATTERN) }
                                ?.let(versions::add)
                        }
                    }
                }
            }
            require(versions.size <= 1) { "Conflicting Compose versions were found in target APK splits" }
            return versions.singleOrNull()
        } finally {
            Files.walk(tempDir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun isSupportedComposeVersion(version: String): Boolean {
        val match = Regex("^(\\d+)\\.(\\d+)").find(version) ?: return false
        val (major, minor) = match.destructured
        return major.toInt() > 1 || major.toInt() == 1 && minor.toInt() >= 2
    }

    fun attach(
        prepared: PreparedComposeInspection,
        authorization: ComposeInspectionAuthorization,
        token: InspectorSessionToken = InspectorSessionToken.generate(),
    ): InjectedComposeSession {
        require(authorization.preflight == prepared.preflight) { "Compose inspection authorization is stale" }
        val target = prepared.preflight
        prepared.bundle.verifyUnchanged()
        val currentPids = checked(AdbCommandFactory.pidOf(target.serial, target.packageName)).stdout
            .split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        require(target.pid in currentPids) { "Target PID changed; authorize Compose inspection again" }
        require(!checked(AdbCommandFactory.readUnixSockets(target.serial)).stdout.contains("@${socketName(target.pid)}")) {
            "Another Layout Inspector is already attached to PID ${target.pid}"
        }
        val suffix = token.value.take(32)
        val staging = "/data/local/tmp/aps-ui-inspector-$suffix"
        val privateDir = "${prepared.appDataDir}/code_cache/aps-ui-inspector-$suffix"
        val stagedFiles = listOf("agent.so", "service.jar", "payload.jar", "view.jar").map { "$staging-$it" }
        val privateFiles = listOf("agent.so", "service.jar", "payload.jar", "view.jar").map { "$privateDir/$it" }
        try {
            listOf(
                prepared.bundle.nativeAgent,
                prepared.bundle.serviceJar,
                prepared.bundle.payloadJar,
                prepared.bundle.viewInspectorJar,
            ).zip(stagedFiles).forEach { (local, remote) ->
                checked(AdbCommandFactory.push(target.serial, local.toString(), remote))
            }
            checked(AdbCommandFactory.runAsMkdir(target.serial, target.packageName, privateDir))
            stagedFiles.zip(privateFiles).forEach { (from, to) ->
                checked(AdbCommandFactory.runAsCopy(target.serial, target.packageName, from, to))
                checked(AdbCommandFactory.runAsChmod(target.serial, target.packageName, "444", to))
            }
            checked(AdbCommandFactory.removeRemote(target.serial, stagedFiles))
            val options = "${privateFiles[1]};${privateFiles[2]};${target.pid};${token.value}"
            checked(AdbCommandFactory.attachAgent(target.serial, target.pid, privateFiles[0], options))
            waitForSocket(target.serial, target.pid)
            val port = portAllocator()
            checked(AdbCommandFactory.forward(target.serial, port, socketName(target.pid)))
            return InjectedComposeSession(
                serial = target.serial,
                packageName = target.packageName,
                pid = target.pid,
                hostPort = port,
                token = token,
                privateViewInspectorPath = privateFiles[3],
                processRunner = processRunner,
                cleanupPrivateFiles = privateFiles,
                cleanupStagedFiles = stagedFiles,
            )
        } catch (error: Throwable) {
            processRunner.run(AdbCommandFactory.removeRemote(target.serial, stagedFiles))
            processRunner.run(AdbCommandFactory.runAsRemove(target.serial, target.packageName, privateFiles))
            processRunner.run(AdbCommandFactory.runAsRmdir(target.serial, target.packageName, privateDir))
            throw error
        }
    }

    private fun waitForSocket(serial: String, pid: Int) {
        val expected = "@${socketName(pid)}"
        repeat(10) { attempt ->
            if (checked(AdbCommandFactory.readUnixSockets(serial)).stdout.contains(expected)) return
            sleeper((100L shl attempt.coerceAtMost(3)))
        }
        error("Timed out waiting for Compose inspector agent socket")
    }

    private fun checked(arguments: List<String>): ProcessResult = processRunner.run(arguments).also { result ->
        if (result.exitCode != 0) throw AdbCommandException(arguments, result)
    }

    private fun socketName(pid: Int) = "ui_inspector_$pid"

    private companion object {
        const val MAX_APK_SPLITS = 32
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val MAX_VERSION_BYTES = 256
        const val COMPOSE_VERSION_ENTRY = "META-INF/androidx.compose.ui_ui.version"
        val COMPOSE_VERSION_PATTERN = Regex("[0-9A-Za-z._+-]+")
    }
}

class PreparedComposeInspection internal constructor(
    val preflight: ComposeInspectionPreflight,
    internal val bundle: ComposeAgentBundle,
    internal val appDataDir: String,
)

class InjectedComposeSession internal constructor(
    val serial: String,
    val packageName: String,
    val pid: Int,
    val hostPort: Int,
    val token: InspectorSessionToken,
    val privateViewInspectorPath: String,
    private val processRunner: ProcessRunner,
    private val cleanupPrivateFiles: List<String>,
    private val cleanupStagedFiles: List<String>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val deployedInspectorFiles = mutableListOf<String>()

    fun deployComposeInspector(resolved: ResolvedComposeInspector): String {
        check(!closed.get()) { "Compose injection session is closed" }
        require(resolved.identity.sha256 == resolved.jar.sha256()) { "Compose inspector checksum changed after resolution" }
        val staged = "/data/local/tmp/aps-compose-${resolved.identity.sha256.take(16)}.jar"
        val privateDir = cleanupPrivateFiles.first().substringBeforeLast('/')
        val destination = "$privateDir/compose-${resolved.identity.sha256.take(16)}.jar"
        try {
            checked(AdbCommandFactory.push(serial, resolved.jar.toString(), staged))
            checked(AdbCommandFactory.runAsCopy(serial, packageName, staged, destination))
            synchronized(deployedInspectorFiles) { deployedInspectorFiles += destination }
            checked(AdbCommandFactory.runAsChmod(serial, packageName, "444", destination))
            return destination
        } finally {
            processRunner.run(AdbCommandFactory.removeRemote(serial, listOf(staged)))
        }
    }

    fun isTargetAlive(): Boolean = processRunner.run(AdbCommandFactory.pidOf(serial, packageName))
        .takeIf { it.exitCode == 0 }
        ?.stdout?.split(Regex("\\s+"))?.any { it.toIntOrNull() == pid } == true

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        processRunner.run(AdbCommandFactory.removeForward(serial, hostPort))
        processRunner.run(AdbCommandFactory.removeRemote(serial, cleanupStagedFiles))
        val privateFiles = cleanupPrivateFiles + synchronized(deployedInspectorFiles) { deployedInspectorFiles.toList() }
        processRunner.run(AdbCommandFactory.runAsRemove(serial, packageName, privateFiles))
        processRunner.run(
            AdbCommandFactory.runAsRmdir(serial, packageName, cleanupPrivateFiles.first().substringBeforeLast('/')),
        )
    }

    private fun checked(arguments: List<String>): ProcessResult = processRunner.run(arguments).also { result ->
        if (result.exitCode != 0) throw AdbCommandException(arguments, result)
    }
}
