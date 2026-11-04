import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

// ---------------------------------------------------------------------------------------------
// Self-built Compose inspection agent artifacts.
//
// The two vendored upstream snapshots stay byte-for-byte unmodified:
//   third_party/aosp-ui-inspector    platform/tools/base@b126135... ui-inspector subtree
//   third_party/androidx-inspection  platform/frameworks/support@4da03982... inspection/inspection
//
// The only APS adaptation is staged into build/agent-src: the AOSP snapshot compiles against
// Android Studio's shaded protobuf, while APS builds the same protos with the public runtime.
//
// Packaging mirrors the upstream Bazel targets:
//   lib_ui_inspector_service.jar  = dex(service classes)
//   lib_ui_inspector_payload.jar  = dex(payload + common + protos + protobuf runtime +
//                                       androidx.inspection)
//   view-inspector.jar            = dex(jarjar(view + common + kotlin-stdlib + coroutines))
//                                   + META-INF/services resources
// The payload is loaded by a DexClassLoader whose parent is the service class loader, which is why
// it carries the shared runtime and the View inspector does not.
// ---------------------------------------------------------------------------------------------

val shadedProtobuf = "com.android.tools.idea.protobuf"
val publicProtobuf = "com.google.protobuf"

val repoRoot: File = layout.projectDirectory.dir("../../..").asFile
val aospInspector: File = File(repoRoot, "third_party/aosp-ui-inspector")
val androidxInspection: File = File(repoRoot, "third_party/androidx-inspection")

val aospPatchFiles: List<File> =
    File(repoRoot, "third_party/aosp-ui-inspector-patches").listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".patch") }
        .sortedBy { it.name }
        .onEach { require(it.isFile) { "Missing APS patch: " + it } }

val stagedAgentSourcesDir = layout.buildDirectory.dir("agent-src")
val stagedAospDir = layout.buildDirectory.dir("agent-src/aosp")
val stagedAndroidxDir = layout.buildDirectory.dir("agent-src/androidx")

val stagedJavaSourceDirs: List<File> =
    listOf(
        "agent/service/src/main/java",
        "agent/payload/src/main/java",
        "agent/inspectors/view/src/main/java",
        "common/src/main/java",
    ).map { stagedAospDir.get().asFile.resolve(it) } +
        stagedAndroidxDir.get().asFile.resolve("src/main/java")

fun androidSdkDir(): File =
    (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.let(::File)
        ?: error("Set ANDROID_HOME or ANDROID_SDK_ROOT; the Compose agent build needs an Android SDK")

fun numericVersion(name: String): Double = name.substringAfterLast('-').toDoubleOrNull() ?: 0.0

// Desktop packaging configures this module but does not build the Android agents. Resolve SDK
// inputs only when an agent task actually consumes them, not while configuring unrelated builds.
val androidJar = providers.provider {
    File(androidSdkDir(), "platforms").listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "android.jar").isFile }
        .maxByOrNull { numericVersion(it.name) }
        ?.let { File(it, "android.jar") }
        ?: error("No Android platform with android.jar under " + androidSdkDir() + "/platforms")
}

val d8Tool = providers.provider {
    File(androidSdkDir(), "build-tools").listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "d8").isFile }
        .maxByOrNull { numericVersion(it.name) }
        ?.let { File(it, "d8") }
        ?: error("No d8 under " + androidSdkDir() + "/build-tools")
}

