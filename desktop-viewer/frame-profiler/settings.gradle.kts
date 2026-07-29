pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "frame-profiler"

include(
    ":frame-model",
    ":frame-agent-protocol",
    ":capture-frame",
    ":parser-frame",
    ":analysis-frame",
    ":storage-sqlite",
    ":export-adapters",
    ":presentation",
    ":frame-app",
)

// Resolves the shared ADB and host process tooling for standalone Frame Profiler builds.
includeBuild("../simpleperf-viewer") {
    name = "frame-profiler-simpleperf-tooling"
}

includeBuild("../ui-components") {
    name = "frame-profiler-ui-components"
}
