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
    ":desktop-ui",
    ":presentation",
    ":application",
    ":platform-toolchain",
    ":device-adb",
    ":capture-simpleperf",
    ":parser-simpleperf-proto",
    ":profile-model",
    ":profile-analysis",
    ":storage-sqlite",
    ":analysis-rules",
    ":visualization",
    ":export-adapters",
    ":test-fixtures",
)
