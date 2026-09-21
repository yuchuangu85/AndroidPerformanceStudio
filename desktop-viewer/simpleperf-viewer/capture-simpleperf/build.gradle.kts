dependencies {
    implementation(project(":profile-model"))
    implementation(libs.aps.adb.core)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.core)
}
