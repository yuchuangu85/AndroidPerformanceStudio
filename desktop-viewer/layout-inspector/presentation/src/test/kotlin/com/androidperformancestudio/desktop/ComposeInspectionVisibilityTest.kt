package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeInspectionVisibilityTest {
    @Test
    fun `foreground capture automatically prepares the full Compose tree for each new foreground app`() {
        assertEquals(
            "serial:FOREGROUND_APP:com.example",
            automaticComposePreflightKey(
                serial = "serial",
                packageName = "com.example",
                mode = CaptureTargetMode.FOREGROUND_APP,
                authorizedPackage = null,
            ),
        )
    }

    @Test
    fun `switching the foreground app retries preflight instead of reusing the previous attempt`() {
        // The package is part of the key, so a native View app that failed the Compose preflight does
        // not stop a later Compose app, and a previously authorized app is not reused after another
        // app took the foreground.
        assertEquals(
            "serial:FOREGROUND_APP:com.compose",
            automaticComposePreflightKey(
                serial = "serial",
                packageName = "com.compose",
                mode = CaptureTargetMode.FOREGROUND_APP,
                authorizedPackage = "com.native",
            ),
        )
        assertNull(
            automaticComposePreflightKey(
                serial = "serial",
                packageName = "com.compose",
                mode = CaptureTargetMode.FOREGROUND_APP,
                authorizedPackage = "com.compose",
            ),
        )
    }

    @Test
    fun `automatic full Compose preparation never replaces system UI or a missing foreground app`() {
        assertNull(
            automaticComposePreflightKey(
                serial = "serial",
                packageName = "com.example",
                mode = CaptureTargetMode.SYSTEM_UI,
                authorizedPackage = null,
            ),
        )
        assertNull(
            automaticComposePreflightKey(
                serial = "serial",
                packageName = null,
                mode = CaptureTargetMode.FOREGROUND_APP,
                authorizedPackage = null,
            ),
        )
        assertNull(
            automaticComposePreflightKey(
                serial = null,
                packageName = "com.example",
                mode = CaptureTargetMode.FOREGROUND_APP,
                authorizedPackage = null,
            ),
        )
    }

    @Test
    fun `system composables are shown by default when the full Compose tree is available`() {
        assertTrue(ViewDisplayOptions().showSystemComposables)
    }
}
