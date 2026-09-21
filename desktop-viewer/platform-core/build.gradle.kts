plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.androidperformancestudio"
    version = "0.1.0-SNAPSHOT"
}
