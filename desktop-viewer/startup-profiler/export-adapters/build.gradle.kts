import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":startup-model"))
    implementation(project(":analysis-startup"))
    implementation("com.androidperformancestudio:profiler-contracts:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

val sourceSets = extensions.getByType<SourceSetContainer>()

tasks.register<JavaExec>("writeElectronInteropFixture") {
    group = "verification"
    description = "Writes a Kotlin-created Startup Profiler JSON fixture for Electron interop tests."
    classpath = sourceSets.named("test").get().runtimeClasspath
    mainClass.set("com.androidperformancestudio.startup.export.ElectronStartupJsonFixtureWriter")
    args(
        providers.gradleProperty("fixturePath").orNull
            ?: layout.buildDirectory
                .file("fixtures/kotlin-startup-report.json")
                .get()
                .asFile.absolutePath,
    )
}
