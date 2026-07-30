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

rootProject.name = "android-performance-studio"

include(
    ":app-desktop",
    ":adb-core",
    ":simpleperf-presentation",
    ":simpleperf-application",
    ":platform-toolchain",
    ":device-adb",
    ":capture-simpleperf",
    ":parser-simpleperf-proto",
    ":profile-model",
    ":profile-analysis",
    ":simpleperf-storage-sqlite",
    ":analysis-rules",
    ":visualization",
    ":simpleperf-export-adapters",
    ":simpleperf-test-fixtures",
)

project(":adb-core").projectDir = file("../platform-adb/adb-core")
project(":simpleperf-presentation").projectDir = file("presentation")
project(":simpleperf-application").projectDir = file("application")
project(":simpleperf-storage-sqlite").projectDir = file("storage-sqlite")
project(":simpleperf-export-adapters").projectDir = file("export-adapters")
project(":simpleperf-test-fixtures").projectDir = file("test-fixtures")

includeBuild("../ui-components") {
    name = "simpleperf-ui-components"
}
