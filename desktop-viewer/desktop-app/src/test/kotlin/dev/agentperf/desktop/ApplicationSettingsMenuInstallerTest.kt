package dev.agentperf.desktop

import java.awt.desktop.PreferencesHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApplicationSettingsMenuInstallerTest {
    @Test
    fun `supported preferences action forwards settings request and unregisters`() {
        var handler: PreferencesHandler? = null
        var requests = 0
        val installer = ApplicationSettingsMenuInstaller(
            supported = { true },
            setHandler = { handler = it },
        )

        val registration = installer.install { requests += 1 }
        handler!!.handlePreferences(null)
        registration.close()

        assertEquals(1, requests)
        assertNull(handler)
    }

    @Test
    fun `unsupported preferences action is a no op`() {
        var registrations = 0
        val installer = ApplicationSettingsMenuInstaller(
            supported = { false },
            setHandler = { registrations += 1 },
        )

        installer.install {}.close()

        assertEquals(0, registrations)
    }

    @Test
    fun `desktop registration failures remain a no op`() {
        val installer = ApplicationSettingsMenuInstaller(
            supported = { true },
            setHandler = { error("registration failed") },
        )

        installer.install {}.close()
    }
}
