import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget



plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

val appVersion = project.version.toString()
val firefoxProfilerDist = rootProject.layout.projectDirectory.dir("../third_party/firefox-profiler/dist")
val userDocumentationEnglish = rootProject.layout.projectDirectory.dir("../docs-user")
val userDocumentationChinese = rootProject.layout.projectDirectory.dir("../docs-user-zh")
val firefoxProfilerAppResources = layout.buildDirectory.dir("generated/firefox-profiler-app-resources")
val prepareFirefoxProfilerAppResources =
    tasks.register<Sync>("prepareFirefoxProfilerAppResources") {
        inputs.file(firefoxProfilerDist.file("index.html"))
        inputs.dir(userDocumentationEnglish)
        inputs.dir(userDocumentationChinese)
        from(firefoxProfilerDist)
        from(userDocumentationEnglish) {
            into("docs-user")
        }
        from(userDocumentationChinese) {
            into("docs-user-zh")
        }
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

val targetArch = project.findProperty("target.arch")?.toString()

dependencies {
    implementation(project(":layout-inspector:presentation"))
    implementation("com.androidperformancestudio:app-desktop:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:perfetto-app:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio.memory:memory-app:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio.frame:frame-app:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio.startup:startup-app:0.1.0-SNAPSHOT")
    when (targetArch) {
        "x64" -> implementation(compose.desktop.macos_x64)
        "arm64" -> implementation(compose.desktop.macos_arm64)
        else -> implementation(compose.desktop.currentOs)
    }
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
        // Bound HPROF parsing so oversized imports fail with a recoverable Java OOME
        // instead of letting the operating system terminate the desktop process.
        jvmArgs("-Xmx4g")

        if (targetArch == "x64") {
            javaHome = "${System.getProperty("user.home")}/Downloads/zulu21.50.19-ca-jdk21.0.11-macosx_x64/Contents/Home"
        }

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
