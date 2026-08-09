plugins {
    id("com.google.protobuf")
}

dependencies {
    implementation(project(":profile-model"))
    implementation("com.androidperformancestudio:host-toolchain:0.1.0-SNAPSHOT")
    implementation("com.google.protobuf:protobuf-java:4.35.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
}
