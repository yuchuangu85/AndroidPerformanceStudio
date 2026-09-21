plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.androidperformancestudio.network.agent"
    compileSdk = 37
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":network-agent-protocol"))
    implementation(project(":network-model"))
    implementation(libs.androidx.startup)
    implementation(libs.okhttp)
}
