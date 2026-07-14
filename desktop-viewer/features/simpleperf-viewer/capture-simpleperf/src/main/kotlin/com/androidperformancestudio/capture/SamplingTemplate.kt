package com.androidperformancestudio.capture

enum class SamplingTemplate(
    val displayName: String,
    val description: String,
) {
    APP_CPU_BASIC(
        displayName = "App CPU Basic",
        description = "General-purpose app CPU hotspot sampling.",
    ),
    UI_THREAD_FOCUS(
        displayName = "UI Thread Focus",
        description = "High-frequency sampling for a selected UI thread.",
    ),
    NATIVE_HOTSPOT(
        displayName = "Native Hotspot",
        description = "CPU cycle sampling for native computation hotspots.",
    ),
    LOW_OVERHEAD(
        displayName = "Low Overhead",
        description = "Reduced frequency with frame-pointer call graphs.",
    ),
    SYSTEM_PROCESS(
        displayName = "System Process",
        description = "Conservative sampling for a root-accessible system process.",
    ),
    ;

    fun create(target: SimpleperfTarget): SamplingParameters =
        when (this) {
            APP_CPU_BASIC -> defaults(target)
            UI_THREAD_FOCUS -> defaults(target)
            NATIVE_HOTSPOT ->
                defaults(target).copy(
                    event = "cpu-cycles",
                    rate = SamplingRate.Frequency(NATIVE_FREQUENCY_HERTZ),
                )
            LOW_OVERHEAD ->
                defaults(target).copy(
                    rate = SamplingRate.Frequency(LOW_OVERHEAD_FREQUENCY_HERTZ),
                    callGraph = CallGraphMode.FRAME_POINTER,
                )
            SYSTEM_PROCESS ->
                defaults(target).copy(
                    rate = SamplingRate.Frequency(SYSTEM_FREQUENCY_HERTZ),
                    callGraph = CallGraphMode.FRAME_POINTER,
                )
        }

    private fun defaults(target: SimpleperfTarget): SamplingParameters = SamplingParameters(target = target)

    companion object {
        private const val NATIVE_FREQUENCY_HERTZ = 1000
        private const val LOW_OVERHEAD_FREQUENCY_HERTZ = 100
        private const val SYSTEM_FREQUENCY_HERTZ = 400
    }
}
