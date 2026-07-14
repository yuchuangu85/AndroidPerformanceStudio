dependencies {
    implementation(project(":application"))
    implementation(project(":profile-model"))
    implementation(project(":platform-toolchain"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

val poc by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[poc.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get())

tasks.register<JavaExec>("runAdbSelfCheck") {
    group = "verification"
    description = "Locates adb and verifies it through the structured process runner."
    dependsOn(poc.classesTaskName)
    classpath = poc.runtimeClasspath
    mainClass.set("com.androidperformancestudio.adb.AdbSelfCheckKt")
}
