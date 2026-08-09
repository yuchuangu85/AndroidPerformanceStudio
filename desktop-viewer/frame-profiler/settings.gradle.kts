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

rootProject.name = "frame-profiler"

include(
    ":frame-model",
    ":frame-agent-protocol",
    ":capture-frame",
    ":parser-frame",
    ":analysis-frame",
    ":frame-storage-sqlite",
    ":frame-export-adapters",
    ":frame-presentation",
    ":frame-app",
)

project(":frame-storage-sqlite").projectDir = file("storage-sqlite")
project(":frame-export-adapters").projectDir = file("export-adapters")
project(":frame-presentation").projectDir = file("presentation")

// Resolves the shared ADB and host process tooling for standalone Frame Profiler builds.
includeBuild("../platform-core") {
    name = "frame-profiler-platform-core"
}

includeBuild("../ui-components") {
    name = "frame-profiler-ui-components"
}
