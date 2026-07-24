plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.agentperf.android.frame"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":layout-inspector:shared-kernel:android-agent-core"))
    implementation("com.androidperformancestudio.frame:frame-agent-protocol:0.1.0-SNAPSHOT")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
