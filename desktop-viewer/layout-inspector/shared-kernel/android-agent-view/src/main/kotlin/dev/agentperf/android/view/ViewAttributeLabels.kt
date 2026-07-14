package dev.agentperf.android.view

internal object ViewAttributeLabels {
    fun visibility(value: Int): String = when (value) {
        0 -> "VISIBLE"
        4 -> "INVISIBLE"
        8 -> "GONE"
        else -> "UNKNOWN($value)"
    }

    fun layerType(value: Int): String = when (value) {
        0 -> "NONE"
        1 -> "SOFTWARE"
        2 -> "HARDWARE"
        else -> "UNKNOWN($value)"
    }
}
