pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "gpu-inspector-integration"

include(
    ":gpu-integration-model",
    ":agi-toolchain",
    ":agi-artifact-index",
    ":presentation",
    ":gpu-integration-app",
)

includeBuild("../simpleperf-viewer") {
    name = "gpu-inspector-simpleperf-tooling"
}

includeBuild("../ui-components") {
    name = "gpu-inspector-integration-ui-components"
}