fun runTool(command: List<String>, directory: File? = null) {
    val builder = ProcessBuilder(command).redirectErrorStream(true)
    if (directory != null) builder.directory(directory)
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    check(exit == 0) { "Command failed with exit " + exit + ":\n  " + command.joinToString(" ") + "\n" + output }
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

val stageAgentSources =
    tasks.register("stageAgentSources") {
        description = "Mirrors the vendored agent sources, applies the APS patches and retargets protobuf."
        val aosp = stagedAospDir
        val androidx = stagedAndroidxDir
        val snapshot = aospInspector
        val androidxSnapshot = androidxInspection
        val patches = aospPatchFiles
        inputs.dir(snapshot)
        inputs.dir(androidxSnapshot)
        inputs.files(patches)
        inputs.property("shadedProtobuf", shadedProtobuf)
        inputs.property("publicProtobuf", publicProtobuf)
        outputs.dir(stagedAgentSourcesDir)
        doLast {
            val aospTarget = aosp.get().asFile
            aospTarget.deleteRecursively()
            aospTarget.mkdirs()
            // Only the subtrees the bundle is built from; host/ and documentation stay out.
            for (subtree in listOf("agent", "common")) {
                File(snapshot, subtree).copyRecursively(File(aospTarget, subtree), overwrite = true)
            }
            patches.forEach { patch ->
                runTool(listOf("patch", "-p1", "-s", "-i", patch.absolutePath), aospTarget)
            }

            val androidxTarget = androidx.get().asFile
            androidxTarget.deleteRecursively()
            androidxTarget.mkdirs()
            File(androidxSnapshot, "src/main/java")
                .copyRecursively(File(androidxTarget, "src/main/java"), overwrite = true)

            // The AOSP snapshot compiles against Android Studio's shaded protobuf; APS builds the
            // same protos with the public runtime.
            for (root in listOf(aospTarget, androidxTarget)) {
                root.walkTopDown()
                    .filter { it.isFile && (it.name.endsWith(".java") || it.name.endsWith(".kt")) }
                    .forEach { source ->
                        val text = source.readText(Charsets.UTF_8)
                        val rewritten = text.replace(shadedProtobuf, publicProtobuf)
                        if (rewritten != text) source.writeText(rewritten, Charsets.UTF_8)
                    }
            }
        }
    }

stagedJavaSourceDirs.forEach { sourceSets["main"].java.srcDir(it) }
sourceSets["main"].proto.srcDir(File(aospInspector, "common/proto"))

kotlin.sourceSets.named("main") { stagedJavaSourceDirs.forEach { kotlin.srcDir(it) } }

tasks.withType<KotlinCompile>().configureEach { dependsOn(stageAgentSources) }
tasks.named("compileJava") { dependsOn(stageAgentSources) }

protobuf {
    // The protobuf plugin already registers the java builtin for a JVM project; only the pinned
    // protoc artifact has to be selected so codegen matches third_party/.../common/proto.
    protoc { artifact = libs.protobuf.protoc.get().toString() }
}

val payloadLibraries = configurations.create("payloadLibraries")
val viewInspectorLibraries = configurations.create("viewInspectorLibraries")
val jarjarTool = configurations.create("jarjarTool")

dependencies {
    // android.jar and the two annotation-only APIs are compile-time inputs. The annotations are
    // CLASS retention and never loaded, so they are not bundled into the injected jars.
    compileOnly(files(androidJar))
    compileOnly("androidx.annotation:annotation:1.8.1")
    compileOnly("org.jspecify:jspecify:1.0.0")

    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.coroutines.core)

    add(payloadLibraries.name, libs.protobuf.java)
    add(viewInspectorLibraries.name, libs.kotlinx.coroutines.core)
    add(viewInspectorLibraries.name, "org.jetbrains.kotlin:kotlin-stdlib:" + libs.versions.kotlin.get())
    add(jarjarTool.name, "org.pantsbuild:jarjar:1.7.1")
}

// ---------------------------------------------------------------------------------------------
// Packaging
// ---------------------------------------------------------------------------------------------

val serviceClassPrefixes = listOf("com/android/tools/ui/inspector/service/")
val payloadClassPrefixes =
    listOf(
        "com/android/tools/ui/inspector/payload/",
        "com/android/tools/ui/inspector/common/",
        "com/android/tools/ui/inspector/protocol/",
        "com/android/tools/ui/inspector/view/inspector/protocol/",
        "androidx/inspection/",
    )
val viewInspectorClassPrefixes =
    listOf(
        "com/android/tools/ui/inspector/inspectors/view/",
        "com/android/tools/agent/appinspection/",
        "com/android/tools/ui/inspector/common/",
    )

