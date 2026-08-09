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
    ":network-storage-sqlite",
    ":network-export-adapters",
    ":network-presentation",
    ":network-app",
)

project(":network-storage-sqlite").projectDir = file("storage-sqlite")
project(":network-export-adapters").projectDir = file("export-adapters")
project(":network-presentation").projectDir = file("presentation")

includeBuild("../platform-core") {
    name = "network-profiler-platform-core"
}

includeBuild("../ui-components") {
    name = "network-profiler-ui-components"
}
