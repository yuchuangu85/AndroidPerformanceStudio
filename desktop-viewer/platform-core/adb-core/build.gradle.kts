import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

group = "com.androidperformancestudio"
version = "0.1.0-SNAPSHOT"
layout.buildDirectory = rootProject.layout.buildDirectory.dir("adb-core")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":host-toolchain"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("smokeBundledAdb") {
    group = "verification"
    description = "Probes bundled ADB in an SDK-free environment; optionally captures memory data from an authorized device."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.androidperformancestudio.platform.adb.BundledAdbSmokeProbe")
    val bundle = providers.gradleProperty("adbSmokeBundle").getOrElse(rootProject.file("../../build/adb-tools").absolutePath)
    val captureDevice = providers.gradleProperty("adbSmokeDevice").getOrElse("false")
    args(bundle, captureDevice)
    val preserved = listOf("HOME", "USERPROFILE", "SYSTEMROOT", "SystemRoot", "TEMP", "TMP", "TMPDIR")
    setEnvironment(System.getenv().filterKeys { it in preserved } + ("PATH" to ""))
    val emptyHome = layout.buildDirectory.dir("adb-smoke-empty-home").get().asFile
    systemProperty("user.home", emptyHome.absolutePath)
    doFirst { emptyHome.mkdirs() }
}
