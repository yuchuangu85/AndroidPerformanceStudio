plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.winscope.app.generated.resources"
}

dependencies {
    implementation(project(":winscope-core"))
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:platform-perfetto:0.1.0-SNAPSHOT")
    implementation("com.androidperformancestudio:adb-core:0.1.0-SNAPSHOT")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
}
