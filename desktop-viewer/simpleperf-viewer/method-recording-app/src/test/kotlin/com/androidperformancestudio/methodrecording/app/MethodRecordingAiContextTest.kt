package com.androidperformancestudio.methodrecording.app

import com.androidperformancestudio.arttrace.ArtClockSource
import com.androidperformancestudio.arttrace.ArtMethod
import com.androidperformancestudio.arttrace.ArtThread
import com.androidperformancestudio.arttrace.ArtTraceAction
import com.androidperformancestudio.arttrace.ArtTraceAnalysis
import com.androidperformancestudio.arttrace.ArtTraceEvent
import com.androidperformancestudio.arttrace.ArtTraceHeader
import com.androidperformancestudio.arttrace.MethodTopMethod
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MethodRecordingAiContextTest {
    @Test
    fun `missing empty loading and recording states produce no context`() {
        assertEquals(null, methodRecordingAiContext(MethodRecordingState()))
        assertEquals(null, methodRecordingAiContext(state().copy(analysis = analysis().copy(events = emptyList()))))
        assertEquals(null, methodRecordingAiContext(state().copy(isLoading = true)))
        assertEquals(null, methodRecordingAiContext(state().copy(capturePhase = MethodTraceCapturePhase.Recording)))
    }

    @Test
    fun `summary keeps existing top eight with metrics units clock range and warning count`() {
        val rows = (0 until 11).map { method(it) }
        val context = assertNotNull(methodRecordingAiContext(state().copy(topMethods = rows)))
        val evidence = context.evidence.single()
        val payload = evidence.payloadJson
        assertEquals("method-recording", context.key)
        assertEquals("method-recording-summary", evidence.kind)
        assertFalse(context.currentSelection)
        assertTrue("\"topMethodCount\":11" in payload)
        assertTrue("\"includedTopMethodCount\":8" in payload)
        assertTrue("\"omittedTopMethodCount\":3" in payload)
        assertTrue("\"budgetOmittedTopMethodCount\":0" in payload)
        assertEquals(8, Regex("\"selfMicros\":").findAll(payload).count())
        assertTrue("\"symbol\":\"method-7\"" in payload)
        assertFalse("method-8" in payload)
        assertTrue("\"selfMicros\":10" in payload)
        assertTrue("\"totalMicros\":20" in payload)
        assertTrue("\"callCount\":30" in payload)
        assertTrue("\"threadCount\":2" in payload)
        assertTrue("\"timeUnit\":\"microseconds\"" in payload)
        assertTrue("\"timeUnit\":\"nanoseconds\"" in payload)
        assertTrue("\"startNanos\":100" in payload)
        assertTrue("\"endNanos\":1100" in payload)
        assertTrue("\"clockSource\":\"DUAL\"" in payload)
        assertTrue("\"warningCount\":2" in payload)
        assertTrue("not statistical samples or pure CPU execution time" in payload)
        assertTrue("No scheduling, blocking, jank, or ANR causality is established" in payload)
        assertTrue("totals are not additive" in payload)
        assertTrue("LOADED_TOP_METHODS_ONLY" in payload)
    }

    @Test
    fun `summary with no loaded top methods does not invent rows or selection`() {
        val context = assertNotNull(methodRecordingAiContext(state().copy(topMethods = emptyList())))
        assertFalse(context.currentSelection)
        assertTrue("\"topMethods\":[]" in context.evidence.single().payloadJson)
        assertTrue("\"omittedTopMethodCount\":0" in context.evidence.single().payloadJson)
    }

    @Test
    fun `labels are truncated and library is a basename on either platform`() {
        val rows =
            listOf(
                method().copy(symbolName = "s".repeat(200), resource = "/private/project/" + "l".repeat(100)),
                method(1).copy(resource = "C:\\private\\project\\lib-safe.so"),
            )
        val payload = methodRecordingAiContext(state().copy(topMethods = rows))!!.evidence.single().payloadJson
        assertTrue("\"symbol\":\"${"s".repeat(128)}\"" in payload)
        assertFalse("s".repeat(129) in payload)
        assertTrue("\"library\":\"${"l".repeat(64)}\"" in payload)
        assertFalse("l".repeat(65) in payload)
        assertTrue("\"symbolTruncated\":true" in payload)
        assertTrue("\"libraryTruncated\":true" in payload)
        assertTrue("\"library\":\"lib-safe.so\"" in payload)
        assertFalse("private" in payload)
        assertFalse("project" in payload)
    }

    @Test
    fun `raw trace data names paths diagnostics and warning text are not exposed`() {
        val context = methodRecordingAiContext(state())!!
        val serialized = context.toString()
        listOf("private", "raw-warning", "thread-name", "trace-label", "source-file", "raw-class", "raw-method", "raw-signature").forEach {
            assertFalse(it in serialized, it)
        }
        val payload = context.evidence.single().payloadJson
        listOf("events", "threads", "warnings", "traceLabel", "sourceFile", "cpuNanos", "methodId").forEach {
            assertFalse("\"$it\":" in payload, it)
        }
        assertTrue("\"eventCount\":1" in payload)
    }

    @Test
    fun `context reads only collection sizes and never scans raw records or warnings`() {
        val events =
            object : AbstractList<ArtTraceEvent>() {
                override val size: Int = 1_000_000

                override fun get(index: Int): ArtTraceEvent = error("Raw events must not be read")
            }
        val warnings =
            object : AbstractList<String>() {
                override val size: Int = 500

                override fun get(index: Int): String = error("Raw warnings must not be read")
            }
        val context =
            assertNotNull(methodRecordingAiContext(state().copy(analysis = analysis().copy(events = events, warnings = warnings))))
        assertTrue("\"eventCount\":1000000" in context.evidence.single().payloadJson)
        assertTrue("\"warningCount\":500" in context.evidence.single().payloadJson)
    }

    @Test
    fun `utf8 payload and escaped labels stay within eight KiB with omission metadata`() {
        val cjk = "\u6f22"
        val rows = (0 until 20).map { method(it).copy(symbolName = cjk.repeat(200), resource = cjk.repeat(100)) }
        val unicodePayload = methodRecordingAiContext(state().copy(topMethods = rows))!!.evidence.single().payloadJson
        assertTrue(unicodePayload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertTrue("\"includedTopMethodCount\":8" in unicodePayload)
        assertTrue(cjk.repeat(128) in unicodePayload)
        val escaped = rows.map { it.copy(symbolName = "\u0001".repeat(128), resource = "\u0001".repeat(64)) }
        val payload = methodRecordingAiContext(state().copy(topMethods = escaped))!!.evidence.single().payloadJson
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertFalse("\"includedTopMethodCount\":8" in payload)
        assertFalse("\"budgetOmittedTopMethodCount\":0" in payload)
    }

    @Test
    fun `supplementary unicode labels are never split at truncation boundary`() {
        val pair = "\uD83D\uDE00"
        val row = method().copy(symbolName = "s".repeat(127) + pair, resource = "l".repeat(63) + pair)
        val payload = methodRecordingAiContext(state().copy(topMethods = listOf(row)))!!.evidence.single().payloadJson
        assertTrue("\"symbol\":\"${"s".repeat(127)}\"" in payload)
        assertTrue("\"library\":\"${"l".repeat(63)}\"" in payload)
        assertEquals(payload, payload.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    @Test
    fun `opaque evidence id is stable across unrelated UI metadata and changes with measured facts`() {
        val initial = state()
        val evidence = methodRecordingAiContext(initial)!!.evidence.single()
        assertTrue(Regex("method-recording-summary:[0-9a-f]{64}").matches(evidence.id))
        assertEquals(evidence, methodRecordingAiContext(initial.copy())!!.evidence.single())
        val renamed =
            initial.copy(
                traceLabel = "different-trace-label",
                capturePhase = MethodTraceCapturePhase.Completed(Path.of("/other/location.trace")),
                selectedPid = 99,
                selectedSerial = "other-serial",
                topMethods = listOf(method().copy(resource = "/other/lib-safe.so")),
                analysis = analysis().copy(warnings = listOf("different-warning", "other-warning")),
            )
        assertEquals(evidence, methodRecordingAiContext(renamed)!!.evidence.single())
        val changed = initial.copy(topMethods = listOf(method().copy(selfMicros = 99)))
        assertNotEquals(evidence.id, methodRecordingAiContext(changed)!!.evidence.single().id)
        val otherRecording = initial.copy(analysis = analysis().copy(header = analysis().header.copy(startTimeNanos = 99)))
        assertNotEquals(evidence.id, methodRecordingAiContext(otherRecording)!!.evidence.single().id)
    }

    private fun method(index: Int = 0) = MethodTopMethod("method-$index", "/private/lib-safe.so", 10L + index, 20L + index, 30L + index, 2)

    private fun state() =
        MethodRecordingState(
            analysis = analysis(),
            topMethods = listOf(method()),
            traceLabel = "private-trace-label",
            error = "private-diagnostic",
            capturePhase = MethodTraceCapturePhase.Completed(Path.of("/private/recording.trace")),
        )

    private fun analysis() =
        ArtTraceAnalysis(
            header = ArtTraceHeader(version = 5, startTimeNanos = 42, clockSource = ArtClockSource.DUAL),
            methods = mapOf(2L to ArtMethod(2, "raw-class", "raw-method", "raw-signature", "/private/source-file")),
            threads = mapOf(1 to ArtThread(1, "private-thread-name")),
            events = listOf(ArtTraceEvent(1, 2, ArtTraceAction.ENTER, 100, cpuNanos = 987654321)),
            startTimeNanos = 100,
            endTimeNanos = 1_100,
            warnings = listOf("raw-warning", "another raw-warning"),
        )
}
