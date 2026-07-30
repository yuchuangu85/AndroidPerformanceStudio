pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "battery-profiler"

include(
    ":battery-app",
)

includeBuild("../simpleperf-viewer") { name = "battery-profiler-simpleperf-tooling" }

includeBuild("../ui-components") {
    name = "battery-profiler-ui-components"
}
