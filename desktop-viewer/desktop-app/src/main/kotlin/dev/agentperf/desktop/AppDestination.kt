package dev.agentperf.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppDestination {
    HOME,
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
    PERFETTO,
    MEMORY_PROFILER,
    FRAME_PROFILER,
    STARTUP_PROFILER,
    BATTERY_PROFILER,
}

internal fun AppDestination.shouldMaximizeWindow(): Boolean =
    when (this) {
        AppDestination.HOME -> false
        else -> true
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
