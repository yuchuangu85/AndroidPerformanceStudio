package com.androidperformancestudio.desktop

import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidPackage
import com.androidperformancestudio.adb.AndroidProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DesktopTargetBarSourceTest {
    @Test
    fun `only application processes are offered by the fixed process selector`() {
        val snapshot =
            AdbTargetSnapshot(
                packages = listOf(AndroidPackage("com.example.camera")),
                processes =
                    listOf(
                        AndroidProcess(321, 1, "u0_a10", "com.example.camera"),
                        AndroidProcess(322, 1, "u0_a10", "com.example.camera:remote"),
                        AndroidProcess(654, 1, "root", "surfaceflinger"),
                        AndroidProcess(655, 1, "system", "system_server"),
                    ),
            )

        val options = snapshot.applicationProcessOptions()

        assertEquals(listOf(321, 322), options.map { it.pid })
        assertEquals(listOf("com.example.camera", "com.example.camera"), options.map { it.packageName })
    }
}
