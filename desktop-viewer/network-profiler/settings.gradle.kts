pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "network-profiler"

include(
    ":network-model",
    ":network-agent-protocol",
    ":android-agent-network",
    ":network-instrumentation",
    ":capture-network",
    ":parser-har",
    ":analysis-network",
    ":storage-sqlite",
    ":export-adapters",
    ":presentation",
    ":network-app",
)

includeBuild("../simpleperf-viewer") {
    name = "network-profiler-simpleperf-tooling"
}

includeBuild("../ui-components") {
    name = "network-profiler-ui-components"
}
