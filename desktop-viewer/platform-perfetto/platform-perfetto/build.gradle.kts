import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
        explicitApi()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.aps.profiler.contracts)
    implementation(libs.aps.host.toolchain)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

sourceSets {
    main {
        resources.srcDir(rootProject.projectDir)
        resources.include("trace-processor-manifest.json")
    }
}

tasks.test {
    useJUnitPlatform()
}
