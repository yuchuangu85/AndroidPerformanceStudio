package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CaptureArchiveLimitsTest {
    @Test
    fun `default and maximum multipliers produce bounded archive limits`() {
        val defaults = CaptureArchiveLimits()
        val maximum = CaptureArchiveLimits(
            snapshotSizeMultiplier = CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER,
        )

        assertEquals(32, defaults.maxSnapshotSizeMiB)
        assertEquals(320, maximum.maxSnapshotSizeMiB)
        assertEquals(368L * 1024 * 1024, maximum.maxTotalUncompressedBytes)
        assertEquals(384L * 1024 * 1024, maximum.maxArchiveBytes)
    }

    @Test
    fun `store restores valid values and clamps stale preferences`() {
        var storedValue: Int? = null
        val store = CaptureArchiveLimitsStore(
            readValue = { storedValue },
            writeValue = { storedValue = it },
        )

        assertEquals(CaptureArchiveLimits(), store.load())

        val expected = CaptureArchiveLimits(snapshotSizeMultiplier = 6)
        store.save(expected)
        assertEquals(6, storedValue)
        assertEquals(expected, store.load())

        storedValue = 99
        assertEquals(
            CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER,
            store.load().snapshotSizeMultiplier,
        )
        storedValue = -1
        assertEquals(
            CaptureArchiveLimits.MIN_SNAPSHOT_SIZE_MULTIPLIER,
            store.load().snapshotSizeMultiplier,
        )
    }
}
