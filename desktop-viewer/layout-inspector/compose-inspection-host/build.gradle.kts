import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":compose-inspection-model"))
    implementation(project(":adb-gateway"))
    implementation(libs.aps.profiler.contracts)
    implementation(libs.protobuf.java)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val protobufVersion = libs.versions.protobufVersion.get()

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
}

tasks.test { useJUnitPlatform() }
