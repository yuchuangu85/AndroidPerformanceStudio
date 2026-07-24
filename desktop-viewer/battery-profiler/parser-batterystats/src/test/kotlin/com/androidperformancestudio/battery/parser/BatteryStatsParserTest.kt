package com.androidperformancestudio.battery.parser

import com.androidperformancestudio.battery.model.BatteryHistoryEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryStatsParserTest {
    private val parser = BatteryStatsParser()

    @Test
    fun `parses uid resources network energy and battery state`() {
        val checkin =
            """
            9,0,l,bt,1700000000,1000
            9,10123,l,wl,location-lock,5000000,2,0,0,0
            9,10123,l,apk,alarm-tag,0,4,0,0,0
            9,10123,l,jb,sync-job,9000000,3,0,0,0
            9,10123,l,sr,21,7000000,1,0,0,0
            9,10123,l,nt,100,200,300,400,5,6,7,8,9,10,11000
            9,0,l,h,1234,+wake_lock=location-lock,uid=10123
            """.trimIndent()
        val parsed = parser.parse(checkin, "Uid u0a123: 1.25 ( cpu=0.50 wifi=0.75 )", "level: 87", 10123)

        assertEquals(
            5_000,
            parsed.uidStats.wakelocks
                .getValue("location-lock")
                .durationMs,
        )
        assertEquals(
            4,
            parsed.uidStats.alarms
                .getValue("alarm-tag")
                .count,
        )
        assertEquals(1_011, parsed.uidStats.network.totalBytes)
        assertEquals(
            1.25,
            parsed.uidStats.energy
                .getValue("total")
                .energyMah,
        )
        assertEquals(BatteryHistoryEventKind.WAKELOCK, parsed.history.single().kind)
        assertEquals(87, parser.parseDeviceState("level: 87\ntemperature: 321\nUSB powered: true").levelPercent)
        assertEquals(true, parser.parseDeviceState("USB powered: true").powered)
    }

    @Test
    fun `preserves unknown records and warns when uid is absent`() {
        val result = parser.parse("9,1000,l,unknown,x", "", "", 12345)
        assertTrue(result.warnings.any { "12345" in it })
    }
}
