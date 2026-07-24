@file:Suppress("MaxLineLength")

package com.androidperformancestudio.startup.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopStartupBackendTest {
    @Test
    fun `parses only launcher component rows`() {
        val output =
            """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            dev.sample/.MainActivity
            other.app/com.other.Launcher
            No activities found
            """.trimIndent()

        assertEquals(listOf("dev.sample/.MainActivity", "other.app/com.other.Launcher"), parseLauncherComponents(output))
    }
}
