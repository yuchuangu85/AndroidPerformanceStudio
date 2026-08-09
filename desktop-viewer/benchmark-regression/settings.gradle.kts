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
    ":benchmark-storage-sqlite",
    ":benchmark-export-adapters",
    ":benchmark-cli",
    ":benchmark-presentation",
    ":benchmark-app",
)

project(":benchmark-storage-sqlite").projectDir = file("storage-sqlite")
project(":benchmark-export-adapters").projectDir = file("export-adapters")
project(":benchmark-presentation").projectDir = file("presentation")


includeBuild("../ui-components") {
    name = "benchmark-regression-ui-components"
}
