package com.androidperformancestudio.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SamplingTemplatesTest {
    @Test
    fun `provides the documented app cpu ui thread native and system templates`() {
        assertEquals(
            listOf(
                SamplingTemplate.APP_CPU_BASIC,
                SamplingTemplate.UI_THREAD_FOCUS,
                SamplingTemplate.NATIVE_HOTSPOT,
                SamplingTemplate.LOW_OVERHEAD,
                SamplingTemplate.SYSTEM_PROCESS,
            ),
            SamplingTemplate.entries,
        )
    }

    @Test
    fun `app cpu template uses cpu clock dwarf and one thousand hertz`() {
        val parameters = SamplingTemplate.APP_CPU_BASIC.create(SimpleperfTarget.App("com.example.app"))

        assertEquals("cpu-clock", parameters.event)
        assertEquals(SamplingRate.Frequency(1000), parameters.rate)
        assertEquals(10.0, parameters.durationSeconds)
        assertEquals(CallGraphMode.DWARF, parameters.callGraph)
        assertEquals(EventScope.USER, parameters.scope)
    }

    @Test
    fun `ui thread template retains the selected thread`() {
        val parameters =
            SamplingTemplate.UI_THREAD_FOCUS.create(
                SimpleperfTarget.Thread(333, appPackage = "com.example.app"),
            )

        assertIs<SimpleperfTarget.Thread>(parameters.target)
        assertEquals(SamplingRate.Frequency(1000), parameters.rate)
        assertEquals(EventScope.USER, parameters.scope)
    }

    @Test
    fun `low overhead template uses frame pointers and reduced frequency`() {
        val parameters = SamplingTemplate.LOW_OVERHEAD.create(SimpleperfTarget.Process(321))

        assertEquals(CallGraphMode.FRAME_POINTER, parameters.callGraph)
        assertEquals(SamplingRate.Frequency(100), parameters.rate)
    }
}
