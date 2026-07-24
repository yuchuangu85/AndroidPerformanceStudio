pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "battery-profiler"

include(
    ":battery-model",
    ":parser-batterystats",
    ":analysis-battery",
    ":capture-battery",
    ":historian-adapter",
    ":storage-sqlite",
    ":export-adapters",
    ":presentation",
    ":battery-app",
)

includeBuild("../simpleperf-viewer") { name = "battery-profiler-simpleperf-tooling" }
