import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

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
    implementation(project(":adb-gateway"))
    implementation(project(":application"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    testImplementation(project(":shared-kernel:test-fixtures"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.agentperf.desktop.MainKt"
        jvmArgs("-Dapple.awt.application.name=AgentPerf Inspector")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AgentPerf Inspector"
            packageVersion = "0.1.1"
            macOS {
                // jpackage requires a positive first component for macOS app images.
                packageVersion = "1.1" // 0.1.1 compatibility
                packageBuildVersion = "1.1"
            }
        }
    }
}
