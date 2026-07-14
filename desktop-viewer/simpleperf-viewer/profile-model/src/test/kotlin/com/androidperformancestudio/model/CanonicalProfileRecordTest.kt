package com.androidperformancestudio.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class CanonicalProfileRecordTest {
    @Test
    fun `sample retains source clock cpu and category`() {
        val sample = canonicalSampleFixture()

        assertEquals(ProfileSourceId("simpleperf"), sample.sourceId)
        assertEquals(ProfileClockDomain("monotonic"), sample.time.clockDomain)
        assertEquals(4, sample.cpuCore)
        assertEquals(ProfileCategory("Native", "System"), sample.category)
    }

    @Test
    fun `interval facts reject reversed ranges`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileSliceFact(
                sourceId = ProfileSourceId("perfetto"),
                thread = threadFixture(),
                start = time(20),
                end = time(10),
                name = "Binder",
                category = null,
            )
        }
    }

    @Test
    fun `fact contracts retain source process thread and timeline data`() {
        val sourceId = ProfileSourceId("simpleperf")
        val process = processFixture()
        val thread = threadFixture()
        val source =
            ProfileSourceFact(
                id = sourceId,
                kind = ProfileSourceKind.SIMPLEPERF,
                clockDomain = ProfileClockDomain("monotonic"),
                validFromNanos = 10,
                validUntilNanosExclusive = 30,
            )
        val processFact = ProfileProcessFact(process, "studio", time(10), time(30))
        val threadFact = ProfileThreadFact(thread, "RenderThread", time(12), time(28))
        val marker = ProfileMarkerFact(sourceId, thread, time(13), time(14), "trace_event", "draw", "{}")
        val counter = ProfileCounterFact(sourceId, time(15), "rss", "bytes", 1024.0)
        val slice = ProfileSliceFact(sourceId, thread, time(16), time(17), "Binder", ProfileCategory("IPC"))
        val screenshot = ProfileScreenshotFact(sourceId, time(18), "artifacts/frame.png")

        assertEquals(10, source.validFromNanos)
        assertEquals("studio", processFact.name)
        assertEquals("RenderThread", threadFact.name)
        assertEquals("trace_event", marker.schema)
        assertEquals(1024.0, counter.value)
        assertEquals(ProfileCategory("IPC"), slice.category)
        assertEquals("artifacts/frame.png", screenshot.artifactPath)
    }

    @Test
    fun `canonical stream wraps every focused fact type`() {
        val sourceId = ProfileSourceId("simpleperf")
        val process = processFixture()
        val thread = threadFixture()

        assertIs<CanonicalProfileRecord.Source>(
            CanonicalProfileRecord.Source(
                ProfileSourceFact(
                    sourceId,
                    ProfileSourceKind.SIMPLEPERF,
                    ProfileClockDomain("monotonic"),
                    null,
                    null,
                ),
            ),
        )
        assertIs<CanonicalProfileRecord.Process>(
            CanonicalProfileRecord.Process(ProfileProcessFact(process, null, null, null)),
        )
        assertIs<CanonicalProfileRecord.Thread>(
            CanonicalProfileRecord.Thread(ProfileThreadFact(thread, "RenderThread", null, null)),
        )
        assertIs<CanonicalProfileRecord.Sample>(CanonicalProfileRecord.Sample(canonicalSampleFixture()))
        assertIs<CanonicalProfileRecord.Marker>(
            CanonicalProfileRecord.Marker(ProfileMarkerFact(sourceId, null, time(1), null, "log", "start", "{}")),
        )
        assertIs<CanonicalProfileRecord.Counter>(
            CanonicalProfileRecord.Counter(ProfileCounterFact(sourceId, time(2), "cpu", "percent", 50.0)),
        )
        assertIs<CanonicalProfileRecord.Slice>(
            CanonicalProfileRecord.Slice(ProfileSliceFact(sourceId, thread, time(3), time(4), "run", null)),
        )
        assertIs<CanonicalProfileRecord.Screenshot>(
            CanonicalProfileRecord.Screenshot(ProfileScreenshotFact(sourceId, time(5), "frame.png")),
        )
    }

    @Test
    fun `legacy adapter preserves context switch and quality evidence records`() {
        val contextSwitch = NormalizedProfileRecord.ContextSwitch(22, 1_000, true)
        val qualityEvidence = NormalizedProfileRecord.Lost(100, 3)

        assertSame(contextSwitch, assertIs<CanonicalProfileRecord.Legacy>(contextSwitch.asCanonical()).value)
        assertSame(qualityEvidence, assertIs<CanonicalProfileRecord.Legacy>(qualityEvidence.asCanonical()).value)
    }

    private fun canonicalSampleFixture(): ProfileSampleFact =
        ProfileSampleFact(
            sourceId = ProfileSourceId("simpleperf"),
            time = time(10),
            thread = threadFixture(),
            eventType = "cpu-cycles",
            eventCount = 7,
            cpuCore = 4,
            onCpu = true,
            category = ProfileCategory("Native", "System"),
            frames =
                listOf(
                    ProfileFrame(
                        virtualAddress = 0x1000,
                        fileId = 1,
                        symbolId = 2,
                        filePath = "/system/lib64/libc.so",
                        symbolName = "poll",
                        executionType = ProfileExecutionType.NATIVE,
                    ),
                ),
            unwindError = null,
        )

    private fun processFixture(): ProfileProcessKey {
        val sourceId = ProfileSourceId("simpleperf")
        return ProfileProcessKey(sourceId, 11)
    }

    private fun threadFixture(): ProfileThreadKey {
        val process = processFixture()
        return ProfileThreadKey(process.sourceId, process, 22)
    }

    private fun time(timestampNanos: Long) = ProfileTimePoint(ProfileClockDomain("monotonic"), timestampNanos)
}
