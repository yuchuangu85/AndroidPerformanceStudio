package com.androidperformancestudio.platform.perfetto

import com.androidperformancestudio.contracts.ArtifactAcquisition
import com.androidperformancestudio.contracts.ArtifactAcquisitionKind
import com.androidperformancestudio.contracts.ArtifactId
import com.androidperformancestudio.contracts.ArtifactKind
import com.androidperformancestudio.contracts.ArtifactLocation
import com.androidperformancestudio.contracts.ArtifactProvenance
import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class TraceAnalysisContextTest {
    @Test
    fun `opens independent artifact contexts and maps the pinned query schema`() =
        runBlocking {
            val trace = Files.createTempFile("synthetic", ".pftrace")
            val runner = FixtureRunner("process_name,total_dur_ms\ncom.example.app,42\n")
            val contexts =
                TraceAnalysisContexts(
                    TraceProcessorTool(Path.of("/tools/trace_processor_shell"), "v57.2", "a".repeat(64)),
                    runner,
                    isReady = { true },
                )
            val processName = TraceColumn.string("process_name")
            val totalDurationMs = TraceColumn.long("total_dur_ms")
            val query =
                TraceQuery(
                    sql = "SELECT process_name, total_dur_ms FROM synthetic_trace",
                    schema = TraceQuerySchema.v57_2(processName, totalDurationMs),
                ) { row -> row[processName] to row[totalDurationMs] }

            val first = assertIs<StudioResult.Success<TraceAnalysisContext>>(contexts.open(artifact("first", trace), trace)).value
            val second = assertIs<StudioResult.Success<TraceAnalysisContext>>(contexts.open(artifact("second", trace), trace)).value
            val rows = assertIs<StudioResult.Success<List<Pair<String?, Long?>>>>(first.query(query)).value

            assertNotEquals(first.port, second.port)
            assertEquals(listOf("com.example.app" to 42L), rows)

            first.close()
            second.close()

            assertTrue(runner.processes.all { it.terminated })
        }

    @Test
    fun `rejects modified artifact bytes before starting trace processor`() =
        runBlocking {
            val trace = Files.createTempFile("modified", ".pftrace")
            Files.writeString(trace, "before")
            val artifact = artifact("modified", trace)
            Files.writeString(trace, "after")
            val runner = FixtureRunner("")
            val contexts =
                TraceAnalysisContexts(
                    TraceProcessorTool(Path.of("/tools/trace_processor_shell"), "v57.2", "a".repeat(64)),
                    runner,
                    isReady = { true },
                )

            val result = contexts.open(artifact, trace)

            assertEquals("TRACE_ARTIFACT_HASH_MISMATCH", assertIs<StudioResult.Failure>(result).error.code)
            assertTrue(runner.processes.isEmpty())
        }

    @Test
    fun `does not publish an artifact context until its server is ready`() =
        runBlocking {
            val trace = Files.createTempFile("concurrent", ".pftrace")
            val runner = FixtureRunner("")
            val contexts =
                TraceAnalysisContexts(
                    TraceProcessorTool(Path.of("/tools/trace_processor_shell"), "v57.2", "a".repeat(64)),
                    runner,
                    isReady = {
                        delay(75)
                        true
                    },
                )
            val artifact = artifact("concurrent", trace)

            coroutineScope {
                val first = async { contexts.open(artifact, trace) }
                delay(10)
                val second = async { contexts.open(artifact, trace) }
                val firstContext = assertIs<StudioResult.Success<TraceAnalysisContext>>(first.await()).value
                val secondContext = assertIs<StudioResult.Success<TraceAnalysisContext>>(second.await()).value

                assertSame(firstContext, secondContext)
                assertEquals(1, runner.processes.size)
                firstContext.close()
            }
        }

    @Test
    fun `public context executes a typed fixture against the installed pinned schema`() =
        runBlocking {
            val binary = findInstalledTraceProcessor() ?: return@runBlocking
            val trace = Files.createTempFile("pinned-schema-fixture", ".pftrace")
            val tool =
                TraceProcessorTool(
                    binary,
                    TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
                    ArtifactFileEvidence.sha256(binary).value,
                )
            val fixtureId = TraceColumn.long("fixture_id")
            val fixtureName = TraceColumn.string("fixture_name")
            val query =
                TraceQuery(
                    sql = "SELECT 7 AS fixture_id, 'synthetic' AS fixture_name",
                    schema = TraceQuerySchema.v57_2(fixtureId, fixtureName),
                ) { row -> requireNotNull(row[fixtureId]) to requireNotNull(row[fixtureName]) }

            val context =
                assertIs<StudioResult.Success<TraceAnalysisContext>>(
                    TraceAnalysisContexts(tool).open(artifact("pinned-schema", trace), trace),
                ).value
            try {
                assertEquals(
                    listOf(7L to "synthetic"),
                    assertIs<StudioResult.Success<List<Pair<Long, String>>>>(context.query(query)).value,
                )
            } finally {
                context.close()
            }
        }

    private fun findInstalledTraceProcessor(): Path? {
        val binary = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            "trace_processor_shell.exe"
        } else {
            "trace_processor_shell"
        }
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            val candidate = current?.resolve("build/perfetto-tools/$binary")
            if (candidate != null && Files.isExecutable(candidate)) return candidate
            current = current?.parent
        }
        return null
    }

    private fun artifact(
        id: String,
        trace: Path,
    ): CaptureArtifact =
        CaptureArtifact(
            id = ArtifactId(id),
            kind = ArtifactKind("perfetto.trace"),
            location = ArtifactLocation("$id.pftrace"),
            sha256 = ArtifactFileEvidence.sha256(trace),
            provenance =
                ArtifactProvenance(
                    acquisition =
                        ArtifactAcquisition(
                            ArtifactAcquisitionKind.IMPORT,
                            "test",
                            performedAtEpochMillis = 0,
                        ),
                ),
        )

    private class FixtureRunner(
        private val queryOutput: String,
    ) : HostProcessRunner {
        val processes = mutableListOf<FixtureProcess>()

        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult =
            HostProcessTextResult(-1, 0, queryOutput, "", Duration.ZERO, false, false)

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult =
            error("not used")

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess =
            FixtureProcess().also(processes::add)
    }

    private class FixtureProcess : RunningHostProcess {
        override val pid: Long = -1
        override val isAlive: Boolean = true
        var terminated = false

        override fun terminate() {
            terminated = true
        }
    }
}
