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
    ":startup-storage-sqlite",
    ":startup-export-adapters",
    ":startup-presentation",
    ":startup-app",
)

project(":startup-storage-sqlite").projectDir = file("storage-sqlite")
project(":startup-export-adapters").projectDir = file("export-adapters")
project(":startup-presentation").projectDir = file("presentation")

includeBuild("../platform-core") {
    name = "startup-profiler-platform-core"
}

includeBuild("../ui-components") {
    name = "startup-profiler-ui-components"
}