fun writeJar(
    destination: File,
    classDirs: Iterable<File>,
    prefixes: List<String>,
    libraries: Iterable<File>,
) {
    destination.parentFile.mkdirs()
    ZipOutputStream(destination.outputStream().buffered()).use { zip ->
        val written = mutableSetOf<String>()
        for (dir in classDirs) {
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                val relative = file.relativeTo(dir).invariantSeparatorsPath
                if (prefixes.any { relative.startsWith(it) } && written.add(relative)) {
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        for (library in libraries) {
            if (!library.isFile || library.extension != "jar") continue
            ZipFile(library).use { source ->
                for (entry in source.entries()) {
                    val name = entry.name
                    if (entry.isDirectory || !written.add(name)) continue
                    if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                    if (name.endsWith("module-info.class")) continue
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))
                    ) {
                        continue
                    }
                    zip.putNextEntry(ZipEntry(name))
                    source.getInputStream(entry).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}

val agentClassDirs = sourceSets["main"].output.classesDirs
val mergedServiceJar = layout.buildDirectory.file("agent-merge/service.jar")
val mergedPayloadJar = layout.buildDirectory.file("agent-merge/payload.jar")
val mergedViewInspectorJar = layout.buildDirectory.file("agent-merge/view-inspector-unjarjared.jar")
val jarjaredViewInspectorJar = layout.buildDirectory.file("agent-merge/view-inspector-jarjared.jar")

val mergeServiceJar =
    tasks.register("mergeServiceJar") {
        inputs.files(agentClassDirs)
        inputs.property("prefixes", serviceClassPrefixes)
        outputs.file(mergedServiceJar)
        doLast { writeJar(mergedServiceJar.get().asFile, agentClassDirs.files, serviceClassPrefixes, emptyList()) }
    }

val mergePayloadJar =
    tasks.register("mergePayloadJar") {
        inputs.files(agentClassDirs)
        inputs.files(payloadLibraries)
        inputs.property("prefixes", payloadClassPrefixes)
        outputs.file(mergedPayloadJar)
        doLast {
            writeJar(
                mergedPayloadJar.get().asFile,
                agentClassDirs.files,
                payloadClassPrefixes,
                payloadLibraries.files.toList(),
            )
        }
    }

val mergeViewInspectorJar =
    tasks.register("mergeViewInspectorJar") {
        inputs.files(agentClassDirs)
        inputs.files(viewInspectorLibraries)
        inputs.property("prefixes", viewInspectorClassPrefixes)
        outputs.file(mergedViewInspectorJar)
        doLast {
            writeJar(
                mergedViewInspectorJar.get().asFile,
                agentClassDirs.files,
                viewInspectorClassPrefixes,
                viewInspectorLibraries.files.toList(),
            )
        }
    }

val jarjarViewInspector =
    tasks.register("jarjarViewInspector") {
        description = "Relocates kotlin/kotlinx.coroutines so the injected inspector cannot clash."
        val rules = File(aospInspector, "agent/inspectors/view/jarjar_rules.txt")
        val source = mergedViewInspectorJar
        val destination = jarjaredViewInspectorJar
        inputs.file(source)
        inputs.file(rules)
        inputs.files(jarjarTool)
        outputs.file(destination)
        doLast {
            val target = destination.get().asFile
            target.parentFile.mkdirs()
            target.delete()
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            runTool(
                listOf(
                    java,
                    "-cp",
                    jarjarTool.files.joinToString(File.pathSeparator) { it.absolutePath },
                    "org.pantsbuild.jarjar.Main",
                    "process",
                    rules.absolutePath,
                    source.get().asFile.absolutePath,
                    target.absolutePath,
                ),
            )
        }
    }

fun registerDexTask(name: String, jar: Any, outputDir: Any) =
    tasks.register(name) {
        inputs.file(jar)
        inputs.file(androidJar)
        outputs.dir(outputDir)
        doLast {
            val directory = (outputDir as Provider<*>).get() as org.gradle.api.file.Directory
            val dir = directory.asFile
            dir.deleteRecursively()
            dir.mkdirs()
            val inputJar = (jar as Provider<*>).get() as org.gradle.api.file.RegularFile
            runTool(
                listOf(
                    d8Tool.get().absolutePath,
                    "--min-api",
                    "29",
                    "--lib",
                    androidJar.get().absolutePath,
                    "--output",
                    dir.absolutePath,
                    inputJar.asFile.absolutePath,
                ),
            )
        }
    }

val dexServiceDir = layout.buildDirectory.dir("agent-dex/service")
val dexPayloadDir = layout.buildDirectory.dir("agent-dex/payload")
val dexViewInspectorDir = layout.buildDirectory.dir("agent-dex/view-inspector")

val dexService = registerDexTask("dexService", mergedServiceJar, dexServiceDir)
val dexPayload = registerDexTask("dexPayload", mergedPayloadJar, dexPayloadDir)
val dexViewInspector = registerDexTask("dexViewInspector", jarjaredViewInspectorJar, dexViewInspectorDir)

fun registerPackageTask(
    name: String,
    dexDir: Any,
    resourcesRoot: File?,
    output: Any,
) =
    tasks.register(name) {
        inputs.dir(dexDir)
        if (resourcesRoot != null) inputs.dir(resourcesRoot)
        outputs.file(output)
        doLast {
            val directory = (dexDir as Provider<*>).get() as org.gradle.api.file.Directory
            val classesDex = File(directory.asFile, "classes.dex")
            check(classesDex.isFile) { "d8 did not produce classes.dex in " + directory.asFile }
            val destination = ((output as Provider<*>).get() as org.gradle.api.file.RegularFile).asFile
            destination.parentFile.mkdirs()
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                classesDex.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                resourcesRoot
                    ?.walkTopDown()
                    ?.filter { it.isFile }
                    ?.forEach { file ->
                        val relative = file.relativeTo(resourcesRoot).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(relative))
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
        }
    }

val bundledServiceJar = layout.buildDirectory.file("compose-agent-bundle/lib_ui_inspector_service.jar")
val bundledPayloadJar = layout.buildDirectory.file("compose-agent-bundle/lib_ui_inspector_payload.jar")
val bundledViewInspectorJar = layout.buildDirectory.file("compose-agent-bundle/view-inspector.jar")

val packageServiceJar = registerPackageTask("packageServiceJar", dexServiceDir, null, bundledServiceJar)
val packagePayloadJar = registerPackageTask("packagePayloadJar", dexPayloadDir, null, bundledPayloadJar)
val packageViewInspectorJar =
    registerPackageTask(
        "packageViewInspectorJar",
        dexViewInspectorDir,
        File(aospInspector, "agent/inspectors/view/src/main/resources"),
        bundledViewInspectorJar,
    )

// The intermediate jars are plain file providers, so the producer/consumer edges have to be
// declared explicitly.
jarjarViewInspector.configure { dependsOn(mergeViewInspectorJar) }
dexService.configure { dependsOn(mergeServiceJar) }
dexPayload.configure { dependsOn(mergePayloadJar) }
dexViewInspector.configure { dependsOn(jarjarViewInspector) }
packageServiceJar.configure { dependsOn(dexService) }
packagePayloadJar.configure { dependsOn(dexPayload) }
packageViewInspectorJar.configure { dependsOn(dexViewInspector) }

// ---------------------------------------------------------------------------------------------
// Native JVMTI agent (NDK + CMake instead of Bazel)
// ---------------------------------------------------------------------------------------------

val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")

val ndkDir = providers.provider {
    File(androidSdkDir(), "ndk").listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "build/cmake/android.toolchain.cmake").isFile }
        .maxByOrNull { numericVersion(it.name) }
        ?: error("No Android NDK under " + androidSdkDir() + "/ndk")
}

val cmakeBinDir = providers.provider {
    File(androidSdkDir(), "cmake").listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "bin/cmake").isFile && File(it, "bin/ninja").isFile }
        .maxByOrNull { numericVersion(it.name) }
        ?.let { File(it, "bin") }
        ?: error("No SDK CMake with ninja under " + androidSdkDir() + "/cmake")
}

