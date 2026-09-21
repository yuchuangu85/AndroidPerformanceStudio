pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Prefer the Alibaba Cloud mirror when Google's Maven endpoint is unavailable.
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
        }
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Prefer the Alibaba Cloud mirror when Google's Maven endpoint is unavailable.
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
        }
        google()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "network-profiler"

include(
    ":network-model",
    ":network-agent-protocol",
    ":network-instrumentation",
    ":capture-network",
    ":parser-har",
    ":analysis-network",
    ":network-storage-sqlite",
    ":network-export-adapters",
    ":network-presentation",
    ":network-app",
)

// The Android capture agent requires AGP and is not needed for desktop-only analysis/import work.
// Enable it with -PincludeNetworkAgent=true when building the device-side agent.
if (providers.gradleProperty("includeNetworkAgent").orNull == "true") {
    include(":android-agent-network")
}

project(":network-storage-sqlite").projectDir = file("storage-sqlite")
project(":network-export-adapters").projectDir = file("export-adapters")
project(":network-presentation").projectDir = file("presentation")

includeBuild("../platform-core") {
    name = "network-profiler-platform-core"
}

includeBuild("../ui-components") {
    name = "network-profiler-ui-components"
}
