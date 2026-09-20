pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:9.2.0")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "memory-profiler"

include(
    ":memory-model",
    ":leakcanary-agent-protocol",
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

// The Android bridge is opt-in so desktop-app run/compile does not resolve the Android Gradle
// Plugin or mobile-only dependencies. Enable it with -PincludeLeakCanaryAgent=true.
if (providers.gradleProperty("includeLeakCanaryAgent").orNull == "true") {
    include(":android-agent-leakcanary")
    project(":android-agent-leakcanary").projectDir = file("android-agent-leakcanary")
}

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