val llvmStrip = providers.provider {
    File(ndkDir.get(), "toolchains/llvm/prebuilt").listFiles().orEmpty()
        .map { File(it, "bin/llvm-strip") }
        .firstOrNull { it.isFile }
        ?: error("No llvm-strip under " + ndkDir.get() + "/toolchains/llvm/prebuilt")
}

val stagedNativeSourcesDir = layout.buildDirectory.dir("agent-native-src")
val nativeAgentDir = layout.buildDirectory.dir("agent-native")
val cppSourceDir = File(layout.projectDirectory.asFile, "src/main/cpp")

val stageNativeSources =
    tasks.register("stageNativeSources") {
        description = "Stages the patched ui-inspector agent native sources and the JDK jvmti.h."
        dependsOn(stageAgentSources)
        val destination = stagedNativeSourcesDir
        val agentSources = stagedAospDir.get().asFile.resolve("agent/native")
        val jvmtiHeader = File(System.getProperty("java.home"), "include/jvmti.h")
        inputs.dir(agentSources)
        inputs.file(jvmtiHeader)
        outputs.dir(destination)
        doLast {
            val outputDir = destination.get().asFile
            outputDir.deleteRecursively()
            val agentDir = File(outputDir, "ui-inspector")
            agentDir.mkdirs()
            agentSources.listFiles().orEmpty()
                .filter { it.isFile && (it.extension == "cc" || it.extension == "h") }
                .forEach { source -> source.copyTo(File(agentDir, source.name), overwrite = true) }
            val jvmtiDir = File(outputDir, "jvmti")
            jvmtiDir.mkdirs()
            jvmtiHeader.copyTo(File(jvmtiDir, "jvmti.h"), overwrite = true)
        }
    }

