pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "benchmark-regression"

include(
    ":benchmark-model",
    ":parser-benchmark-json",
    ":analysis-regression",
    ":storage-sqlite",
    ":export-adapters",
    ":benchmark-cli",
    ":presentation",
    ":benchmark-app",
)
