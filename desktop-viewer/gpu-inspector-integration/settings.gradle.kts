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

rootProject.name = "gpu-inspector-integration"

include(
    ":gpu-integration-model",
    ":agi-toolchain",
    ":agi-artifact-index",
    ":gpu-integration-presentation",
    ":gpu-integration-app",
)

project(":gpu-integration-presentation").projectDir = file("presentation")

includeBuild("../platform-core") {
    name = "gpu-inspector-platform-core"
}

includeBuild("../ui-components") {
    name = "gpu-inspector-integration-ui-components"
}
