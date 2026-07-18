import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val appVersion = project.version.toString()
val firefoxProfilerDist = rootProject.layout.projectDirectory.dir("../third_party/firefox-profiler/dist")
val firefoxProfilerAppResources = layout.buildDirectory.dir("generated/firefox-profiler-app-resources")
val prepareFirefoxProfilerAppResources =
    tasks.register<Sync>("prepareFirefoxProfilerAppResources") {
        inputs.file(firefoxProfilerDist.file("index.html"))
        from(firefoxProfilerDist)
        into(firefoxProfilerAppResources.map { resources -> resources.dir("common") })
    }

tasks
    .matching { task -> task.name == "prepareAppResources" }
    .configureEach { dependsOn(prepareFirefoxProfilerAppResources) }

fun macOsPackageVersion(version: String): String {
    val numericComponents = version.split(".")
    val firstPositiveIndex =
        numericComponents.indexOfFirst { component -> component.toIntOrNull()?.let { it > 0 } == true }
    return when (firstPositiveIndex) {
        -1 -> "1"
        0 -> version
        else -> numericComponents.drop(firstPositiveIndex).joinToString(".")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":layout-inspector:presentation"))
    implementation("com.androidperformancestudio:app-desktop:0.1.0-SNAPSHOT")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
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
        jvmArgs("-Dapple.awt.application.name=AndroidPerfermanceStudio")
        jvmArgs("-Dagentperf.version=$appVersion")
        nativeDistributions {
            appResourcesRootDir.set(firefoxProfilerAppResources)
            // The minimized jpackage runtime cannot infer JdkAiHttpTransport's reflective HTTP usage.
            modules("java.net.http")
            modules("java.sql")
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "AndroidPerfermanceStudio"
            packageVersion = appVersion
            windows {
                iconFile.set(project.file("src/main/package/windows/app-icon.ico"))
                shortcut = true
            }
            linux {
                iconFile.set(project.file("src/main/package/linux/app-icon.png"))
                shortcut = true
            }
            macOS {
                iconFile.set(project.file("src/main/package/macos/app-icon.icns"))
                // jpackage requires a positive first component for macOS app images.
                val macVersion = macOsPackageVersion(appVersion)
                packageVersion = macVersion
                packageBuildVersion = macVersion
            }
        }
    }
}
