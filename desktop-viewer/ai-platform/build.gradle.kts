plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

allprojects {
    group = "com.androidperformancestudio"
    version = "0.1.0-SNAPSHOT"
}

tasks.register("checkAll") {
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register("format") {
    group = "formatting"
    dependsOn(subprojects.map { "${it.path}:ktlintFormat" })
}
