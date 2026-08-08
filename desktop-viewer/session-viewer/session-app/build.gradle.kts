plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose.resources {
    packageOfResClass = "com.androidperformancestudio.session.app.generated.resources"
}

dependencies {
    implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
    api(project(":session-model"))
    implementation(project(":session-storage"))
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.components:components-resources:1.11.1")
    testImplementation(compose.desktop.currentOs)
}
