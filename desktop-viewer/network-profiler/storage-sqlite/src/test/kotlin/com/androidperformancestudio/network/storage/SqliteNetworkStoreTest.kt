package com.androidperformancestudio.network.storage

import com.androidperformancestudio.network.model.*
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteNetworkStoreTest {
    @Test fun `persists capture idempotently`() {
        val session = NetworkSession(startedAt = Instant.now(), endedAt = Instant.now(), deviceSerial = null, packageName = null, coverage = NetworkCoverage(setOf("HAR"), InstrumentationMode.HAR_IMPORT, setOf("HAR"), emptySet(), 0, NetworkConfidence.EXACT), clockMapping = null, status = NetworkSessionStatus.COMPLETE)
        val call = HttpCall("c", "GET", "https://example.test", 0, 1, listOf(HttpExchange(0, null, null, 200, null, null, emptyList(), CacheDisposition.UNKNOWN, null)), CallOutcome.SUCCESS, NetworkEvidenceSource.HAR_IMPORT)
        val result = NetworkCaptureResult(session, listOf(call), 1)
        SqliteNetworkStore.open(createTempDirectory().resolve("db.sqlite")).use {
            it.save(result)
            it.save(result)
            assertEquals(1, it.listRecent().size)
        }
    }
}
