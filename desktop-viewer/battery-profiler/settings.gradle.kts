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
    ":battery-storage-sqlite",
    ":battery-export-adapters",
    ":battery-presentation",
    ":battery-app",
)

project(":battery-storage-sqlite").projectDir = file("storage-sqlite")
project(":battery-export-adapters").projectDir = file("export-adapters")
project(":battery-presentation").projectDir = file("presentation")

includeBuild("../simpleperf-viewer") { name = "battery-profiler-simpleperf-tooling" }

includeBuild("../ui-components") {
    name = "battery-profiler-ui-components"
}
