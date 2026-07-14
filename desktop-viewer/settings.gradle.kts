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

rootProject.name = "AndroidPerfermanceStudio"

include(":desktop-app")

val layoutInspectorModules =
    listOf(
        "shared-kernel:protocol-model",
        "shared-kernel:analysis-engine",
        "shared-kernel:test-fixtures",
        "shared-kernel:android-agent-core",
        "shared-kernel:android-agent-view",
        "shared-kernel:android-agent-startup",
        "adb-gateway",
        "application",
        "presentation",
        "samples:android-view-app",
    )

layoutInspectorModules.forEach { module ->
    val path = ":layout-inspector:$module"
    include(path)
    project(path).projectDir = file("layout-inspector/${module.replace(':', '/')}")
}

// CPU profiling is intentionally kept as an isolated composite build. Its plugin versions,
// dependencies, packages, tests, and native application lifecycle do not leak into the layout viewer.
includeBuild("simpleperf-viewer") {
    name = "simpleperf-viewer"
}
