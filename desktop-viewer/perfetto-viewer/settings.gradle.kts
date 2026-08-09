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
    ":perfetto-ui-server",
    ":perfetto-model",
    ":perfetto-analysis",
    ":perfetto-storage",
    ":perfetto-export",
)

// Resolves neutral profiler contracts, ADB, and host tooling in standalone builds.
includeBuild("../platform-core") {
    name = "perfetto-viewer-platform-core"
}

includeBuild("../platform-perfetto") {
    name = "perfetto-viewer-platform-perfetto"
}

includeBuild("../ui-components") {
    name = "perfetto-viewer-ui-components"
}
