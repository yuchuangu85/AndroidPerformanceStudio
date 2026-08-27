package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ComposeInspectionVisibilityTest {
    @Test
    fun `foreground capture automatically prepares the full Compose tree without a toolbar switch`() {
        assertEquals(
            "serial:FOREGROUND_APP",
            automaticComposePreflightKey(
                serial = "serial",
                mode = CaptureTargetMode.FOREGROUND_APP,
                fullComposeEnabled = false,
                hasAuthorization = false,
            ),
        )
    }

    @Test
    fun `automatic full Compose preparation never replaces system UI or an active native-plus-compose session`() {
        assertNull(
            automaticComposePreflightKey(
                serial = "serial",
                mode = CaptureTargetMode.SYSTEM_UI,
                fullComposeEnabled = false,
                hasAuthorization = false,
            ),
        )
        assertNull(
            automaticComposePreflightKey(
                serial = "serial",
                mode = CaptureTargetMode.FOREGROUND_APP,
                fullComposeEnabled = true,
                hasAuthorization = true,
            ),
        )
    }

    @Test
    fun `system composables are shown by default when the full Compose tree is available`() {
        assertFalse(DEFAULT_HIDE_SYSTEM_COMPOSABLES)
    }
}
