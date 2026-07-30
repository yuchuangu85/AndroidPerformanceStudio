dependencies {
    api(project(":profile-model"))
    api(project(":profile-analysis"))
    implementation(project(":simpleperf-storage-sqlite"))
    testImplementation(project(":simpleperf-export-adapters"))
    testImplementation(project(":simpleperf-storage-sqlite"))
}

val poc by sourceSets.creating
val sampleGenerator by sourceSets.creating

dependencies {
    add(poc.implementationConfigurationName, project(":simpleperf-application"))
    add(poc.implementationConfigurationName, project(":profile-analysis"))
    add(poc.implementationConfigurationName, project(":profile-model"))
    add(poc.implementationConfigurationName, project(":simpleperf-storage-sqlite"))
    add(poc.implementationConfigurationName, project(":visualization"))
    add(poc.implementationConfigurationName, "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    add(sampleGenerator.implementationConfigurationName, project(":simpleperf-export-adapters"))
    add(sampleGenerator.implementationConfigurationName, project(":profile-analysis"))
    add(sampleGenerator.implementationConfigurationName, project(":profile-model"))
    add(sampleGenerator.implementationConfigurationName, project(":simpleperf-storage-sqlite"))
}

tasks.register<JavaExec>("runP0PerformancePoc") {
    group = "verification"
    description = "Runs the reproducible million-record SQLite and visualization P0 benchmark."
    dependsOn(poc.classesTaskName)
    classpath = poc.runtimeClasspath
    mainClass.set("com.androidperformancestudio.fixtures.P0PerformancePocKt")
    jvmArgs("-Xms256m", "-Xmx1g")
    args(
        rootProject.layout.projectDirectory
            .dir("docs/poc-results")
            .asFile.absolutePath,
    )
}

tasks.register<JavaExec>("runFlameGraphPerformancePoc") {
    group = "verification"
    description = "Runs the reproducible Firefox flame graph parity P0 benchmark."
    dependsOn(poc.classesTaskName)
    classpath = poc.runtimeClasspath
    mainClass.set("com.androidperformancestudio.fixtures.P0PerformancePocKt")
    jvmArgs("-Xms256m", "-Xmx1g")
    args(
        rootProject.layout.projectDirectory
            .dir("docs/poc-results")
            .asFile.absolutePath,
        "firefox-flame-graph",
    )
}

tasks.register<JavaExec>("generateSampleSession") {
    group = "documentation"
    description = "Regenerates the checked-in V0.1 demonstration session package."
    dependsOn(sampleGenerator.classesTaskName)
    classpath = sampleGenerator.runtimeClasspath
    mainClass.set("com.androidperformancestudio.fixtures.SampleSessionGeneratorKt")
    args(
        layout.projectDirectory
            .file("src/main/resources/sessions/golden.apsession.zip")
            .asFile.absolutePath,
    )
}
