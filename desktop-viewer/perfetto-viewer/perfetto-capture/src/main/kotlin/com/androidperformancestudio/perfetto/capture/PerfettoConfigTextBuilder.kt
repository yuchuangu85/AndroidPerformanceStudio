package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoProbe
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.perfetto.model.defaultProbes
import com.androidperformancestudio.platform.perfetto.PerfettoCaptureDocument
import com.androidperformancestudio.platform.perfetto.PerfettoConfigComposer
import com.androidperformancestudio.platform.perfetto.PerfettoDataSource

/** Builds a text-proto TraceConfig accepted by the Android `perfetto --txt` CLI. */
object PerfettoConfigTextBuilder {
    fun build(config: PerfettoCaptureConfig): String {
        config.customConfigText
            ?.takeIf { config.template == PerfettoTraceTemplate.CUSTOM && it.isNotBlank() }
            ?.let { return it }

        val probes = config.enabledProbes ?: config.template.defaultProbes()
        val extraFtraceEvents = config.additionalCategories.filter { '/' in it }
        val extraAtraceCategories = config.additionalCategories.filterNot { '/' in it }
        val ftraceEvents =
            (probes.flatMap(::ftraceEvents) + extraFtraceEvents).distinct()
        val atraceCategories =
            (
                (if (PerfettoProbe.ATRACE in probes) atraceCategories(config.template) else emptyList()) +
                    extraAtraceCategories
            ).distinct()
        val atraceApps =
            buildSet {
                config.targetPackage?.takeIf(String::isNotBlank)?.let(::add)
                if (PerfettoProbe.LOW_MEMORY_KILLER in probes) add("lmkd")
            }

        val dataSources =
            buildList {
                if (ftraceEvents.isNotEmpty() || atraceCategories.isNotEmpty() || atraceApps.isNotEmpty()) {
                    add(ftrace(ftraceEvents, atraceCategories, atraceApps, PerfettoProbe.ADVANCED_FTRACE in probes))
                }
                if (probes.any { it in SYS_STATS_PROBES }) add(systemStats(probes))
                if (probes.any { it in PROCESS_STATS_PROBES }) add(processStats(PerfettoProbe.PROCESS_STATS in probes))
                addDirectSources(probes, config.targetPackage)
                add(PerfettoDataSource("android.packages_list"))
            }
        return PerfettoConfigComposer.compose(
            PerfettoCaptureDocument(
                durationMillis = config.durationSeconds * 1_000L,
                bufferSizeKb = config.bufferSizeKb,
                dataSources = dataSources,
                flushPeriodMillis = 5_000,
            ),
        )
    }

    private fun MutableList<PerfettoDataSource>.addDirectSources(
        probes: Set<PerfettoProbe>,
        targetPackage: String?,
    ) {
        fun addIf(
            probe: PerfettoProbe,
            source: String,
            config: String = "",
        ) {
            if (probe in probes) add(PerfettoDataSource(source, config))
        }

        targetPackage?.takeIf(String::isNotBlank)?.let { target ->
            if (PerfettoProbe.NATIVE_HEAP in probes) add(heapProfile(target))
            if (PerfettoProbe.JAVA_HEAP_DUMP in probes) add(javaHeapDump(target))
        }
        addIf(
            PerfettoProbe.POWER_RAILS,
            "android.power",
            """
            android_power_config {
              battery_poll_ms: 1000
              collect_power_rails: true
              battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT
              battery_counters: BATTERY_COUNTER_CHARGE
              battery_counters: BATTERY_COUNTER_CURRENT
            }
            """.trimIndent(),
        )
        addIf(PerfettoProbe.GPU_MEMORY, "android.gpu.memory")
        addIf(PerfettoProbe.GPU_RENDER_STAGES, "gpu.renderstages")
        addIf(PerfettoProbe.ANDROID_LOG, "android.log")
        addIf(PerfettoProbe.FRAME_TIMELINE, "android.surfaceflinger.frametimeline")
        addIf(PerfettoProbe.GAME_INTERVENTIONS, "android.game_interventions")
        addIf(
            PerfettoProbe.NETWORK_PACKETS,
            "android.network_packets",
            "network_packet_trace_config { poll_ms: 1000 }",
        )
        if (PerfettoProbe.CALLSTACK_SAMPLING in probes) add(perfSampling(targetPackage))
        addIf(PerfettoProbe.TRACK_EVENTS, "track_event")
    }

    private fun ftrace(
        events: List<String>,
        atraceCategories: List<String>,
        atraceApps: Set<String>,
        advanced: Boolean,
    ): PerfettoDataSource =
        PerfettoDataSource(
            "linux.ftrace",
            buildString {
                appendLine("ftrace_config {")
                events.forEach { appendLine("  ftrace_events: \"${escapeProtoString(it)}\"") }
                atraceCategories.forEach { appendLine("  atrace_categories: \"${escapeProtoString(it)}\"") }
                atraceApps.forEach { appendLine("  atrace_apps: \"${escapeProtoString(it)}\"") }
                if (advanced) {
                    appendLine("  symbolize_ksyms: true")
                    appendLine("  disable_generic_events: false")
                }
                appendLine("}")
            },
        )

    private fun processStats(pollMemory: Boolean): PerfettoDataSource =
        PerfettoDataSource(
            "linux.process_stats",
            buildString {
                appendLine("process_stats_config {")
                appendLine("  scan_all_processes_on_start: true")
                appendLine("  record_thread_names: true")
                if (pollMemory) appendLine("  proc_stats_poll_ms: 1000")
                appendLine("}")
            },
        )

