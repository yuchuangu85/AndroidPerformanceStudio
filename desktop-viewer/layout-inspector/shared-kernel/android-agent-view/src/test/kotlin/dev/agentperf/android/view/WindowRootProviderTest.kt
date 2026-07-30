package com.androidperformancestudio.android.view

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WindowRootProviderTest {
    @Test
    fun `api 29 uses every process window`() {
        assertEquals(
            listOf("activity", "dialog"),
            processWindowRoots(
                sdkInt = 29,
                globalRoots = { listOf("activity", "dialog") },
                activityRoot = { "fallback" },
            ),
        )
    }

    @Test
    fun `older api and empty global roots use activity window`() {
        assertEquals(
            listOf("activity"),
            processWindowRoots(28, { listOf("dialog") }, { "activity" }),
        )
        assertEquals(
            listOf("activity"),
            processWindowRoots(29, { emptyList<String>() }, { "activity" }),
        )
    }
}
