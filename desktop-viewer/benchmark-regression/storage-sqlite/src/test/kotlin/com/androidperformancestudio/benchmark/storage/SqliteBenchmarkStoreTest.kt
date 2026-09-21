@file:Suppress("LongMethod")

package com.androidperformancestudio.benchmark.storage

import com.androidperformancestudio.benchmark.model.*
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteBenchmarkStoreTest {
    @Test
    fun `persists run summary idempotently`() {
        val database = createTempDirectory().resolve("db.sqlite")
        val trace = Path.of("trace.perfetto-trace")
        val profile = Path.of("baseline-prof.txt")
        val run =
            BenchmarkRun(
                sourceFile = Path.of("result.json"),
                benchmarkDataVersion = 1,
                benchmarkLibraryVersion = null,
                device = BenchmarkDevice("Pixel", null, 35, null, "arm64", null, null, true),
                build = BenchmarkBuild(null, null, null, null, null, null),
                cases =
                    listOf(
                        BenchmarkCase(
                            "Bench",
                            "test",
                            null,
                            "speed-profile",
                            null,
                            1,
                            listOf(
                                BenchmarkMetric(
                                    "timeMs",
                                    "ms",
                                    MetricDirection.LOWER_IS_BETTER,
                                    listOf(1.0),
                                    1.0,
                                    1.0,
                                    1.0,
                                    EvidenceConfidence.EXACT,
                                ),
                            ),
                            listOf(trace),
                            scenario = BenchmarkScenario.SCROLL,
                            baselineProfile =
                                BaselineProfileEvidence(
                                    status = BaselineProfileStatus.VERIFIED,
                                    source = "baseline-profile-plugin",
                                    artifact = profile,
                                    profileHash = "abc",
                                    verifiedAt = Instant.EPOCH,
                                ),
                        ),
                    ),
            )
        SqliteBenchmarkStore.open(database).use { store ->
            store.save(run)
            store.save(run)
            val saved = store.listRecent().single()
            assertEquals(1, saved.traceArtifactCount)
            assertEquals(1, saved.baselineProfileCaseCount)
        }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT baseline_profile_source, baseline_profile_artifact, baseline_profile_hash, baseline_profile_verified_at FROM benchmark_case").use { rows ->
                    assertEquals(true, rows.next())
                    assertEquals("baseline-profile-plugin", rows.getString(1))
                    assertEquals(profile.toString(), rows.getString(2))
                    assertEquals("abc", rows.getString(3))
                    assertEquals(Instant.EPOCH.toString(), rows.getString(4))
                }
            }
        }
    }
}
