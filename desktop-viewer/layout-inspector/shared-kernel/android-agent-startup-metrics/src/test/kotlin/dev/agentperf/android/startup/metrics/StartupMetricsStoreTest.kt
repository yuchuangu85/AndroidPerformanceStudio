package dev.agentperf.android.startup.metrics

import com.androidperformancestudio.startup.agent.protocol.AgentStartupMilestoneKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartupMetricsStoreTest {
    @Test
    fun `cold run associates pre-activity events with intent run id`() {
        val store = store()
        store.add(AgentStartupMilestoneKind.INITIALIZER_ENTER, 10)

        store.associate("cold-1")
        store.add(AgentStartupMilestoneKind.ACTIVITY_CREATED, 20)

        val result = store.result("cold-1")
        assertEquals(2, result.events.size)
        assertEquals(setOf("cold-1"), result.events.map { it.runId }.toSet())
    }

    @Test
    fun `arming isolates subsequent hot run events`() {
        val store = store()
        store.arm("hot-1")
        store.add(AgentStartupMilestoneKind.ACTIVITY_RESUMED, 20)
        store.arm("hot-2")
        store.add(AgentStartupMilestoneKind.ACTIVITY_RESUMED, 30)

        assertEquals(1, store.result("hot-1").events.size)
        assertEquals(1, store.result("hot-2").events.size)
    }

    private fun store() = StartupMetricsStore("dev.sample", 42, "dev.sample", 34, 1)
}
