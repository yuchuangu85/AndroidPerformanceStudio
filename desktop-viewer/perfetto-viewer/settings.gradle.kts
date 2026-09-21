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
