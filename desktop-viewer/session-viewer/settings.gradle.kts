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

rootProject.name = "session-viewer"

include(
    ":session-model",
    ":session-storage",
    ":session-app",
)

includeBuild("../ui-components") {
    name = "session-viewer-ui-components"
}
