package com.androidperformancestudio.perfetto.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PerfettoDeviceCapabilitiesTest {
    @Test
    fun `probe support uses sdk and advertised data sources`() {
        val capabilities =
            PerfettoDeviceCapabilities(
                androidSdk = 32,
                dataSourceNames = setOf("linux.ftrace", "linux.sys_stats"),
            )

        assertEquals(null, capabilities.unsupportedReason(PerfettoProbe.CPU_SCHEDULING))
        assertEquals("Requires Android 34+", capabilities.unsupportedReason(PerfettoProbe.NETWORK_PACKETS))
        assertEquals(
            "android.log is unavailable on this device",
            capabilities.unsupportedReason(PerfettoProbe.ANDROID_LOG),
        )
    }
}
