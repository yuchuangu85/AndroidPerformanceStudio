plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.androidperformancestudio.android.startup"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":layout-inspector-agent-core"))
    implementation(project(":layout-inspector-agent-view"))
    implementation(project(":layout-inspector-agent-frame"))
    implementation(project(":layout-inspector-agent-startup-metrics"))
    implementation(libs.androidx.startup)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
