package com.androidperformancestudio.platform.perfetto

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

public class PerfettoConfigComposerTest {
    @Test
    public fun `composes only feature requested data sources`() {
        val text =
            PerfettoConfigComposer.compose(
                PerfettoCaptureDocument(
                    durationMillis = 2_000,
                    bufferSizeKb = 4_096,
                    dataSources =
                        listOf(
                            PerfettoDataSource("linux.ftrace", "ftrace_config {\n  ftrace_events: \"sched/sched_switch\"\n}"),
                        ),
                ),
            )

        assertContains(text, "duration_ms: 2000")
        assertContains(text, "name: \"linux.ftrace\"")
        assertFailsWith<IllegalArgumentException> {
            PerfettoCaptureDocument(1, 1, emptyList())
        }
    }
}
