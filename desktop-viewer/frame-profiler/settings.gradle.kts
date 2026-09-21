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

rootProject.name = "frame-profiler"

include(
    ":frame-model",
    ":frame-agent-protocol",
    ":capture-frame",
    ":parser-frame",
    ":analysis-frame",
    ":frame-storage-sqlite",
    ":frame-export-adapters",
    ":frame-presentation",
    ":frame-app",
)

project(":frame-storage-sqlite").projectDir = file("storage-sqlite")
project(":frame-export-adapters").projectDir = file("export-adapters")
project(":frame-presentation").projectDir = file("presentation")

// Resolves the shared ADB and host process tooling for standalone Frame Profiler builds.
includeBuild("../platform-core") {
    name = "frame-profiler-platform-core"
}

includeBuild("../platform-perfetto") {
    name = "frame-profiler-platform-perfetto"
}

includeBuild("../ui-components") {
    name = "frame-profiler-ui-components"
}
