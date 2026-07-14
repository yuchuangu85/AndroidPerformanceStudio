package dev.agentperf.desktop

import dev.agentperf.adb.AdbDevice
import dev.agentperf.adb.DeviceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceSelectionUiStateTest {
    @Test
    fun `device choices prefer model label and keep serial identity`() {
        val choices = deviceChoices(
            listOf(
                AdbDevice(serial = "emulator-5554", state = DeviceState.DEVICE, model = "sdk_gphone"),
                AdbDevice(serial = "R3CN30ABC", state = DeviceState.DEVICE, model = "Pixel_8"),
            ),
        )

        assertEquals(
            listOf(
                DeviceChoiceModel("emulator-5554", "sdk_gphone · emulator-5554"),
                DeviceChoiceModel("R3CN30ABC", "Pixel_8 · R3CN30ABC"),
            ),
            choices,
        )
    }

    @Test
    fun `selected serial falls back to automatic when disconnected`() {
        assertEquals(null, sanitizeSelectedDeviceSerial("missing", emptyList()))
        assertEquals(
            "physical-1",
            sanitizeSelectedDeviceSerial(
                "physical-1",
                listOf(AdbDevice("physical-1", DeviceState.DEVICE)),
            ),
        )
    }
}
