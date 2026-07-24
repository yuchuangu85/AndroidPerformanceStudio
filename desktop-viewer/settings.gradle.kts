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

val layoutInspectorModules =
    listOf(
        "shared-kernel:protocol-model",
        "shared-kernel:analysis-engine",
        "shared-kernel:test-fixtures",
        "shared-kernel:android-agent-core",
        "shared-kernel:android-agent-view",
        "shared-kernel:android-agent-frame",
        "shared-kernel:android-agent-startup-metrics",
        "shared-kernel:android-agent-startup",
        "adb-gateway",
        "application",
        "presentation",
        "samples:android-view-app",
    )

layoutInspectorModules.forEach { module ->
    val path = ":layout-inspector:$module"
    include(path)
    project(path).projectDir = file("layout-inspector/${module.replace(':', '/')}")
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
