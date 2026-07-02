plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.agentperf.android.view"
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
    api(project(":shared-kernel:android-agent-core"))
}
