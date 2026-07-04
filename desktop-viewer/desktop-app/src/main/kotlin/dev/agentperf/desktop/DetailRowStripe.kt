package dev.agentperf.desktop

internal object DetailRowStripe {
    fun usesDeepBackground(index: Int): Boolean = index % 2 == 0
}
