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

includeBuild("../platform-core") { name = "battery-profiler-platform-core" }

includeBuild("../ui-components") {
    name = "battery-profiler-ui-components"
}
