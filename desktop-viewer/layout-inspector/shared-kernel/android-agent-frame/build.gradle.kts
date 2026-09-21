plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.androidperformancestudio.android.frame"
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
    api(project(":layout-inspector-agent-core"))
    implementation(libs.aps.frame.agent.protocol)
    implementation(libs.androidx.metrics.performance)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
