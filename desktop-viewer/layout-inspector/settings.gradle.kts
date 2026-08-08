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

rootProject.name = "layout-inspector"

include(
    ":adb-gateway",
    ":application",
    ":presentation",
    ":protocol-model",
    ":compose-inspection-model",
    ":compose-inspection-host",
    ":analysis-engine",
    ":test-fixtures",
)

project(":adb-gateway").projectDir = file("adb-gateway")
project(":application").projectDir = file("application")
project(":presentation").projectDir = file("presentation")
project(":protocol-model").projectDir = file("shared-kernel/protocol-model")
project(":compose-inspection-model").projectDir = file("shared-kernel/compose-inspection-model")
project(":compose-inspection-host").projectDir = file("compose-inspection-host")
project(":analysis-engine").projectDir = file("shared-kernel/analysis-engine")
project(":test-fixtures").projectDir = file("shared-kernel/test-fixtures")

// Shared composite builds for dependency resolution
includeBuild("../ui-components") {
    name = "layout-inspector-ui-components"
}
includeBuild("../ai-core") {
    name = "layout-inspector-ai-core"
}
includeBuild("../platform-adb") {
    name = "layout-inspector-platform-adb"
}
