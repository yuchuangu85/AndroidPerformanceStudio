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

// Makes standalone `./gradlew check` resolve neutral shared platform dependencies.
includeBuild("../platform-core") {
    name = "memory-profiler-platform-core"
}

includeBuild("../platform-perfetto") {
    name = "memory-profiler-platform-perfetto"
}

includeBuild("../ui-components") {
    name = "memory-profiler-ui-components"
}
