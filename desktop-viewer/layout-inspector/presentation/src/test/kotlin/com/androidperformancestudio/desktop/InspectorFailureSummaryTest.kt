package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorFailureSummaryTest {
    @Test
    fun `wrapped exception exposes the deepest cause message`() {
        val error = IllegalStateException(
            "Unable to capture foreground app com.android.systemui through ADB " +
                "(visible-window-dump: exit 0; uiautomator: exit -1)",
            RuntimeException(
                IllegalArgumentException(
                    "Unexpected special floating-point value NaN. " +
                        "It is possible to deserialize them using allowSpecialFloatingPointValues",
                ),
            ),
        )

        val summary = error.failureReasonSummary()

        assertTrue(summary.startsWith(
            "Unable to capture foreground app com.android.systemui through ADB",
        ))
        assertTrue(summary.contains("(caused by: Unexpected special floating-point value NaN"))
    }

    @Test
    fun `exception without a cause keeps its own message`() {
        val error = IllegalStateException("Foreground app is not enabled")

        assertEquals("Foreground app is not enabled", error.failureReasonSummary())
    }

    @Test
    fun `multi line root cause keeps only the first line`() {
        val error = IllegalStateException(
            "capture failed",
            RuntimeException("first line\nsecond line"),
        )

        assertEquals("capture failed (caused by: first line)", error.failureReasonSummary())
    }

    @Test
    fun `long root cause is truncated`() {
        val error = IllegalStateException("capture failed", RuntimeException("x".repeat(400)))

        val summary = error.failureReasonSummary()

        assertEquals("capture failed (caused by: ${"x".repeat(200)})", summary)
    }

    @Test
    fun `root cause already contained in the base message is not duplicated`() {
        val error = IllegalStateException(
            "encode: layout snapshot serialization failed: Unexpected special floating-point value NaN",
            IllegalArgumentException("Unexpected special floating-point value NaN"),
        )

        val summary = error.failureReasonSummary()

        assertEquals(
            "encode: layout snapshot serialization failed: Unexpected special floating-point value NaN",
            summary,
        )
    }
}
