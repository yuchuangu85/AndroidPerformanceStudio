import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":desktop-viewer:application"))
    implementation(project(":shared-kernel:test-fixtures"))
    implementation(compose.desktop.currentOs)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.agentperf.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AgentPerfDesktopViewer"
            packageVersion = "0.1.0"
        }
    }
}
