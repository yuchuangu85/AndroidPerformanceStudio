package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.perfetto.model.PerfettoDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PerfettoDeviceSelectionTest {
    @Test
    fun `selects the only online device when no valid selection remains`() {
        val offline = PerfettoDevice("offline", "Offline", online = false)
        val online = PerfettoDevice("online", "Online")

        assertEquals("online", preferredDeviceSerial(null, listOf(offline, online)))
        assertEquals("online", preferredDeviceSerial("missing", listOf(offline, online)))
        assertEquals("online", preferredDeviceSerial("online", listOf(online)))
        assertNull(preferredDeviceSerial(null, listOf(PerfettoDevice("one", "One"), PerfettoDevice("two", "Two"))))
    }
}
