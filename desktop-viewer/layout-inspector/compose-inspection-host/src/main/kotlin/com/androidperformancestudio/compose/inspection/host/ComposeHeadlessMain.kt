package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.adb.AdbProcessRunner
import com.androidperformancestudio.compose.inspection.*
import com.androidperformancestudio.protocol.ProtocolCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess
import kotlinx.serialization.json.*

/** One explicitly requested operation. Preflight never attaches or downloads an inspector. */
fun main(arguments: Array<String>) {
    try {
        require(arguments.isNotEmpty()) { "Expected preflight or capture" }
        require(arguments.drop(1).size % 2 == 0) { "Expected --name value arguments" }
        val options = arguments.drop(1).chunked(2).associate { it[0] to it[1] }
        require(options.size * 2 == arguments.size - 1) { "Duplicate arguments" }
        fun option(name: String) = options[name] ?: error("Missing $name")
        val receiptPath = Path.of(option("--receipt"))
        val serial = option("--serial")
        val packageName = option("--package")
        val pid = option("--pid").toInt().also { require(it > 0) }
        val runner = AdbProcessRunner(Path.of(option("--adb")))
        val resolver = ComposeInspectorArtifactResolver(Path.of(option("--cache")))
        val manager = ComposeInjectionManager(runner, resolver)
        val prepared = manager.preflight(serial, packageName, Path.of(option("--bundle")), pid)
        val identity = prepared.preflight.identityJson()
        when (arguments[0]) {
            "preflight" -> {
                val receipt = buildJsonObject {
                    put("authorizationId", UUID.randomUUID().toString())
                    put("identity", identity)
                    put("inspectorSource", prepared.preflight.inspectorSource)
                    put("inspectorDownloadRequired", prepared.preflight.inspectorDownloadRequired)
                    put("performanceNotice", "Injects an inspector into this debuggable process; may download the exact Compose inspector, access runtime UI data, and affect app performance. No performance bound is guaranteed.")
                }
                Files.writeString(receiptPath, receipt.toString(), java.nio.file.StandardOpenOption.CREATE_NEW)
                if (receiptPath.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(receiptPath, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                }
                println(receipt)
            }
            "capture" -> {
                val receipt = Json.parseToJsonElement(Files.readString(receiptPath)).jsonObject
                require(option("--authorize") == receipt.getValue("authorizationId").jsonPrimitive.content) {
                    "Explicit authorization must match the preflight receipt"
                }
                require(receipt.getValue("identity") == identity) { "Compose target or agent bundle changed; preflight and authorize again" }
                val marker = Path.of(option("--cancel-file"))
                check(!Files.exists(marker)) { "Compose capture cancelled" }
                manager.attach(prepared, ComposeInspectionAuthorization.authorize(prepared.preflight)).use { injection ->
                    check(!Files.exists(marker)) { "Compose capture cancelled" }
                    ComposeLiveSession.open(injection, resolver, runner, requireNotNull(prepared.preflight.composeVersion)).use { session ->
                        check(!Files.exists(marker)) { "Compose capture cancelled" }
                        val capture = session.capture(hideSystemComposables = false)
                        val pending = ArrayDeque<ComposableNode>()
                        pending.addAll(capture.composeInspection.frame.roots.flatMap { it.nodes })
                        val details = linkedMapOf<Long, ComposableDetail>()
                        val coverage = mutableListOf<ComposeDetailCoverage>()
                        val detailDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                        while (pending.isNotEmpty()) {
                            check(!Files.exists(marker)) { "Compose capture cancelled" }
                            val node = pending.removeFirst()
                            pending.addAll(node.children)
                            if (details.size >= 100 || System.nanoTime() >= detailDeadline) {
                                coverage += ComposeDetailCoverage(node.id, "parameters", ComposeDetailCoverageState.NOT_COLLECTED, reason = "Headless capture detail budget: 100 nodes or 30 seconds")
                                continue
                            }
                            val detail = session.loadDetail(node.id, node.anchorHash)
                            details[node.id] = detail
                            coverage += ComposeDetailCoverage(node.id, "parameters", ComposeDetailCoverageState.COLLECTED, loadedElements = detail.parameters.size)
                        }
                        val enriched = capture.copy(composeInspection = capture.composeInspection.copy(
                            frame = capture.composeInspection.frame.copy(details = details, coverage = coverage),
                        ))
                        check(!Files.exists(marker)) { "Compose capture cancelled" }
                        writeHeadlessArchive(Path.of(option("--output")), enriched)
                    }
                }
                println(buildJsonObject { put("status", "captured") })
            }
            else -> error("Expected preflight or capture")
        }
    } catch (error: Exception) {
        System.err.println(buildJsonObject { put("error", error.message ?: error.javaClass.simpleName) })
        exitProcess(1)
    }
}

private fun ComposeInspectionPreflight.identityJson() = buildJsonObject {
    put("serial", serial)
    put("packageName", packageName)
    put("pid", pid)
    put("apiLevel", apiLevel)
    put("abi", abi)
    put("bundleFingerprint", bundleFingerprint)
    put("composeVersion", composeVersion)
    put("appRestartRequired", appRestartRequired)
}

/** Archive v2 wire schema, independent of the desktop presentation module. */
private fun writeHeadlessArchive(target: Path, capture: ComposeLiveCapture) {
    val snapshot = capture.snapshot
    val content = linkedMapOf(
        "capture/layout-snapshot.json" to ProtocolCodec(1).encodeSnapshot(snapshot).toByteArray(Charsets.UTF_8),
        "capture/compose-inspection.json" to ComposeInspectionJson().encode(capture.composeInspection).toByteArray(Charsets.UTF_8),
    )
    capture.screenshotPng?.let { content["capture/screenshot.png"] = it }
    content.forEach { (path, bytes) ->
        val maximum = if (path.endsWith("compose-inspection.json")) 64 else 32
        require(bytes.size <= maximum * 1024 * 1024) { "Archive entry too large: $path" }
    }
    val manifest = buildJsonObject {
        put("format", "agentperf-inspector-capture")
        put("archiveVersion", 2)
        put("producerVersion", "aps-compose-headless-1")
        put("packageName", snapshot.packageName)
        put("capturedAtEpochMillis", snapshot.capturedAtEpochMillis)
        put("protocolMajor", snapshot.protocolVersion.major)
        put("protocolMinor", snapshot.protocolVersion.minor)
        putJsonArray("entries") {
            content.forEach { (path, bytes) ->
                addJsonObject {
                    put("path", path)
                    put("size", bytes.size)
                    put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    put("required", path == "capture/layout-snapshot.json")
                }
            }
        }
    }
    val temporary = Files.createTempFile(target.toAbsolutePath().parent, ".aps-compose-", ".tmp")
    try {
        ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
            (linkedMapOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8)) + content).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        Files.move(temporary, target, ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
