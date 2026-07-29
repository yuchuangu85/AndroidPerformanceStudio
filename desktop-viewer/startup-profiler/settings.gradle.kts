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

rootProject.name = "startup-profiler"

include(
    ":startup-model",
    ":startup-agent-protocol",
    ":capture-startup",
    ":parser-startup",
    ":analysis-startup",
    ":storage-sqlite",
    ":export-adapters",
    ":presentation",
    ":startup-app",
)

includeBuild("../simpleperf-viewer") {
    name = "startup-profiler-simpleperf-tooling"
}

includeBuild("../ui-components") {
    name = "startup-profiler-ui-components"
}
