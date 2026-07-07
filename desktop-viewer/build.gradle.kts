plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val defaultAppVersion = "0.1.6"
val appVersion = providers.gradleProperty("appVersion").getOrElse(defaultAppVersion)

allprojects {
    group = "dev.agentperf"
    version = appVersion
}
