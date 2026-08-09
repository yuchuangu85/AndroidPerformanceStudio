package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import com.androidperformancestudio.platform.perfetto.PerfettoCaptureDocument
import com.androidperformancestudio.platform.perfetto.PerfettoConfigComposer
import com.androidperformancestudio.platform.perfetto.PerfettoDataSource

/** Builds a text-proto TraceConfig accepted by the Android `perfetto --txt` CLI. */
object PerfettoConfigTextBuilder {
    fun build(config: PerfettoCaptureConfig): String {
        config.customConfigText
            ?.takeIf { config.template == PerfettoTraceTemplate.CUSTOM && it.isNotBlank() }
            ?.let { return it }

        val template = templateSettings(config.template)
        val extraFtraceEvents = config.additionalCategories.filter { '/' in it }
        val extraAtraceCategories = config.additionalCategories.filterNot { '/' in it }
        val ftraceEvents = (template.ftraceEvents + extraFtraceEvents).distinct()
        val atraceCategories = (template.atraceCategories + extraAtraceCategories).distinct()

        val dataSources =
            buildList {
                add(
                    PerfettoDataSource(
                        "linux.ftrace",
                        buildString {
                            appendLine("ftrace_config {")
                            ftraceEvents.forEach { event -> appendLine("  ftrace_events: \"${escapeProtoString(event)}\"") }
                            atraceCategories.forEach { category ->
                                appendLine("  atrace_categories: \"${escapeProtoString(category)}\"")
                            }
                            config.targetPackage?.takeIf(String::isNotBlank)?.let { targetPackage ->
                                appendLine("  atrace_apps: \"${escapeProtoString(targetPackage)}\"")
                            }
                            appendLine("  symbolize_ksyms: true")
                            appendLine("}")
                        },
                    ),
                )
                add(processStats(template.pollMemory))
                if (template.collectSystemStats) add(systemStats())
                if (template.collectFrameTimeline) add(PerfettoDataSource("android.surfaceflinger.frametimeline"))
                add(PerfettoDataSource("android.packages_list"))
                if (template.collectNativeHeap) {
                    config.targetPackage?.takeIf(String::isNotBlank)?.let { add(heapProfile(it)) }
                }
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

    private fun systemStats(): PerfettoDataSource =
        PerfettoDataSource(
            "linux.sys_stats",
            """
            sys_stats_config {
              cpufreq_period_ms: 250
              meminfo_period_ms: 1000
              vmstat_period_ms: 1000
              stat_period_ms: 1000
            }
            """.trimIndent(),
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

    private fun escapeProtoString(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun templateSettings(template: PerfettoTraceTemplate): TemplateSettings =
        when (template) {
            PerfettoTraceTemplate.SYSTEM_OVERVIEW ->
                TemplateSettings(
                    ftraceEvents =
                        listOf(
                            "sched/sched_switch",
                            "sched/sched_waking",
                            "sched/sched_wakeup",
                            "sched/sched_wakeup_new",
                            "sched/sched_blocked_reason",
                            "power/cpu_frequency",
                            "power/cpu_idle",
                            "binder/binder_transaction",
                            "binder/binder_transaction_received",
                        ),
                    atraceCategories = listOf("am", "binder_driver", "gfx", "view", "wm", "power"),
                    collectFrameTimeline = true,
                    collectSystemStats = true,
                    pollMemory = true,
                )
            PerfettoTraceTemplate.APP_PERFORMANCE ->
                TemplateSettings(
                    ftraceEvents =
                        listOf(
                            "sched/sched_switch",
                            "sched/sched_waking",
                            "sched/sched_wakeup_new",
                            "binder/binder_transaction",
                            "binder/binder_transaction_received",
                        ),
                    atraceCategories = listOf("am", "binder_driver", "gfx", "view", "wm"),
                    collectFrameTimeline = true,
                    collectSystemStats = true,
                )
            PerfettoTraceTemplate.GFX_PIPELINE ->
                TemplateSettings(
                    ftraceEvents = listOf("sched/sched_switch", "sched/sched_waking", "power/cpu_frequency"),
                    atraceCategories = listOf("gfx", "view", "wm"),
                    collectFrameTimeline = true,
                    collectSystemStats = true,
                )
            PerfettoTraceTemplate.INPUT_LATENCY ->
                TemplateSettings(
                    ftraceEvents =
                        listOf(
                            "sched/sched_switch",
                            "sched/sched_waking",
                            "sched/sched_wakeup_new",
                            "binder/binder_transaction",
                            "binder/binder_transaction_received",
                        ),
                    atraceCategories = listOf("input", "view", "wm"),
                )
            PerfettoTraceTemplate.MEMORY_PROFILE ->
                TemplateSettings(
                    ftraceEvents =
                        listOf(
                            "sched/sched_switch",
                            "kmem/rss_stat",
                            "mm_event/mm_event_record",
                            "oom/oom_score_adj_update",
                            "lowmemorykiller/lowmemory_kill",
                        ),
                    atraceCategories = listOf("am", "dalvik"),
                    collectSystemStats = true,
                    pollMemory = true,
                    collectNativeHeap = true,
                )
            PerfettoTraceTemplate.CUSTOM -> TemplateSettings()
        }

    private data class TemplateSettings(
        val ftraceEvents: List<String> = emptyList(),
        val atraceCategories: List<String> = emptyList(),
        val collectFrameTimeline: Boolean = false,
        val collectSystemStats: Boolean = false,
        val pollMemory: Boolean = false,
        val collectNativeHeap: Boolean = false,
    )
}
