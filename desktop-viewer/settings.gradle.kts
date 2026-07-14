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

include(
    ":shared-kernel:protocol-model",
    ":shared-kernel:analysis-engine",
    ":shared-kernel:test-fixtures",
    ":shared-kernel:android-agent-core",
    ":shared-kernel:android-agent-view",
    ":shared-kernel:android-agent-startup",
    ":adb-gateway",
    ":application",
    ":desktop-app",
    ":samples:android-view-app",
)

// CPU profiling is intentionally kept as an isolated composite build. Its plugin versions,
// dependencies, packages, tests, and native application lifecycle do not leak into the layout viewer.
includeBuild("features/simpleperf-viewer") {
    name = "simpleperf-viewer"
}
