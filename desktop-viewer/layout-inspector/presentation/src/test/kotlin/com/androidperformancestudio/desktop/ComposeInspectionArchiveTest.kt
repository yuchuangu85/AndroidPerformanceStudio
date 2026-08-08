package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.CapabilityAvailability
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeArchivePrivacy
import com.androidperformancestudio.compose.inspection.ComposeCapability
import com.androidperformancestudio.compose.inspection.ComposeCapabilityState
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.fixtures.SampleSnapshots
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.ProtocolCodec
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ComposeInspectionArchiveTest {
    @TempDir
    lateinit var tempDir: Path

    private val service = CaptureArchiveService(
        archiveCodec = CaptureArchiveCodec(),
        protocolCodec = ProtocolCodec(supportedMajor = 1),
    )

    @Test
    fun `compose details use archive v2 and are redacted by default`() {
        val target = tempDir.resolve("compose.apinspect")
        val snapshot = SampleSnapshots.dashboard

        service.export(
            target = target,
            producerVersion = "test",
            snapshot = snapshot,
            screenshotPng = null,
            rawArtifacts = null,
            composeInspection = inspection(
                packageName = snapshot.packageName,
                capturedAt = snapshot.capturedAtEpochMillis,
                privacy = ComposeArchivePrivacy.SAFE_REDACTED,
            ),
        )

        val document = CaptureArchiveCodec().read(target)
        val imported = service.import(target)
        assertEquals(CAPTURE_ARCHIVE_VERSION, document.archiveVersion)
        assertEquals(
            "<redacted>",
            imported.composeInspection?.frame?.details?.get(1)?.parameters?.single()?.value,
        )
        ZipFile(target.toFile()).use { zip ->
            assertTrue(zip.getEntry(CaptureArchivePaths.COMPOSE_INSPECTION) != null)
        }
    }

    @Test
    fun `layout only export remains archive v1 compatible`() {
        val target = tempDir.resolve("legacy.apinspect")
        val snapshot = SampleSnapshots.dashboard

        service.export(target, "test", snapshot, null, null)

        val document = CaptureArchiveCodec().read(target)
        assertEquals(LEGACY_CAPTURE_ARCHIVE_VERSION, document.archiveVersion)
        assertNull(document.payload.composeInspectionJson)
    }

    @Test
    fun `full fidelity still excludes credentials and session tokens`() {
        val target = tempDir.resolve("full-fidelity.apinspect")
        val snapshot = SampleSnapshots.dashboard
        val source =
            inspection(
                packageName = snapshot.packageName,
                capturedAt = snapshot.capturedAtEpochMillis,
                privacy = ComposeArchivePrivacy.FULL_FIDELITY,
            )
        val detail = source.frame.details.getValue(1)
        val inspection =
            source.copy(
                frame =
                    source.frame.copy(
                        details =
                            mapOf(
                                1L to
                                    detail.copy(
                                        parameters =
                                            listOf(
                                                ComposeValue("title", "String", "Visible title"),
                                                ComposeValue("sessionToken", "String", "eyJhbGciOiJIUzI1NiJ9.payload.signature"),
                                                ComposeValue("password", "String", "hunter2"),
                                                ComposeValue("authorizationHeader", "String", "Bearer secret-access-token"),
                                                ComposeValue("cacheFile", "String", "/storage/emulated/0/Android/data/example/cache/private.bin"),
                                            ),
                                    ),
                            ),
                    ),
            )

        service.export(
            target = target,
            producerVersion = "test",
            snapshot = snapshot,
            screenshotPng = null,
            rawArtifacts = null,
            composeInspection = inspection,
            composePrivacy = ComposeArchivePrivacy.FULL_FIDELITY,
        )

        val parameters = service.import(target).composeInspection!!.frame.details.getValue(1).parameters
        assertEquals("Visible title", parameters.single { it.name == "title" }.value)
        assertEquals("<redacted:sensitive>", parameters.single { it.name == "sessionToken" }.value)
        assertEquals("<redacted:sensitive>", parameters.single { it.name == "password" }.value)
        assertEquals("<redacted:sensitive>", parameters.single { it.name == "authorizationHeader" }.value)
        assertEquals("<redacted:sensitive>", parameters.single { it.name == "cacheFile" }.value)
    }

    @Test
    fun `mismatched optional compose detail does not block base snapshot import`() {
        val target = tempDir.resolve("mismatch.apinspect")
        val snapshot = SampleSnapshots.dashboard
        val payload = inspection("other.package", snapshot.capturedAtEpochMillis)
        val json = com.androidperformancestudio.compose.inspection.ComposeInspectionJson().encode(payload)
        CaptureArchiveCodec().write(
            target,
            CaptureArchiveMetadata(
                producerVersion = "test",
                packageName = snapshot.packageName,
                capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                protocolMajor = snapshot.protocolVersion.major,
                protocolMinor = snapshot.protocolVersion.minor,
            ),
            CaptureArchivePayload(
                snapshotJson = ProtocolCodec(1).encodeSnapshot(snapshot),
                composeInspectionJson = json,
            ),
        )

        val imported = service.import(target)
        assertEquals(snapshot.packageName, imported.snapshot.packageName)
        assertNull(imported.composeInspection)
        assertTrue(imported.composeInspectionWarning?.contains("does not match") == true)
    }

    private fun inspection(
        packageName: String,
        capturedAt: Long,
        privacy: ComposeArchivePrivacy = ComposeArchivePrivacy.FULL_FIDELITY,
    ) = ComposeInspectionDocument(
        packageName = packageName,
        capturedAtEpochMillis = capturedAt,
        privacy = privacy,
        frame = ComposeInspectionFrame(
            frameId = "$packageName:$capturedAt:1",
            generation = 1,
            mode = ComposeInspectionMode.FULL,
            capabilities = listOf(
                ComposeCapabilityState(ComposeCapability.FULL_TREE, CapabilityAvailability.AVAILABLE),
            ),
            roots = listOf(
                ComposableRoot(
                    viewId = 7,
                    nodes = listOf(
                        ComposableNode(1, 11, "Content", Bounds(0, 0, 10, 10)),
                    ),
                ),
            ),
            details = mapOf(
                1L to ComposableDetail(
                    nodeId = 1,
                    anchorHash = 11,
                    parameters = listOf(ComposeValue("title", "String", "sensitive")),
                ),
            ),
        ),
    )
}
