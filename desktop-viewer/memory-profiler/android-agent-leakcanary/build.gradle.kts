plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.androidperformancestudio.memory.leak.agent"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":leakcanary-agent-protocol"))
}

tasks.register<Copy>("bundleLeakCanaryAgent") {
    dependsOn("assembleDebug", ":leakcanary-agent-protocol:jar")
    from(layout.buildDirectory.file("outputs/aar/android-agent-leakcanary-debug.aar"))
    from(rootProject.project(":leakcanary-agent-protocol").layout.buildDirectory.file("libs/leakcanary-agent-protocol-${project.version}.jar"))
    into(rootProject.layout.buildDirectory.dir("leakcanary-agent-bundle"))
}

// AAR packaging remains available in offline desktop environments where the AGP lint tool is not cached.
tasks.matching { it.name == "extractDebugAnnotations" }.configureEach {
    enabled = false
}

tasks.register("ensureDebugTypedefs") {
    val typedefs = layout.buildDirectory.file("intermediates/annotations_typedef_file/debug/extractDebugAnnotations/typedefs.txt")
    outputs.file(typedefs)
    doLast {
        val file = typedefs.get().asFile
        file.parentFile.mkdirs()
        if (!file.exists()) file.writeText("")
    }
}
tasks.matching { it.name == "syncDebugLibJars" }.configureEach {
    dependsOn("ensureDebugTypedefs")
}
