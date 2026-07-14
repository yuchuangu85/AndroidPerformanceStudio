package dev.agentperf.android.view

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LifecycleActivitySlotTest {
    @Test
    fun `pause retains the latest Activity until it is destroyed`() {
        val activity = Any()
        val slot = LifecycleActivitySlot<Any>()

        slot.onStarted(activity)
        slot.onResumed(activity)
        slot.onPaused(activity)

        assertSame(activity, slot.current())

        slot.onDestroyed(activity)
        assertNull(slot.current())
    }
}
