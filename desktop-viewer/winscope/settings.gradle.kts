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

rootProject.name = "winscope"

include(":winscope-core", ":winscope-app", ":winscope-test-fixtures")

includeBuild("../platform-core") { name = "winscope-platform-core" }
includeBuild("../platform-perfetto") { name = "winscope-platform-perfetto" }
includeBuild("../ui-components") { name = "winscope-ui-components" }
