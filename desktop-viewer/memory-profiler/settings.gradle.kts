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

rootProject.name = "memory-profiler"

include(
    ":memory-model",
    ":capture-memory",
    ":parser-hprof",
    ":analysis-memory",
    ":memory-storage-sqlite",
    ":memory-export-adapters",
    ":memory-presentation",
    ":memory-app",
)

project(":memory-storage-sqlite").projectDir = file("storage-sqlite")
project(":memory-export-adapters").projectDir = file("export-adapters")
project(":memory-presentation").projectDir = file("presentation")

// Makes standalone `./gradlew check` resolve the shared host toolchain and ADB gateway.
includeBuild("../simpleperf-viewer") {
    name = "memory-profiler-simpleperf-tooling"
}

includeBuild("../ui-components") {
    name = "memory-profiler-ui-components"
}
