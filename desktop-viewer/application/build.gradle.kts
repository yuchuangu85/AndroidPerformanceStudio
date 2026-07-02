plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":shared-kernel:protocol-model"))
    api(project(":shared-kernel:analysis-engine"))
    testImplementation(project(":shared-kernel:test-fixtures"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}
