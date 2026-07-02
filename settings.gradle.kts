pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "agent-perf"

include(
    ":shared-kernel:protocol-model",
    ":shared-kernel:analysis-engine",
    ":shared-kernel:test-fixtures",
    ":shared-kernel:android-agent-core",
    ":shared-kernel:android-agent-view",
    ":shared-kernel:android-agent-startup",
    ":desktop-viewer:adb-gateway",
    ":desktop-viewer:application",
    ":desktop-viewer:desktop-app",
    ":samples:android-view-app",
)
