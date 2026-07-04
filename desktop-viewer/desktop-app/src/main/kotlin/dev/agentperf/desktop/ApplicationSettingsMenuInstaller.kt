package dev.agentperf.desktop

import java.awt.Desktop
import java.awt.desktop.PreferencesHandler

internal class ApplicationSettingsMenuInstaller(
    private val supported: () -> Boolean,
    private val setHandler: (PreferencesHandler?) -> Unit,
) {
    fun install(onOpenSettings: () -> Unit): AutoCloseable {
        if (!runCatching(supported).getOrDefault(false)) {
            return emptyRegistration()
        }
        return runCatching {
            setHandler(PreferencesHandler { onOpenSettings() })
            AutoCloseable {
                runCatching { setHandler(null) }
            }
        }.getOrElse {
            emptyRegistration()
        }
    }

    companion object {
        fun desktop(): ApplicationSettingsMenuInstaller {
            if (!Desktop.isDesktopSupported()) {
                return unsupported()
            }
            val desktop = runCatching { Desktop.getDesktop() }.getOrNull()
                ?: return unsupported()
            return ApplicationSettingsMenuInstaller(
                supported = {
                    desktop.isSupported(Desktop.Action.APP_PREFERENCES)
                },
                setHandler = desktop::setPreferencesHandler,
            )
        }

        private fun unsupported(): ApplicationSettingsMenuInstaller =
            ApplicationSettingsMenuInstaller(
                supported = { false },
                setHandler = {},
            )

        private fun emptyRegistration(): AutoCloseable = AutoCloseable {}
    }
}
