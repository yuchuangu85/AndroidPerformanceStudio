package com.androidperformancestudio.network.instrumentation

public data class NetworkInstrumentationCoverage(
    val transformedCallSites: Int,
    val skippedCallSites: Int,
    val okhttpVersion: String?,
    val warnings: List<String>,
) {
    public val complete: Boolean get() = transformedCallSites > 0 && skippedCallSites == 0
}

public object NetworkInstrumentationPolicy {
    public fun explicitFactoryGuidance(): String =
        "Install NetworkProfiler.eventListenerFactory(existingFactory) on every debug OkHttpClient.Builder. " +
            "Adding only the dependency cannot observe arbitrary OkHttp clients."
}
