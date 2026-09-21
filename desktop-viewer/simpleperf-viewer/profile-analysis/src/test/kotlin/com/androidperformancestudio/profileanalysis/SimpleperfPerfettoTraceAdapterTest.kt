package com.androidperformancestudio.profileanalysis

import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleperfPerfettoTraceAdapterTest {
    @Test
    fun `maps sched binder frame and frequency evidence into canonical facts`() {
        val header = "ts,dur,name,process_name,thread_name,pid,tid\n"
        val result =
            SimpleperfPerfettoTraceAdapter().mapFixture(
                schedulingCsv = header + "10,5,Running,app,main,100,101\n",
                binderCsv = header + "20,7,IFoo.call,app,main,100,101\n",
                frameCsv = header + "30,16,FrameTimeline:Jank,app,[NULL],100,[NULL]\n",
                counterCsv = "ts,name,unit,value\n12,cpu0 frequency,kHz,1800000\n13,gpu frequency,Hz,600000000\n",
            )

        assertEquals(
            "Scheduling",
            result.scheduling
                .single()
                .category
                ?.name,
        )
        assertEquals(
            "Binder",
            result.binder
                .single()
                .category
                ?.subcategory,
        )
        assertEquals(
            "FrameTimeline",
            result.frameTimeline
                .single()
                .category
                ?.subcategory,
        )
        assertEquals(2, result.frequencyCounters.size)
    }
}
