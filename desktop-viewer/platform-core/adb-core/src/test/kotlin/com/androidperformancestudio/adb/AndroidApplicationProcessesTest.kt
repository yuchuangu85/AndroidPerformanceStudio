package com.androidperformancestudio.adb

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidApplicationProcessesTest {
    @Test
    fun `application processes include non-debuggable SystemUI and secondary app processes`() {
        val snapshot =
            AdbTargetSnapshot(
                packages =
                    listOf(
                        AndroidPackage("com.android.systemui"),
                        AndroidPackage("com.example.app"),
                    ),
                processes =
                    listOf(
                        AndroidProcess(43, 1, "u0_a1", "com.example.app:worker"),
                        AndroidProcess(101, 1, "system", "com.android.systemui:screenshot"),
                        AndroidProcess(42, 1, "u0_a1", "com.example.app"),
                        AndroidProcess(100, 1, "system", "com.android.systemui"),
                    ),
            )

        assertEquals(
            listOf(
                AndroidApplicationProcess(100, "com.android.systemui", "system", "com.android.systemui"),
                AndroidApplicationProcess(101, "com.android.systemui:screenshot", "system", "com.android.systemui"),
                AndroidApplicationProcess(42, "com.example.app", "u0_a1", "com.example.app"),
                AndroidApplicationProcess(43, "com.example.app:worker", "u0_a1", "com.example.app"),
            ),
            snapshot.applicationProcesses(),
        )
        assertEquals(emptyList(), snapshot.profileableOrDebuggableProcesses())
    }

    @Test
    fun `profileable or debuggable processes retain only eligible main and secondary processes`() {
        val snapshot =
            AdbTargetSnapshot(
                packages =
                    listOf(
                        AndroidPackage("com.android.systemui"),
                        AndroidPackage("com.example.debug", debuggable = true),
                        AndroidPackage("com.example.profile", profileableByShell = true),
                    ),
                processes =
                    listOf(
                        AndroidProcess(31, 1, "u0_a2", "com.example.profile:worker"),
                        AndroidProcess(11, 1, "system", "com.android.systemui:screenshot"),
                        AndroidProcess(21, 1, "u0_a1", "com.example.debug:worker"),
                        AndroidProcess(30, 1, "u0_a2", "com.example.profile"),
                        AndroidProcess(10, 1, "system", "com.android.systemui"),
                        AndroidProcess(20, 1, "u0_a1", "com.example.debug"),
                    ),
            )

        assertEquals(
            listOf(
                AndroidApplicationProcess(20, "com.example.debug", "u0_a1", "com.example.debug"),
                AndroidApplicationProcess(21, "com.example.debug:worker", "u0_a1", "com.example.debug"),
                AndroidApplicationProcess(30, "com.example.profile", "u0_a2", "com.example.profile"),
                AndroidApplicationProcess(31, "com.example.profile:worker", "u0_a2", "com.example.profile"),
            ),
            snapshot.profileableOrDebuggableProcesses(),
        )
    }

    @Test
    fun `application processes exclude native unowned and package prefix lookalikes`() {
        val snapshot =
            AdbTargetSnapshot(
                packages = listOf(AndroidPackage("com.example.app", debuggable = true)),
                processes =
                    listOf(
                        AndroidProcess(1, 0, "root", "init"),
                        AndroidProcess(2, 1, "system", "system_server"),
                        AndroidProcess(3, 1, "system", "/system/bin/surfaceflinger"),
                        AndroidProcess(4, 1, "u0_a2", "com.example.uninstalled"),
                        AndroidProcess(5, 1, "u0_a2", "com.example.uninstalled:worker"),
                        AndroidProcess(6, 1, "u0_a1", "com.example.application"),
                        AndroidProcess(7, 1, "u0_a1", "com.example.app2:worker"),
                        AndroidProcess(8, 1, "u0_a1", "com.example.app.extra"),
                        AndroidProcess(9, 1, "u0_a1", "com.example.app_worker"),
                        AndroidProcess(43, 1, "u0_a1", "com.example.app:worker"),
                        AndroidProcess(42, 1, "u0_a1", "com.example.app"),
                    ),
            )
        val expected =
            listOf(
                AndroidApplicationProcess(42, "com.example.app", "u0_a1", "com.example.app"),
                AndroidApplicationProcess(43, "com.example.app:worker", "u0_a1", "com.example.app"),
            )

        assertEquals(expected, snapshot.applicationProcesses())
        assertEquals(expected, snapshot.profileableOrDebuggableProcesses())
    }

    @Test
    fun `application processes sort by name then numeric pid regardless of input order`() {
        val snapshot =
            AdbTargetSnapshot(
                packages =
                    listOf(
                        AndroidPackage("com.example.z", debuggable = true),
                        AndroidPackage("com.example.a", profileableByShell = true),
                    ),
                processes =
                    listOf(
                        AndroidProcess(1, 0, "u0_a2", "com.example.z"),
                        AndroidProcess(100, 1, "u0_a1", "com.example.a:worker"),
                        AndroidProcess(20, 1, "u0_a1", "com.example.a"),
                        AndroidProcess(3, 1, "u0_a1", "com.example.a"),
                        AndroidProcess(9, 1, "u0_a1", "com.example.a:worker"),
                    ),
            )
        val expected =
            listOf(
                "com.example.a" to 3,
                "com.example.a" to 20,
                "com.example.a:worker" to 9,
                "com.example.a:worker" to 100,
                "com.example.z" to 1,
            )

        assertEquals(expected, snapshot.applicationProcesses().map { it.name to it.pid })
        assertEquals(expected, snapshot.profileableOrDebuggableProcesses().map { it.name to it.pid })
    }

    @Test
    fun `application processes require both installed packages and running processes`() {
        val noPackages =
            AdbTargetSnapshot(
                packages = emptyList(),
                processes = listOf(AndroidProcess(42, 1, "u0_a1", "com.example.app")),
            )
        val noProcesses =
            AdbTargetSnapshot(
                packages = listOf(AndroidPackage("com.example.app", debuggable = true)),
                processes = emptyList(),
            )

        assertEquals(emptyList(), noPackages.applicationProcesses())
        assertEquals(emptyList(), noPackages.profileableOrDebuggableProcesses())
        assertEquals(emptyList(), noProcesses.applicationProcesses())
        assertEquals(emptyList(), noProcesses.profileableOrDebuggableProcesses())
    }
}
