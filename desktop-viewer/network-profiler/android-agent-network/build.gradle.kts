plugins { id("com.android.library") }
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
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
