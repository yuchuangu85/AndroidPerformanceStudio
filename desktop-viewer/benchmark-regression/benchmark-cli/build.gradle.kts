plugins { application }
dependencies {
    implementation(project(":benchmark-model"))
    implementation(project(":parser-benchmark-json"))
    implementation(project(":analysis-regression"))
    implementation(project(":export-adapters"))
}
application { mainClass.set("com.androidperformancestudio.benchmark.cli.MainKt") }