    private fun systemStats(probes: Set<PerfettoProbe>): PerfettoDataSource =
        PerfettoDataSource(
            "linux.sys_stats",
            buildString {
                appendLine("sys_stats_config {")
                if (PerfettoProbe.CPU_USAGE in probes) appendLine("  stat_period_ms: 1000")
                if (PerfettoProbe.CPU_FREQUENCY in probes) appendLine("  cpufreq_period_ms: 250")
                if (PerfettoProbe.KERNEL_MEMINFO in probes) appendLine("  meminfo_period_ms: 1000")
                if (PerfettoProbe.VMSTAT in probes) appendLine("  vmstat_period_ms: 1000")
                appendLine("}")
            },
        )

    private fun heapProfile(targetPackage: String): PerfettoDataSource =
        PerfettoDataSource(
            "android.heapprofd",
            """
            heapprofd_config {
              sampling_interval_bytes: 4096
              process_cmdline: "${escapeProtoString(targetPackage)}"
            }
            """.trimIndent(),
        )

    private fun javaHeapDump(targetPackage: String): PerfettoDataSource =
        PerfettoDataSource(
            "android.java_hprof",
            """
            java_hprof_config {
              process_cmdline: "${escapeProtoString(targetPackage)}"
            }
            """.trimIndent(),
        )

    private fun perfSampling(targetPackage: String?): PerfettoDataSource =
        PerfettoDataSource(
            "linux.perf",
            buildString {
                appendLine("perf_event_config {")
                appendLine("  timebase { frequency: 100 timestamp_clock: PERF_CLOCK_MONOTONIC }")
                appendLine("  callstack_sampling {")
                targetPackage?.takeIf(String::isNotBlank)?.let {
                    appendLine("    scope { target_cmdline: \"${escapeProtoString(it)}\" }")
                }
                appendLine("  }")
                appendLine("}")
            },
        )

    @Suppress("CyclomaticComplexMethod")
    private fun ftraceEvents(probe: PerfettoProbe): List<String> =
        when (probe) {
            PerfettoProbe.CPU_SCHEDULING ->
                listOf(
                    "sched/sched_switch",
                    "power/suspend_resume",
                    "sched/sched_blocked_reason",
                    "sched/sched_wakeup",
                    "sched/sched_wakeup_new",
                    "sched/sched_waking",
                    "sched/sched_process_exit",
                    "sched/sched_process_free",
                    "task/task_newtask",
                    "task/task_rename",
                )
            PerfettoProbe.CPU_FREQUENCY ->
                listOf("power/cpu_frequency", "power/cpu_idle", "power/suspend_resume")
            PerfettoProbe.SYSCALLS -> listOf("raw_syscalls/sys_enter", "raw_syscalls/sys_exit")
            PerfettoProbe.HIGH_FREQUENCY_MEMORY ->
                listOf(
                    "mm_event/mm_event_record",
                    "kmem/rss_stat",
                    "ion/ion_stat",
                    "dmabuf_heap/dma_heap_stat",
                    "kmem/ion_heap_grow",
                    "kmem/ion_heap_shrink",
                )
            PerfettoProbe.LOW_MEMORY_KILLER ->
                listOf("lowmemorykiller/lowmemory_kill", "oom/oom_score_adj_update")
            PerfettoProbe.BOARD_VOLTAGES ->
                listOf(
                    "regulator/regulator_set_voltage",
                    "regulator/regulator_set_voltage_complete",
                    "power/clock_enable",
                    "power/clock_disable",
                    "power/clock_set_rate",
                    "power/suspend_resume",
                )
            PerfettoProbe.GPU_FREQUENCY -> listOf("power/gpu_frequency")
            PerfettoProbe.GPU_MEMORY -> listOf("gpu_mem/gpu_mem_total")
            PerfettoProbe.GPU_WORK_PERIOD -> listOf("power/gpu_work_period")
            PerfettoProbe.MALI_FENCE_EVENTS ->
                listOf(
                    "mali/mali_KCPU_FENCE_SIGNAL",
                    "mali/mali_KCPU_FENCE_WAIT_END",
                    "mali/mali_KCPU_FENCE_WAIT_START",
                )
            PerfettoProbe.ATRACE -> listOf("ftrace/print")
            PerfettoProbe.WIFI_NETWORK ->
                listOf("cfg80211/*", "mac80211/*", "net/netif_receive_skb", "net/net_dev_xmit")
            PerfettoProbe.PROCESS_THREAD_ASSOCIATION ->
                listOf("sched/sched_process_exit", "sched/sched_process_free", "task/task_newtask", "task/task_rename")
            else -> emptyList()
        }

    private fun atraceCategories(template: PerfettoTraceTemplate): List<String> =
        when (template) {
            PerfettoTraceTemplate.SYSTEM_OVERVIEW -> listOf("am", "binder_driver", "gfx", "view", "wm", "power")
            PerfettoTraceTemplate.APP_PERFORMANCE -> listOf("am", "binder_driver", "gfx", "view", "wm")
            PerfettoTraceTemplate.GFX_PIPELINE -> listOf("gfx", "view", "wm")
            PerfettoTraceTemplate.INPUT_LATENCY -> listOf("input", "view", "wm")
            PerfettoTraceTemplate.MEMORY_PROFILE -> listOf("am", "dalvik")
            PerfettoTraceTemplate.CUSTOM -> emptyList()
        }

    private fun escapeProtoString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private val SYS_STATS_PROBES =
        setOf(PerfettoProbe.CPU_USAGE, PerfettoProbe.CPU_FREQUENCY, PerfettoProbe.KERNEL_MEMINFO, PerfettoProbe.VMSTAT)
    private val PROCESS_STATS_PROBES = setOf(PerfettoProbe.PROCESS_STATS, PerfettoProbe.PROCESS_THREAD_ASSOCIATION)
}
