package dev.agentperf.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppDestination {
    HOME,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
}

internal fun AppDestination.shouldMaximizeWindow(): Boolean =
    when (this) {
        AppDestination.HOME -> false
        AppDestination.LAYOUT_INSPECTOR,
        AppDestination.SIMPLEPERF,
        -> true
    }

class AppNavigator(
    initialDestination: AppDestination = AppDestination.HOME,
) {
    var destination by mutableStateOf(initialDestination)
        private set

    fun open(destination: AppDestination) {
        this.destination = destination
    }
}
