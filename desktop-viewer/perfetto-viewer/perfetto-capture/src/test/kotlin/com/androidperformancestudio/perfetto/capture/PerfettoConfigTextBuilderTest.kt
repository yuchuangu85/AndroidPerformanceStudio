package com.androidperformancestudio.perfetto.capture

import com.androidperformancestudio.perfetto.model.PerfettoCaptureConfig
import com.androidperformancestudio.perfetto.model.PerfettoProbe
import com.androidperformancestudio.perfetto.model.PerfettoTraceTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoConfigTextBuilderTest {
    @Test
    fun `app template emits valid ftrace atrace and frame timeline sources`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.APP_PERFORMANCE,
                    targetPackage = "com.example.\"debug\\app",
                    durationSeconds = 15,
                    bufferSizeKb = 8192,
                    additionalCategories = listOf("dalvik", "sched/sched_process_exit"),
                ),
            )

        assertTrue(text.contains("duration_ms: 15000"))
        assertTrue(text.contains("size_kb: 8192"))
        assertTrue(text.contains("name: \"linux.ftrace\""))
        assertTrue(text.contains("atrace_categories: \"dalvik\""))
        assertTrue(text.contains("ftrace_events: \"sched/sched_process_exit\""))
        assertTrue(text.contains("atrace_apps: \"com.example.\\\"debug\\\\app\""))
        assertTrue(text.contains("name: \"android.surfaceflinger.frametimeline\""))
        assertTrue(text.contains("name: \"linux.process_stats\""))
        assertFalse(text.contains("android.atrace"))
        assertFalse(text.contains("android_atrace_config"))
    }

    @Test
    fun `memory template adds polled memory and targeted native heap sources`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.MEMORY_PROFILE,
                    targetPackage = "com.example.app",
                ),
            )

        assertTrue(text.contains("proc_stats_poll_ms: 1000"))
        assertTrue(text.contains("name: \"linux.sys_stats\""))
        assertTrue(text.contains("meminfo_period_ms: 1000"))
        assertTrue(text.contains("name: \"android.heapprofd\""))
        assertTrue(text.contains("process_cmdline: \"com.example.app\""))
    }

    @Test
    fun `input latency template uses ftrace atrace categories supported by production Perfetto`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(template = PerfettoTraceTemplate.INPUT_LATENCY),
            )

        assertTrue(text.contains("atrace_categories: \"input\""))
        assertTrue(text.contains("atrace_categories: \"view\""))
        assertTrue(text.contains("atrace_categories: \"wm\""))
        assertFalse(text.contains("android.input.inputevent"))
        assertFalse(text.contains("android_input_event_config"))
    }

    @Test
    fun `custom template is passed through unchanged`() {
        val custom = "buffers { size_kb: 1024 }\nduration_ms: 1000"

        assertEquals(
            custom,
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.CUSTOM,
                    customConfigText = custom,
                ),
            ),
        )
    }

    @Test
    fun `custom capture completion follows duration in custom config`() {
        val config =
            PerfettoCaptureConfig(
                template = PerfettoTraceTemplate.CUSTOM,
                durationSeconds = 10,
                customConfigText = "buffers { size_kb: 1024 }\nduration_ms: 60000",
            )

        assertEquals(60_000L, automaticCompletionDelayMillis(config))
    }

    @Test
    fun `custom capture without duration is stopped explicitly by the user`() {
        val config =
            PerfettoCaptureConfig(
                template = PerfettoTraceTemplate.CUSTOM,
                customConfigText = "buffers { size_kb: 1024 }",
            )

        assertEquals(null, automaticCompletionDelayMillis(config))
    }

    @Test
    fun `selected record page probes are composed into one trace config`() {
        val text =
            PerfettoConfigTextBuilder.build(
                PerfettoCaptureConfig(
                    template = PerfettoTraceTemplate.SYSTEM_OVERVIEW,
                    targetPackage = "com.example.app",
                    enabledProbes =
                        setOf(
                            PerfettoProbe.SYSCALLS,
                            PerfettoProbe.JAVA_HEAP_DUMP,
                            PerfettoProbe.POWER_RAILS,
                            PerfettoProbe.GPU_MEMORY,
                            PerfettoProbe.ANDROID_LOG,
                            PerfettoProbe.NETWORK_PACKETS,
                            PerfettoProbe.CALLSTACK_SAMPLING,
                            PerfettoProbe.TRACK_EVENTS,
                        ),
                ),
            )

        assertTrue(text.contains("raw_syscalls/sys_enter"))
        assertTrue(text.contains("name: \"android.java_hprof\""))
        assertTrue(text.contains("name: \"android.power\""))
        assertTrue(text.contains("name: \"android.gpu.memory\""))
        assertTrue(text.contains("name: \"android.log\""))
        assertTrue(text.contains("name: \"android.network_packets\""))
        assertTrue(text.contains("name: \"linux.perf\""))
        assertTrue(text.contains("name: \"track_event\""))
        assertEquals(1, Regex("name: \"linux.ftrace\"").findAll(text).count())
    }
}
