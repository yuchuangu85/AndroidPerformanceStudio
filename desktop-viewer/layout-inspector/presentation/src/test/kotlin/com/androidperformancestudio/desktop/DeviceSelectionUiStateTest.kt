package com.androidperformancestudio.desktop

import com.androidperformancestudio.platform.adb.AndroidDeviceInfo
import com.androidperformancestudio.platform.adb.AdbDeviceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceSelectionUiStateTest {

    @Test
    fun `device choice does not duplicate a serial used as model fallback`() {
        val choices =
            deviceChoices(
                listOf(AndroidDeviceInfo("serial-1", "serial-1", "serial-1", null, null, null, AdbDeviceState.ONLINE, null, "device", null)),
            )

        assertEquals(listOf(DeviceChoiceModel("serial-1", "serial-1")), choices)
    }

    @Test
    fun `device choices prefer model label and keep serial identity`() {
        val choices = deviceChoices(
            listOf(
                AndroidDeviceInfo("emulator-5554", "sdk_gphone", "sdk_gphone(emulator-5554)", null, null, null, AdbDeviceState.ONLINE, null, "device", null),
                AndroidDeviceInfo("R3CN30ABC", "Pixel_8", "Pixel_8(R3CN30ABC)", null, null, null, AdbDeviceState.ONLINE, null, "device", null),
            ),
        )

        assertEquals(
            listOf(
                DeviceChoiceModel("emulator-5554", "sdk_gphone(emulator-5554)"),
                DeviceChoiceModel("R3CN30ABC", "Pixel_8(R3CN30ABC)"),
            ),
            choices,
        )
    }

    @Test
    fun `selected serial falls back to the only connected device`() {
        assertEquals(null, sanitizeSelectedDeviceSerial("missing", emptyList()))
        assertEquals(
            "physical-1",
            sanitizeSelectedDeviceSerial(
                "physical-1",
                listOf(AndroidDeviceInfo("physical-1", "physical-1", "physical-1", null, null, null, AdbDeviceState.ONLINE, null, "device", null)),
            ),
        )
        assertEquals(
            "physical-1",
            sanitizeSelectedDeviceSerial(
                null,
                listOf(AndroidDeviceInfo("physical-1", "physical-1", "physical-1", null, null, null, AdbDeviceState.ONLINE, null, "device", null)),
            ),
        )
        assertEquals(
            null,
            sanitizeSelectedDeviceSerial(
                null,
                listOf(
                    AndroidDeviceInfo("physical-1", "physical-1", "physical-1", null, null, null, AdbDeviceState.ONLINE, null, "device", null),
                    AndroidDeviceInfo("physical-2", "physical-2", "physical-2", null, null, null, AdbDeviceState.ONLINE, null, "device", null),
                ),
            ),
        )
    }
}
