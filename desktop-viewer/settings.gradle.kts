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
