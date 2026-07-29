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

rootProject.name = "perfetto-viewer"

include(
    ":perfetto-app",
    ":perfetto-presentation",
    ":perfetto-capture",
    ":perfetto-trace-processor",
    ":perfetto-ui-server",
    ":perfetto-model",
    ":perfetto-analysis",
    ":perfetto-storage",
    ":perfetto-export",
)

// Resolves shared ADB, host-toolchain, and profile model dependencies in standalone builds.
includeBuild("../simpleperf-viewer") {
    name = "perfetto-viewer-simpleperf-tooling"
}

includeBuild("../ui-components") {
    name = "perfetto-viewer-ui-components"
}
