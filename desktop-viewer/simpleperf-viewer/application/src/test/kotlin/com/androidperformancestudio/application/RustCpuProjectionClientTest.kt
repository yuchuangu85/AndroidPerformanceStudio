@file:Suppress("MaxLineLength")

package com.androidperformancestudio.application

import com.android.tools.profiler.proto.SimpleperfReport
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.NativeBinaryQuery
import com.androidperformancestudio.platform.toolchain.NativeValueWire
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackFrame
import com.androidperformancestudio.profileanalysis.CallStackTable
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import com.androidperformancestudio.profileanalysis.CallTreeProjector
import com.androidperformancestudio.profileanalysis.FlameFunctionId
import com.androidperformancestudio.profileanalysis.FrameImplementation
import com.androidperformancestudio.profileanalysis.WeightedCallStack
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class RustCpuProjectionClientTest {
    @Test
    fun `invalid pages metrics and modes are not exposed as valid projections`() =
        runBlocking<Unit> {
            val root = Files.createTempDirectory("aps-cpu-page-validation-")
            try {
                val input = Files.writeString(root.resolve("source"), "fixture")
                val binary = Files.writeString(root.resolve("engine"), "fixture").also { it.toFile().setExecutable(true) }
                val digest = ArtifactFileEvidence.sha256(input).value
                val row =
                    mapOf(
                        "id" to "native-id",
                        "parentId" to null,
                        "depth" to 0L,
                        "name" to "root",
                        "resource" to "fixture.so",
                        "inclusiveWeight" to 1L,
                        "selfWeight" to 1L,
                        "sampleCount" to 1L,
                        "selfSampleCount" to 1L,
                    )
                val good = mapOf("total" to 1L, "offset" to 0L, "rows" to listOf(row), "direction" to "forward", "weightType" to "samples")
                for (payload in listOf(
                    good + ("offset" to 1L),
                    good + ("rows" to emptyList<Any>()),
                    good + ("weightType" to "events"),
                    good + ("rows" to listOf(row + ("selfWeight" to 2L))),
                    good + ("rows" to listOf(row - "parentId")),
                )) {
                    val client = RustCpuProjectionClient(NativeBinaryQuery(fakeRunner(payload)))
                    assertFailsWith<IOException> { client.page(binary, input, digest, RustCpuProjectionQuery()) }
                }
                assertFailsWith<IllegalArgumentException> { RustCpuProjectionQuery(weightType = CallStackWeightType.NANOSECONDS) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `actual native basic projections agree with Kotlin ancestry and weights`() =
        runBlocking<Unit> {
            val binary = System.getenv("APS_SIMPLEPERF_ENGINE_TEST")?.let(Path::of)
            assumeTrue(binary != null, "Set APS_SIMPLEPERF_ENGINE_TEST to the built query-capable Rust binary")
            val root = Files.createTempDirectory("aps-cpu-projection-parity-")
            try {
                val input = Files.write(root.resolve("profile.trace"), trace())
                val digest = ArtifactFileEvidence.sha256(input).value
                val client = RustCpuProjectionClient()
                val queries =
                    listOf(
                        RustCpuProjectionQuery(),
                        RustCpuProjectionQuery(eventTypes = setOf("cpu-cycles"), weightType = CallStackWeightType.EVENTS),
                        RustCpuProjectionQuery(
                            threadIds = setOf(1),
                            eventTypes = setOf("cpu-cycles"),
                            startNanosInclusive = 20,
                            endNanosExclusive = 50,
                        ),
                        RustCpuProjectionQuery(eventTypes = setOf("cpu-cycles"), direction = CallStackDirection.INVERTED),
                        RustCpuProjectionQuery(
                            threadIds = setOf(2),
                            eventTypes = setOf("cpu-cycles"),
                            direction = CallStackDirection.INVERTED,
                            weightType = CallStackWeightType.EVENTS,
                        ),
                        RustCpuProjectionQuery(
                            eventTypes = setOf("sched:sched_switch"),
                            direction = CallStackDirection.INVERTED,
                            weightType = CallStackWeightType.EVENTS,
                        ),
                        RustCpuProjectionQuery(startNanosInclusive = 61, endNanosExclusive = 70),
                    )
                for (query in queries) {
                    val page = client.page(requireNotNull(binary), input, digest, query)
                    assertEquals(expected(query), nativePaths(page.rows))
                    val byPages = mutableListOf<RustCpuCallNode>()
                    for (offset in 0 until page.total) {
                        val single = client.page(binary, input, digest, query, offset, 1)
                        assertEquals(page.total, single.total)
                        byPages.addAll(single.rows)
                    }
                    assertEquals(page.rows, byPages, "Paging must not renumber native identities")
                }
                assertFailsWith<IOException> {
                    client.page(requireNotNull(binary), input, digest, RustCpuProjectionQuery(weightType = CallStackWeightType.EVENTS))
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private data class Sample(
        val time: Long,
        val thread: Int,
        val event: Int,
        val count: Long,
        val leaf: Int?,
    )

    private val samples =
        listOf(
            Sample(10, 1, 0, 3, 1),
            Sample(20, 1, 0, 5, 2),
            Sample(30, 2, 0, 7, 1),
            Sample(40, 1, 1, 11, 2),
            Sample(50, 1, 0, 0, 1),
            Sample(60, 1, 0, 13, null),
        )
    private val names = listOf("root", "leafA", "leafB")
    private val eventNames = listOf("cpu-cycles", "sched:sched_switch")

    private fun expected(query: RustCpuProjectionQuery): Map<List<String>, List<Long>> {
        val frames =
            names
                .mapIndexed { index, name ->
                    index.toLong() to
                        CallStackFrame(
                            index.toLong(),
                            FlameFunctionId.fromSymbol(name, "fixture.so"),
                            name,
                            "fixture.so",
                            index.toLong(),
                            FrameImplementation.NATIVE,
                        )
                }.toMap()
        val selected =
            samples.filter { sample ->
                (query.threadIds.isEmpty() || sample.thread in query.threadIds) &&
                    (query.eventTypes.isEmpty() || eventNames[sample.event] in query.eventTypes) &&
                    (query.startNanosInclusive == null || sample.time >= query.startNanosInclusive) &&
                    (query.endNanosExclusive == null || sample.time < query.endNanosExclusive)
            }
        val stacks =
            selected.mapIndexed { index, sample ->
                WeightedCallStack(
                    index.toLong(),
                    sample.time,
                    if (query.weightType ==
                        CallStackWeightType.SAMPLES
                    ) {
                        1L
                    } else {
                        sample.count
                    },
                    sample.thread.toString(),
                    null,
                    null,
                    sample.leaf?.let { listOf(0L, it.toLong()) }.orEmpty(),
                )
            }
        val table = CallTreeProjector.project(CallStackTable(frames, stacks, query.weightType), query.direction)
        return (0 until table.size).associate { index ->
            val path = mutableListOf<String>()
            var at = index
            while (at >= 0) {
                path.add(requireNotNull(table.frameAt(at)).symbolName)
                at = requireNotNull(table.parentIndexAt(at))
            }
            path.reversed() to
                listOf(
                    requireNotNull(table.inclusiveWeightAt(index)),
                    requireNotNull(table.selfWeightAt(index)),
                    requireNotNull(table.sampleCountAt(index)),
                    table.selfSampleCountAt(index),
                )
        }
    }

    private fun nativePaths(rows: List<RustCpuCallNode>): Map<List<String>, List<Long>> {
        val byId = rows.associateBy { it.id }
        return rows.associate { row ->
            val path = mutableListOf<String>()
            var at: RustCpuCallNode? = row
            while (at != null) {
                path.add(at.name)
                at = at.parentId?.let { requireNotNull(byId[it]) }
            }
            path.reversed() to listOf(row.inclusiveWeight, row.selfWeight, row.sampleCount, row.selfSampleCount)
        }
    }

    private fun trace(): ByteArray {
        val records =
            samples.map { sample ->
                val value =
                    SimpleperfReport.Sample
                        .newBuilder()
                        .setTime(
                            sample.time,
                        ).setThreadId(sample.thread)
                        .setEventTypeId(sample.event)
                        .setEventCount(sample.count)
                sample.leaf?.let { leaf ->
                    for (symbol in listOf(leaf, 0)) {
                        value.addCallchain(
                            SimpleperfReport.Sample.CallChainEntry
                                .newBuilder()
                                .setFileId(7)
                                .setSymbolId(symbol)
                                .setVaddrInFile(symbol.toLong()),
                        )
                    }
                }
                SimpleperfReport.Record
                    .newBuilder()
                    .setSample(value)
                    .build()
            } +
                listOf(
                    SimpleperfReport.Record
                        .newBuilder()
                        .setFile(
                            SimpleperfReport.File
                                .newBuilder()
                                .setId(7)
                                .setPath("fixture.so")
                                .addAllSymbol(names),
                        ).build(),
                    SimpleperfReport.Record
                        .newBuilder()
                        .setMetaInfo(
                            SimpleperfReport.MetaInfo
                                .newBuilder()
                                .addEventType("cpu-cycles")
                                .addEventType("sched:sched_switch"),
                        ).build(),
                )
        return ByteArrayOutputStream()
            .apply {
                write("SIMPLEPERF".toByteArray())
                write(1)
                write(0)
                for (record in records) {
                    val bytes = record.toByteArray()
                    repeat(4) { write(bytes.size ushr (it * 8) and 255) }
                    write(bytes)
                }
                repeat(4) { write(0) }
            }.toByteArray()
    }

    private fun fakeRunner(payload: Map<String, Any?>) =
        object : HostProcessRunner {
            override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult {
                val bytes =
                    ByteArrayOutputStream()
                        .also {
                            NativeValueWire.writeFrame(DataOutputStream(it), mapOf("version" to 1L, "status" to "ok", "payload" to payload))
                        }.toByteArray()
                return HostProcessBinaryResult(1, 0, bytes, byteArrayOf(), Duration.ZERO, false, false)
            }

            override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult = error("not used")

            override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("not used")
        }
}
