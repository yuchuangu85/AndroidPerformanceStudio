package dev.agentperf.desktop

internal data class RefreshTimingEvent(
    val refreshKind: String,
    val stage: String,
    val elapsedMillis: Long,
)

internal fun interface RefreshTimingSink {
    fun record(event: RefreshTimingEvent)
}

internal object ConsoleRefreshTimingSink : RefreshTimingSink {
    override fun record(event: RefreshTimingEvent) {
        println(
            "AndroidPerfermanceStudio refresh kind=${event.refreshKind} " +
                "stage=${event.stage} elapsedMs=${event.elapsedMillis}",
        )
    }
}

internal class RefreshTimer(
    private val refreshKind: String,
    private val sink: RefreshTimingSink,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun <T> measure(stage: String, block: () -> T): T {
        val startedAt = nanoTime()
        return try {
            block()
        } finally {
            val elapsedNanos = nanoTime() - startedAt
            sink.record(
                RefreshTimingEvent(
                    refreshKind = refreshKind,
                    stage = stage,
                    elapsedMillis = elapsedNanos.coerceAtLeast(0L) / NANOS_PER_MILLISECOND,
                ),
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
