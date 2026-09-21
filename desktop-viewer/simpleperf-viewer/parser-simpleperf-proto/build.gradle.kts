plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    implementation(project(":profile-model"))
    implementation(libs.aps.host.toolchain)
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.core)
}

val protobufVersion = libs.versions.protobufVersion.get()

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
}
