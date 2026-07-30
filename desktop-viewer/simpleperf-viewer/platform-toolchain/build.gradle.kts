plugins {
    `java-library`
}

dependencies {
    api(project(":adb-core"))
    implementation(project(":profile-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
