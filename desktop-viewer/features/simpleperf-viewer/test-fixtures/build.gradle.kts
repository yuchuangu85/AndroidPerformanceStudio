dependencies {
    api(project(":profile-model"))
    testImplementation(project(":export-adapters"))
    testImplementation(project(":storage-sqlite"))
}

val poc by sourceSets.creating
val sampleGenerator by sourceSets.creating

dependencies {
    add(poc.implementationConfigurationName, project(":profile-model"))
    add(poc.implementationConfigurationName, project(":storage-sqlite"))
    add(poc.implementationConfigurationName, project(":visualization"))
    add(sampleGenerator.implementationConfigurationName, project(":export-adapters"))
    add(sampleGenerator.implementationConfigurationName, project(":profile-model"))
    add(sampleGenerator.implementationConfigurationName, project(":storage-sqlite"))
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
