package com.androidperformancestudio.perfetto.analysis

/** A named group of existing diagnostics that the Trace Analyzer runs together. */
data class DiagnosticPreset(
    val id: String,
    val title: String,
    val queryIds: List<String>,
) {
    init {
        require(queryIds.isNotEmpty() && queryIds.distinct().size == queryIds.size) { "preset queries must be unique" }
    }

    fun queries(): List<DiagnosticQuery> = queryIds.mapNotNull { id -> PerfettoDiagnostics.all.firstOrNull { it.id == id } }
}

object PerfettoDiagnosticPresets {
    /** The commercial diagnostic list documented for page 04 §9.3. */
    val core =
        DiagnosticPreset(
            id = "core",
            title = "Core diagnostics",
            queryIds =
                listOf(
                    "cpu_hotspots",
                    "cpu_freq_dist",
                    "binder_latency",
                    "frame_jank",
                    "mem_counters",
                    "input_latency",
                    "thread_states",
                    "wakeup_latency",
                ),
        )

    val all: List<DiagnosticPreset> =
        listOf(
            core,
            DiagnosticPreset(
                id = "jank",
                title = "Jank and rendering",
                queryIds =
                    listOf(
                        "frame_jank",
                        "frame_surface_correlation",
                        "binder_frame_alignment",
                        "main_thread_blocked",
                        "input_latency",
                    ),
            ),
            DiagnosticPreset(
                id = "scheduling",
                title = "CPU scheduling",
                queryIds =
                    listOf(
                        "cpu_hotspots",
                        "run_queue",
                        "cpu_contention",
                        "thread_states",
                        "wakeup_latency",
                        "cpu_freq_dist",
                    ),
            ),
            DiagnosticPreset(
                id = "binder",
                title = "Binder IPC",
                queryIds =
                    listOf(
                        "binder_latency",
                        "binder_ipc_long_tail",
                        "binder_system_server",
                        "binder_server_saturation",
                        "binder_wait_chain",
                    ),
            ),
            DiagnosticPreset(
                id = "io",
                title = "I/O and SQLite",
                queryIds = listOf("file_io_syscalls", "fsync_calls", "block_io_pressure", "sqlite_activity", "sqlite_lock_wait"),
            ),
            DiagnosticPreset(
                id = "memory",
                title = "Memory and ART",
                queryIds = listOf("mem_counters", "page_faults", "art_gc_events", "art_allocation_churn"),
            ),
            DiagnosticPreset(
                id = "power",
                title = "Thermal and power",
                queryIds = listOf("thermal_throttling", "dvfs_residency", "gpu_frequency_residency", "power_rails"),
            ),
            DiagnosticPreset(
                id = "all",
                title = "All diagnostics",
                queryIds = PerfettoDiagnostics.all.map(DiagnosticQuery::id),
            ),
        )

    fun byId(id: String?): DiagnosticPreset = all.firstOrNull { it.id == id } ?: core
}
