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

val layoutInspectorModules =
    mapOf(
        ":layout-inspector:shared-kernel:protocol-model" to "shared-kernel/protocol-model",
        ":layout-inspector:shared-kernel:analysis-engine" to "shared-kernel/analysis-engine",
        ":layout-inspector:shared-kernel:layout-test-fixtures" to "shared-kernel/test-fixtures",
        ":layout-inspector:shared-kernel:android-agent-core" to "shared-kernel/android-agent-core",
        ":layout-inspector:shared-kernel:android-agent-view" to "shared-kernel/android-agent-view",
        ":layout-inspector:shared-kernel:android-agent-frame" to "shared-kernel/android-agent-frame",
        ":layout-inspector:shared-kernel:android-agent-startup-metrics" to "shared-kernel/android-agent-startup-metrics",
        ":layout-inspector:shared-kernel:android-agent-startup" to "shared-kernel/android-agent-startup",
        ":layout-inspector:adb-gateway" to "adb-gateway",
        ":layout-inspector:layout-application" to "application",
        ":layout-inspector:layout-presentation" to "presentation",
        ":layout-inspector:samples:android-view-app" to "samples/android-view-app",
    )

layoutInspectorModules.forEach { (path, directory) ->
    include(path)
    project(path).projectDir = file("layout-inspector/$directory")
}

// CPU profiling is intentionally kept as an isolated composite build.
includeBuild("simpleperf-viewer") {
    name = "simpleperf-viewer"
}

// Perfetto trace analysis is kept as an isolated composite build.
includeBuild("perfetto-viewer") {
    name = "perfetto-viewer"
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
