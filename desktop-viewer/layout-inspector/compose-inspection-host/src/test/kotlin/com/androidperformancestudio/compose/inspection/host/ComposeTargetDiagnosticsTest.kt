package com.androidperformancestudio.compose.inspection.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposeTargetDiagnosticsTest {
    @Test
    fun `reports the latest crash exception of the inspected package`() {
        val log =
            """
            E/AndroidRuntime: FATAL EXCEPTION: main
            E/AndroidRuntime: Process: com.example.app, PID: 100
            E/AndroidRuntime: java.lang.IllegalStateException: older failure
            E/AndroidRuntime: 	at com.example.app.First.run(First.kt:10)
            E/AndroidRuntime: FATAL EXCEPTION: main
            E/AndroidRuntime: Process: com.example.app, PID: 200
            E/AndroidRuntime: java.lang.IllegalStateException: You must call setGraph() before calling getGraph()
            E/AndroidRuntime: 	at com.example.app.Navigation.run(Navigation.kt:41)
            E/AndroidRuntime: FATAL EXCEPTION: main
            E/AndroidRuntime: Process: com.other.app, PID: 300
            E/AndroidRuntime: java.lang.RuntimeException: unrelated
            """.trimIndent()

        assertEquals(
            "java.lang.IllegalStateException: You must call setGraph() before calling getGraph()",
            lastTargetCrashReasonFromLog(log, "com.example.app"),
        )
        assertNull(lastTargetCrashReasonFromLog(log, "com.absent.app"))
        assertEquals("java.lang.RuntimeException: unrelated", lastTargetCrashReasonFromLog(log, "com.other.app"))
    }
}