val buildAgentNative =
    tasks.register("buildAgentNative") {
        description = "Builds lib_ui_inspector_agent.so for every supported ABI with the NDK."
        dependsOn(stageNativeSources)
        val staged = stagedNativeSourcesDir
        val output = nativeAgentDir
        val transportRoot = File(repoRoot, "third_party/aosp-transport-native")
        val slicerRoot = File(repoRoot, "third_party/aosp-slicer/slicer")
        inputs.dir(staged)
        inputs.dir(transportRoot)
        inputs.dir(slicerRoot)
        inputs.file(cppSourceDir.resolve("CMakeLists.txt"))
        inputs.property("abis", supportedAbis)
        outputs.dir(output)
        doLast {
            val stagedDir = staged.get().asFile
            val outputDir = output.get().asFile
            outputDir.deleteRecursively()
            val cmake = File(cmakeBinDir.get(), "cmake").absolutePath
            val ninja = File(cmakeBinDir.get(), "ninja").absolutePath
            for (abi in supportedAbis) {
                val buildDir = File(outputDir, "build-" + abi)
                val abiDir = File(outputDir, abi)
                buildDir.mkdirs()
                abiDir.mkdirs()
                runTool(
                    listOf(
                        cmake,
                        "-S", cppSourceDir.absolutePath,
                        "-B", buildDir.absolutePath,
                        "-G", "Ninja",
                        "-DCMAKE_MAKE_PROGRAM=" + ninja,
                        "-DCMAKE_TOOLCHAIN_FILE=" +
                            File(ndkDir.get(), "build/cmake/android.toolchain.cmake").absolutePath,
                        "-DANDROID_ABI=" + abi,
                        "-DANDROID_PLATFORM=android-29",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DAGENT_SRC_DIR=" + File(stagedDir, "ui-inspector").absolutePath,
                        "-DTRANSPORT_ROOT=" + transportRoot.absolutePath,
                        "-DSLICER_ROOT=" + slicerRoot.absolutePath,
                        "-DJVMTI_INCLUDE_DIR=" + File(stagedDir, "jvmti").absolutePath,
                    ),
                )
                runTool(listOf(cmake, "--build", buildDir.absolutePath, "--target", "ui_inspector_agent", "-j", "8"))
                val produced = File(buildDir, "libui_inspector_agent.so")
                check(produced.isFile && produced.length() > 0) { "CMake did not produce " + produced }
                val target = File(abiDir, "lib_ui_inspector_agent.so")
                produced.copyTo(target, overwrite = true)
                // The upstream Bazel target ships the stripped binary.
                runTool(listOf(llvmStrip.get().absolutePath, "--strip-unneeded", target.absolutePath))
            }
        }
    }

