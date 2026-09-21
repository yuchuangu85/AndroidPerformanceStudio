package com.androidperformancestudio.frame.app

import com.androidperformancestudio.adb.AdbTargetSnapshot
import com.androidperformancestudio.adb.AndroidPackage
import com.androidperformancestudio.adb.AndroidProcess
import com.androidperformancestudio.frame.presentation.FrameProcessOption
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopFrameOnlineBackendTest {
    @Test
    fun `profileable by shell processes remain selectable for frame capture`() {
        val snapshot =
            AdbTargetSnapshot(
                packages =
                    listOf(
                        AndroidPackage("com.example.debug", debuggable = true),
                        AndroidPackage("com.example.profileable", profileableByShell = true),
                        AndroidPackage("com.example.hidden"),
                    ),
                processes =
                    listOf(
                        AndroidProcess(10, 1, "u0_a10", "com.example.debug"),
                        AndroidProcess(11, 1, "u0_a11", "com.example.profileable:renderer"),
                        AndroidProcess(12, 1, "u0_a12", "com.example.hidden"),
                    ),
            )

        assertEquals(
            listOf(
                FrameProcessOption(10, "com.example.debug", "com.example.debug"),
                FrameProcessOption(11, "com.example.profileable:renderer", "com.example.profileable"),
            ),
            snapshot.frameCaptureProcesses(),
        )
    }
}
