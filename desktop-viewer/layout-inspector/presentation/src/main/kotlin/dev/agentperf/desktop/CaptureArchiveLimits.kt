package dev.agentperf.desktop

import java.util.prefs.Preferences

internal data class CaptureArchiveLimits(
    val snapshotSizeMultiplier: Int = DEFAULT_SNAPSHOT_SIZE_MULTIPLIER,
) {
    init {
        require(
            snapshotSizeMultiplier in
                MIN_SNAPSHOT_SIZE_MULTIPLIER..MAX_SNAPSHOT_SIZE_MULTIPLIER,
        ) {
            "Snapshot size multiplier must be between " +
                "$MIN_SNAPSHOT_SIZE_MULTIPLIER and $MAX_SNAPSHOT_SIZE_MULTIPLIER"
        }
    }

    val maxSnapshotSizeMiB: Int
        get() = BASE_MAX_SNAPSHOT_SIZE_MIB * snapshotSizeMultiplier

    val maxSnapshotBytes: Int
        get() = maxSnapshotSizeMiB * BYTES_PER_MIB

    val maxTotalUncompressedBytes: Long
        get() = BASE_MAX_TOTAL_UNCOMPRESSED_BYTES + snapshotLimitIncreaseBytes

    val maxArchiveBytes: Long
        get() = BASE_MAX_ARCHIVE_BYTES + snapshotLimitIncreaseBytes

    private val snapshotLimitIncreaseBytes: Long
        get() = (maxSnapshotSizeMiB - BASE_MAX_SNAPSHOT_SIZE_MIB).toLong() * BYTES_PER_MIB

    companion object {
        const val BASE_MAX_SNAPSHOT_SIZE_MIB = 32
        const val MIN_SNAPSHOT_SIZE_MULTIPLIER = 1
        const val MAX_SNAPSHOT_SIZE_MULTIPLIER = 10
        const val DEFAULT_SNAPSHOT_SIZE_MULTIPLIER = 1

        private const val BYTES_PER_MIB = 1024 * 1024
        private const val BASE_MAX_TOTAL_UNCOMPRESSED_BYTES = 80L * BYTES_PER_MIB
        private const val BASE_MAX_ARCHIVE_BYTES = 96L * BYTES_PER_MIB

        fun fromStoredMultiplier(value: Int?): CaptureArchiveLimits =
            CaptureArchiveLimits(
                snapshotSizeMultiplier = value
                    ?.coerceIn(
                        MIN_SNAPSHOT_SIZE_MULTIPLIER,
                        MAX_SNAPSHOT_SIZE_MULTIPLIER,
                    )
                    ?: DEFAULT_SNAPSHOT_SIZE_MULTIPLIER,
            )
    }
}

internal class CaptureArchiveLimitsStore(
    private val readValue: () -> Int?,
    private val writeValue: (Int) -> Unit,
    private val flush: () -> Unit = {},
) {
    fun load(): CaptureArchiveLimits = CaptureArchiveLimits.fromStoredMultiplier(readValue())

    fun save(limits: CaptureArchiveLimits): Boolean =
        runCatching {
            writeValue(limits.snapshotSizeMultiplier)
            flush()
        }.isSuccess

    companion object {
        private const val KEY = "archive.snapshotSizeMultiplier"

        fun desktop(): CaptureArchiveLimitsStore {
            val preferences = runCatching {
                Preferences.userNodeForPackage(CaptureArchiveLimitsStore::class.java)
            }.getOrNull()
            return CaptureArchiveLimitsStore(
                readValue = {
                    runCatching {
                        preferences?.getInt(
                            KEY,
                            CaptureArchiveLimits.DEFAULT_SNAPSHOT_SIZE_MULTIPLIER,
                        )
                    }.getOrNull()
                },
                writeValue = { value ->
                    checkNotNull(preferences) { "Capture archive preferences are unavailable" }
                    preferences.putInt(KEY, value)
                },
                flush = { checkNotNull(preferences).flush() },
            )
        }
    }
}