val composeAgentBundle =
    tasks.register("composeAgentBundle") {
        description = "Builds the Windows/macOS-hosted Compose inspection agent jars into build/compose-agent-bundle."
        dependsOn(packageServiceJar, packagePayloadJar, packageViewInspectorJar, buildAgentNative)
        val bundle = layout.buildDirectory.dir("compose-agent-bundle")
        outputs.dir(bundle)
        // The package tasks write their jars straight into the bundle directory. Without declaring
        // them as inputs Gradle can consider this task up to date after a jar has already been
        // rebuilt in place, which leaves a manifest whose hashes no longer match the artifacts.
        inputs.files(bundledServiceJar, bundledPayloadJar, bundledViewInspectorJar)
        inputs.files(
            supportedAbis.map { abi ->
                nativeAgentDir.map { it.file(abi + "/lib_ui_inspector_agent.so") }
            },
        )
        doLast {
            val directory = bundle.get().asFile
            val service = File(directory, "lib_ui_inspector_service.jar")
            val payload = File(directory, "lib_ui_inspector_payload.jar")
            val view = File(directory, "view-inspector.jar")
            listOf(service, payload, view).forEach { jar ->
                check(jar.isFile && jar.length() > 0) { "Missing agent artifact: " + jar }
            }
            val agents =
                supportedAbis.map { abi ->
                    val source = File(nativeAgentDir.get().asFile, abi + "/lib_ui_inspector_agent.so")
                    check(source.isFile && source.length() > 0) { "Missing native agent: " + source }
                    val target = File(directory, "agent/" + abi + "/lib_ui_inspector_agent.so")
                    target.parentFile.mkdirs()
                    source.copyTo(target, overwrite = true)
                    abi to target
                }
            File(directory, "manifest.properties").writeText(
                buildString {
                    appendLine("# Built by :layout-inspector:compose-agent-build (composeAgentBundle).")
                    appendLine("# Runtime code verifies every hash in this file before injection.")
                    agents.forEach { (abi, file) -> appendLine("agent." + abi + ".sha256=" + sha256(file)) }
                    appendLine("service.sha256=" + sha256(service))
                    appendLine("payload.sha256=" + sha256(payload))
                    appendLine("view.sha256=" + sha256(view))
                },
                Charsets.UTF_8,
            )
            File(directory, "PROVENANCE.md").writeText(
                buildString {
                    appendLine("# Compose inspection agent jars")
                    appendLine()
                    appendLine("- AOSP tools/base commit: `b1261356012800c0a93d18f03b060024e8162c2f`")
                    appendLine("- AndroidX frameworks/support commit: `4da03982d44f5a668172df67c920114ceca6dc2b`")
                    appendLine("- AOSP tools/dexter (slicer) commit: `d992a222ec28b56efa29f9104db060379298049c`")
                    appendLine("- Built by: `:layout-inspector:compose-agent-build`")
                    appendLine()
                    appendLine("The sources were mirrored from the vendored snapshots and the APS patches in")
                    appendLine("`third_party/aosp-ui-inspector-patches/` were applied before compiling, including")
                    appendLine("the per-session token handshake. These artifacts are dexed for Android and are")
                    appendLine("loaded by the injected agent, not by the desktop host.")
                },
                Charsets.UTF_8,
            )
        }
    }

/**
 * Asserts that the produced bundle is exactly what ComposeAgentBundle expects: the four artifacts
 * per ABI, the manifest keys the runtime verifies, matching checksums and dexed jars.
 */
val verifyComposeAgentBundle =
    tasks.register("verifyComposeAgentBundle") {
        description = "Verifies the bundle layout, manifest keys, checksums and dexed payloads."
        dependsOn(composeAgentBundle)
        val bundle = layout.buildDirectory.dir("compose-agent-bundle")
        inputs.dir(bundle)
        doLast {
            val directory = bundle.get().asFile
            val manifest =
                Properties().apply {
                    File(directory, "manifest.properties").inputStream().use { load(it) }
                }
            val expected = linkedMapOf<String, File>()
            supportedAbis.forEach { abi ->
                expected["agent." + abi + ".sha256"] =
                    File(directory, "agent/" + abi + "/lib_ui_inspector_agent.so")
            }
            expected["service.sha256"] = File(directory, "lib_ui_inspector_service.jar")
            expected["payload.sha256"] = File(directory, "lib_ui_inspector_payload.jar")
            expected["view.sha256"] = File(directory, "view-inspector.jar")
            for ((key, file) in expected) {
                val recorded = manifest.getProperty(key) ?: error("manifest.properties is missing " + key)
                check(file.isFile && file.length() > 0) { "Missing bundle artifact: " + file }
                check(sha256(file) == recorded) { "Checksum mismatch for " + file }
            }
            val jars = listOf("lib_ui_inspector_service.jar", "lib_ui_inspector_payload.jar", "view-inspector.jar")
            for (name in jars) {
                val dexed = ZipFile(File(directory, name)).use { zip -> zip.getEntry("classes.dex") != null }
                check(dexed) { name + " does not contain classes.dex" }
            }
        }
    }

tasks.named("composeAgentBundle") { finalizedBy(verifyComposeAgentBundle) }
