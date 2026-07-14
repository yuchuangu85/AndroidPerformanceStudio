plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.agentperf.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.agentperf.sample"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity)
    debugImplementation(project(":layout-inspector:shared-kernel:android-agent-startup"))
    debugImplementation(project(":layout-inspector:shared-kernel:android-agent-view"))
}
