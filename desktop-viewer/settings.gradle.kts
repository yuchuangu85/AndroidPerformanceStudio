pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidPerfermanceStudio"

include(":desktop-app")

// Shared public Compose controls used by every application and presentation module.
includeBuild("ui-components") {
    name = "ui-components"
}

// Shared provider-neutral AI transport and structured response infrastructure.
includeBuild("ai-core") {
    name = "ai-core"
}

// Shared import contracts and filesystem source validation.
includeBuild("source-workspace") {
    name = "source-workspace"
}
// Neutral profiler contracts and shared platform infrastructure live in one composite build.
includeBuild("platform-core") {
    name = "platform-core"
}

// Shared verified Perfetto capture configuration and Trace Processor analysis.
includeBuild("platform-perfetto") {
    name = "platform-perfetto"
}

// Layout Inspector is kept as an isolated composite build.
includeBuild("layout-inspector") {
    name = "layout-inspector"
}

// Android Agent modules (device-side libraries) are kept as standard includes
// because they use the Android Gradle Plugin, not Compose Desktop.
val layoutInspectorAgentModules =
    mapOf(
        ":layout-inspector-agent-core" to "layout-inspector/shared-kernel/android-agent-core",
        ":layout-inspector-agent-view" to "layout-inspector/shared-kernel/android-agent-view",
        ":layout-inspector-agent-frame" to "layout-inspector/shared-kernel/android-agent-frame",
        ":layout-inspector-agent-startup" to "layout-inspector/shared-kernel/android-agent-startup",
        ":layout-inspector-agent-startup-metrics" to "layout-inspector/shared-kernel/android-agent-startup-metrics",
    )

layoutInspectorAgentModules.forEach { (path, directory) ->
    include(path)
    project(path).projectDir = file(directory)
}

include(":layout-inspector-sample-app")
project(":layout-inspector-sample-app").projectDir = file("layout-inspector/samples/android-view-app")



// CPU profiling is intentionally kept as an isolated composite build.
includeBuild("simpleperf-viewer") {
    name = "simpleperf-viewer"
}

// Perfetto trace analysis is kept as an isolated composite build.
includeBuild("perfetto-viewer") {
    name = "perfetto-viewer"
}

// Native Android 15+ window-system trace capture and analysis.
includeBuild("winscope") {
    name = "winscope"
}

// Java/Kotlin heap capture and HPROF analysis is kept as an isolated composite build.
includeBuild("memory-profiler") {
    name = "memory-profiler"
}

// Frame timing and jank analysis is kept as an isolated composite build.
includeBuild("frame-profiler") {
    name = "frame-profiler"
}

// Application startup timing and launch analysis is kept as an isolated composite build.
includeBuild("startup-profiler") {
    name = "startup-profiler"
}

// Battery resource attribution and energy estimation is kept as an isolated composite build.
includeBuild("battery-profiler") {
    name = "battery-profiler"
}

// HTTP request capture and HAR analysis is kept as an isolated composite build.
includeBuild("network-profiler") {
    name = "network-profiler"
}

// Android GPU Inspector tooling and artifact management is kept isolated.
includeBuild("gpu-inspector-integration") {
    name = "gpu-inspector-integration"
}

// AndroidX Benchmark import, comparison, and CI reporting is kept isolated.
includeBuild("benchmark-regression") {
    name = "benchmark-regression"
}
