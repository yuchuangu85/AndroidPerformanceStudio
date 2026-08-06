package com.androidperformancestudio.network.instrumentation

/**
 * Result of instrumenting an app with the Network Profiler agent.
 *
 * The agent intercepts HTTP calls through OkHttp's EventListener API.
 * This class records how many OkHttpClient.Builder call sites were
 * successfully transformed vs skipped.
 */
public data class NetworkInstrumentationCoverage(
    /** Number of OkHttpClient.Builder call sites that were instrumented. */
    val transformedCallSites: Int,
    /** Number of OkHttpClient.Builder call sites that could not be instrumented. */
    val skippedCallSites: Int,
    /** Version of OkHttp detected in the app's dependencies. */
    val okhttpVersion: String?,
    /** Warnings about instrumentation issues. */
    val warnings: List<String>,
) {
    public val complete: Boolean get() = transformedCallSites > 0 && skippedCallSites == 0
}

/**
 * Guidance and documentation for the Network Profiler instrumentation.
 *
 * ## HTTP stack coverage matrix
 *
 * The Network Profiler agent uses OkHttp's [okhttp3.EventListener] to
 * capture network events with frame-accurate timing. This covers:
 *
 * | HTTP stack          | Covered? | Notes                                              |
 * |---------------------|----------|-----------------------------------------------------|
 * | OkHttp (direct)     | ✓ FULL   | Via EventListener on instrumented OkHttpClient      |
 * | Retrofit (OkHttp)   | ✓ FULL   | Retrofit delegates to OkHttp by default             |
 * | Ktor (OkHttp engine)| ✓ FULL   | When using the OkHttp engine (ktor-client-okhttp)   |
 * | Ktor (CIO engine)   | ✗ NONE   | Coroutine-based engine; no EventListener            |
 * | Ktor (Android)      | ✗ NONE   | Android engine; no EventListener                    |
 * | HttpURLConnection   | ✗ NONE   | Platform API; needs bytecode instrumentation        |
 * | Cronet              | ✗ NONE   | Google's network stack; needs separate hooking       |
 * | Volley (HurlStack)  | ✗ NONE   | Wraps HttpURLConnection                              |
 * | Volley (OkHttpStack)| ✓ FULL   | Wraps OkHttp; covered if the OkHttpClient is instrumented |
 * | WebView             | ✗ NONE   | Chromium networking; not instrumentable             |
 * | native sockets      | ✗ NONE   | Pure C/C++ networking via JNI                       |
 *
 * ### Interpreting partial coverage
 *
 * When [NetworkInstrumentationCoverage.skippedCallSites] > 0, some
 * OkHttpClient builders in the app were not transformed. This means
 * some OkHttp calls will NOT be captured. The `warnings` list provides
 * details about which call sites were skipped and why.
 *
 * For apps that use multiple HTTP stacks, the session's
 * [NetworkCoverage.unsupportedStacks] field lists stacks that are
 * known to be used but not covered by the agent (e.g. "Cronet",
 * "URLConnection", "WebView").
 */
public object NetworkInstrumentationPolicy {
    public fun explicitFactoryGuidance(): String =
        "Install NetworkProfiler.eventListenerFactory(existingFactory) on every debug OkHttpClient.Builder. " +
            "Adding only the dependency cannot observe arbitrary OkHttp clients."
}
