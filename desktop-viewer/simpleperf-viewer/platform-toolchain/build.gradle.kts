plugins {
    `java-library`
}

dependencies {
    api("com.androidperformancestudio:adb-core:0.1.0-SNAPSHOT")
    implementation(project(":profile-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
