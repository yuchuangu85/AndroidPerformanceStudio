package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.perfetto.model.PerfettoArtifactFactory
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TraceOpeningTest {
    @TempDir lateinit var root: Path
    private val factory = PerfettoArtifactFactory(ByteArray(32) { 1 })

    @Test
    fun `opening a legacy session with a deleted temporary trace returns a recoverable failure`() {
        val trace = root.resolve("perfetto-capture/trace-1785509040036.pftrace")
        val result = prepareTraceArtifact(trace, null, ::importArtifact)

        val error = assertIs<StudioResult.Failure>(result).error
        assertEquals(ErrorCategory.IO, error.category)
        assertEquals("TRACE_FILE_UNAVAILABLE", error.code)
        assertTrue(error.message.contains(trace.toString()))
        assertTrue(error.message.contains("record a new capture"))
        assertFalse(Files.exists(trace))
    }

    @Test
    fun `directories are rejected before artifact registration`() {
        val result = prepareTraceArtifact(root, null) { error("Directory must not be imported") }

        assertEquals("TRACE_FILE_UNAVAILABLE", assertIs<StudioResult.Failure>(result).error.code)
    }

    @Test
    fun `stored metadata does not make a deleted trace available`() {
        val trace = Files.writeString(root.resolve("capture.pftrace"), "trace")
        val artifact = importArtifact(trace)
        Files.delete(trace)

        val result = prepareTraceArtifact(trace, artifact) { error("Stored evidence must not be reimported") }

        assertEquals("TRACE_FILE_UNAVAILABLE", assertIs<StudioResult.Failure>(result).error.code)
    }

    @Test
    fun `valid imports retain hash and import provenance`() {
        val trace = Files.writeString(root.resolve("import.pftrace"), "trace")

        val artifact = assertIs<StudioResult.Success<CaptureArtifact>>(prepareTraceArtifact(trace, null, ::importArtifact)).value

        assertEquals(ArtifactFileEvidence.sha256(trace), artifact.sha256)
        assertEquals(ArtifactAcquisitionKind.IMPORT, artifact.provenance.acquisition.kind)
        assertEquals(trace.toAbsolutePath().normalize().toString(), artifact.location.value)
    }

    @Test
    fun `valid stored artifact retains its identity and capture provenance`() {
        val trace = Files.writeString(root.resolve("capture.pftrace"), "trace")
        val artifact = factory.captured("capture-id", trace, "serial", "Pixel", Instant.EPOCH)

        val result = prepareTraceArtifact(trace, artifact) { error("Stored artifact must not be reimported") }

        assertSame(artifact, assertIs<StudioResult.Success<CaptureArtifact>>(result).value)
    }

    @Test
    fun `file disappearing during import is reported without escaping to the UI coroutine`() {
        val trace = Files.writeString(root.resolve("disappearing.pftrace"), "trace")

        val result =
            prepareTraceArtifact(trace, null) {
                Files.delete(it)
                importArtifact(it)
            }

        assertEquals("TRACE_OPEN_FAILED", assertIs<StudioResult.Failure>(result).error.code)
    }

    @Test
    fun `read failures preserve their cause and trace path`() {
        val trace = Files.writeString(root.resolve("unreadable.pftrace"), "trace")
        val cause = IOException("read failed")

        val result = prepareTraceArtifact(trace, null) { throw cause }

        val error = assertIs<StudioResult.Failure>(result).error
        assertEquals("TRACE_OPEN_FAILED", error.code)
        assertSame(cause, error.cause)
        assertTrue(error.message.contains(trace.toString()))
        assertTrue(error.message.contains("read failed"))
    }

    @Test
    fun `access denied errors are reported without escaping to the UI coroutine`() {
        val trace = Files.writeString(root.resolve("restricted.pftrace"), "trace")
        val cause = SecurityException("access denied")

        val result = prepareTraceArtifact(trace, null) { throw cause }

        assertSame(cause, assertIs<StudioResult.Failure>(result).error.cause)
    }

    @Test
    fun `cancelling a superseded import is not converted into a file error`() {
        val trace = Files.writeString(root.resolve("cancelled.pftrace"), "trace")

        assertFailsWith<CancellationException> {
            prepareTraceArtifact(trace, null) { throw CancellationException("superseded") }
        }
    }

    private fun importArtifact(trace: Path): CaptureArtifact = factory.imported("import-id", trace, Instant.EPOCH)
}
