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

rootProject.name = "ai-platform"

include(":ai-platform-model", ":ai-platform-registry", ":ai-platform-storage", ":ai-platform-providers", ":ai-platform-runtime")

include(":ai-platform-presentation", ":ai-platform-app", ":ai-platform-script-runtime", ":ai-platform-provider-codex", ":ai-platform-provider-claude")

includeBuild("../ui-components")
includeBuild("../ai-core")
