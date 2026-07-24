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
    ":storage-sqlite",
    ":export-adapters",
    ":presentation",
    ":memory-app",
)

// Makes standalone `./gradlew check` resolve the shared host toolchain and ADB gateway.
includeBuild("../simpleperf-viewer") {
    name = "memory-profiler-simpleperf-tooling"
}
