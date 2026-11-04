@file:Suppress("MaxLineLength")

package com.androidperformancestudio.compose.inspection.host

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeAgentAttachDiagnosticsTest {
    private val token = InspectorSessionToken.parse("a".repeat(64))

    @Test
    fun `framework loader attempts are diagnostic context not terminal failures`() {
        val diagnostics =
            requireNotNull(
                composeAgentAttachDiagnostics(
                    "10-30 10:00:01.100 1234 1234 E ActivityThread: Attaching agent with dalvik.system.PathClassLoader failed\r\n" +
                        "10-30 10:00:01.101 1234 1234 E ActivityThread: Attaching agent with null classloader failed\r\n",
                    token,
                ),
            )

        assertFalse(diagnostics.terminalBootstrapFailure)
        assertTrue(diagnostics.logcat.contains("PathClassLoader"))
        assertTrue(diagnostics.logcat.contains("null classloader"))
    }

    @Test
    fun `ART warning is summarized before a long framework classloader description`() {
        val diagnostics =
            requireNotNull(
                composeAgentAttachDiagnostics(
                    "10-30 10:00:01.100 1234 1234 E ActivityThread: Attaching agent with " + "base.apk ".repeat(3_000) + "failed\n" +
                        "10-30 10:00:01.101 1234 1234 W dev.sample: Agent attach failed: dlopen failed: cannot locate symbol\n",
                    token,
                ),
            )

        assertTrue(diagnostics.summary.startsWith("Agent attach failed"))
        assertTrue(diagnostics.logcat.endsWith("cannot locate symbol"))
        assertTrue(diagnostics.logcat.length < 16_500)
        assertTrue(diagnostics.logcat.contains("[Device logcat truncated]"))
    }

    @Test
    fun `native bootstrap error includes following Java cause and stack frames`() {
        val diagnostics =
            requireNotNull(
                composeAgentAttachDiagnostics(
                    "10-30 10:00:01.100 1234 1234 E studio.ui-inspector.native: Failed to find InspectorService\n" +
                        "10-30 10:00:01.101 1234 1234 W System.err: java.lang.NoClassDefFoundError: InspectorService\n" +
                        "10-30 10:00:01.102 1234 1234 W System.err:     at java.lang.ClassLoader.loadClass(ClassLoader.java:1)\n" +
                        "10-30 10:00:01.103 1234 1234 W System.err: Caused by: java.lang.ClassNotFoundException: InspectorService\n",
                    token,
                ),
            )

        assertTrue(diagnostics.terminalBootstrapFailure)
        assertTrue(diagnostics.summary.startsWith("Failed to find InspectorService"))
        assertTrue(diagnostics.logcat.contains("ClassLoader.java:1"))
        assertTrue(diagnostics.logcat.contains("Caused by: java.lang.ClassNotFoundException"))
    }

    @Test
    fun `session tokens are redacted even in older attach option logs`() {
        val previousToken = "b".repeat(64)
        val diagnostics =
            requireNotNull(
                composeAgentAttachDiagnostics(
                    "10-30 10:00:01.100 1234 1234 W dev.sample: Agent attach failed: " +
                        "lib_ui_inspector_agent.so=/data/service.jar;/data/payload.jar;1234;${token.value}\n" +
                        "10-30 10:00:01.101 1234 1234 W dev.sample: Agent attach failed: " +
                        "lib_ui_inspector_agent.so=/data/service.jar;/data/payload.jar;1234;$previousToken\n",
                    token,
                ),
            )

        assertFalse(diagnostics.logcat.contains(token.value))
        assertFalse(diagnostics.summary.contains(token.value))
        assertFalse(diagnostics.logcat.contains(previousToken))
        assertTrue(diagnostics.logcat.contains(";1234;[redacted]"))
    }

    @Test
    fun `unrelated framework and library errors do not masquerade as agent errors`() {
        assertNull(
            composeAgentAttachDiagnostics(
                "10-30 10:00:01.100 1234 1234 E ActivityThread: Failed to find provider info for dev.sample.provider\n" +
                    "10-30 10:00:01.101 1234 1234 W nativeloader: dlopen failed: other-plugin.so not found\n",
                token,
            ),
        )
    }

    @Test
    fun `brief logcat format retains a clear native error`() {
        val diagnostics =
            composeAgentAttachDiagnostics(
                "E/studio.ui-inspector.native(1234): Invalid options format\n",
                token,
            )

        assertNotNull(diagnostics)
        assertTrue(requireNotNull(diagnostics).terminalBootstrapFailure)
    }

    @Test
    fun `JVMTI plugin warning is retained without an inspector tag`() {
        val diagnostics =
            requireNotNull(
                composeAgentAttachDiagnostics(
                    "10-30 10:00:01.100 1234 1234 W dev.sample: Could not load plugin: dlopen failed: libopenjdkjvmti.so not found\n",
                    token,
                ),
            )

        assertTrue(diagnostics.summary.contains("Could not load plugin"))
        assertTrue(diagnostics.logcat.contains("libopenjdkjvmti.so not found"))
        assertFalse(diagnostics.terminalBootstrapFailure)
    }
}
