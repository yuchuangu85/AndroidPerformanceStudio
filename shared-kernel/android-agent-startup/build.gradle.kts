plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.agentperf.android.startup"
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
    api(project(":shared-kernel:android-agent-core"))
    implementation(libs.androidx.startup)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
