package com.androidperformancestudio.platform.perfetto

import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.model.ErrorCategory
import com.androidperformancestudio.model.StudioError
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.CpuArchitecture
import com.androidperformancestudio.platform.toolchain.HostOperatingSystem
import com.androidperformancestudio.platform.toolchain.HostPlatform
import com.androidperformancestudio.platform.toolchain.HostPlatformDetector
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.JvmHostProcessRunner
import com.androidperformancestudio.platform.toolchain.SystemHostPlatformDetector
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class TraceProcessorManifest(
    public val checksums: Map<HostPlatform, String>,
) {
    init {
        require(checksums.values.all { SHA_256.matches(it) }) { "trace processor manifest contains an invalid checksum" }
    }

    public companion object {
        public fun pinnedV59_1(): TraceProcessorManifest =
            TraceProcessorManifest(loadPinnedManifest())

        private fun loadPinnedManifest(): Map<HostPlatform, String> {
            val stream = checkNotNull(TraceProcessorManifest::class.java.getResourceAsStream("/trace-processor-manifest.json")) {
                "Pinned Trace Processor manifest resource is missing"
            }
            val root = stream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(root.getValue("version").jsonPrimitive.content == TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION) {
                "Pinned Trace Processor manifest version does not match the query schema"
            }
            return root.getValue("artifacts").jsonObject.map { (host, value) ->
                parseHost(host) to value.jsonObject.getValue("sha256").jsonPrimitive.content
            }.toMap()
        }

        private fun parseHost(value: String): HostPlatform =
            when (value) {
                "macos-x64" -> HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.X64)
                "macos-arm64" -> HostPlatform(HostOperatingSystem.MACOS, CpuArchitecture.ARM64)
                "linux-x64" -> HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.X64)
                "linux-arm64" -> HostPlatform(HostOperatingSystem.LINUX, CpuArchitecture.ARM64)
                "windows-x64" -> HostPlatform(HostOperatingSystem.WINDOWS, CpuArchitecture.X64)
                else -> error("Unsupported host in Trace Processor manifest: $value")
            }

        private val SHA_256: Regex = Regex("[0-9a-f]{64}")
    }
}

public class TraceProcessorToolResolver(
    private val platformDetector: HostPlatformDetector = SystemHostPlatformDetector(),
    private val manifest: TraceProcessorManifest = TraceProcessorManifest.pinnedV59_1(),
    private val applicationResourcesPath: Path? =
        System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of),
    private val installedToolsPath: Path =
        Path.of(
            System.getProperty("user.home"),
            ".android-performance-studio",
            "tools",
            "perfetto",
            TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
        ),
    private val configuredOverride: Path? =
        System.getProperty(TRACE_PROCESSOR_OVERRIDE_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: System.getenv(TRACE_PROCESSOR_OVERRIDE_ENVIRONMENT)?.takeIf(String::isNotBlank)?.let(Path::of),
    private val processRunner: HostProcessRunner = JvmHostProcessRunner(),
) {
    public suspend fun resolve(): StudioResult<TraceProcessorTool> {
        val platform =
            when (val detected = platformDetector.detect()) {
                is StudioResult.Success -> detected.value
                is StudioResult.Failure -> return detected
            }
        configuredOverride?.let { return resolveOverride(it) }
        val expected =
            manifest.checksums[platform]
                ?: return failure(
                    ErrorCategory.UNSUPPORTED_PLATFORM,
                    "TRACE_PROCESSOR_HOST_UNSUPPORTED",
                    "The pinned Trace Processor is not available for ${platform.resourceDirectory}",
                )
        val binaryName = if (platform.operatingSystem == HostOperatingSystem.WINDOWS) WINDOWS_BINARY else UNIX_BINARY

        val packaged = applicationResourcesPath?.resolve("perfetto-tools")?.resolve(binaryName)
        // The packaged copy may exist with the correct bytes but a missing executable bit (a known
        // packaging defect where the Compose plugin's resource copy strips POSIX modes). When that
        // happens we do not fail outright; we fall through to the installed copy, and if that is
        // missing too we self-heal by copying the good packaged bytes into a user-writable cache.
        val packagedVerdict = if (packaged != null && Files.exists(packaged)) verifyPinned(packaged, expected) else null
        when (packagedVerdict) {
            is BinaryVerification.Valid -> return StudioResult.Success(packagedVerdict.tool)
            is BinaryVerification.ChecksumMismatch ->
                return failure(
                    ErrorCategory.DATA_VALIDATION,
                    "TRACE_PROCESSOR_CHECKSUM_MISMATCH",
                    "Trace Processor checksum mismatch for $packaged",
                )
            else -> Unit // null (absent), NotExecutable (right bytes, bit lost), or NotRegular
        }

        val installed = installedToolsPath.resolve(binaryName)
        val installedVerdict = if (Files.exists(installed)) verifyPinned(installed, expected) else null
        when (installedVerdict) {
            is BinaryVerification.Valid -> return StudioResult.Success(installedVerdict.tool)
            is BinaryVerification.ChecksumMismatch -> {
                // Only surface the installed checksum mismatch if we have no good packaged bytes to
                // heal from. When the packaged copy is the right binary that lost its bit, self-heal
                // from it instead of failing on the corrupt installed copy.
                if (packagedVerdict !is BinaryVerification.NotExecutable) {
                    return failure(
                        ErrorCategory.DATA_VALIDATION,
                        "TRACE_PROCESSOR_CHECKSUM_MISMATCH",
                        "Trace Processor checksum mismatch for $installed",
                    )
                }
            }
            else -> Unit
        }

        // The packaged copy is the right binary but lost its executable bit: recover out-of-the-box
        // by copying it into the user-writable cache and setting the executable bit on the copy.
        if (packagedVerdict is BinaryVerification.NotExecutable) {
            return selfHeal(packaged!!, binaryName)
        }

        // No good source to heal from and no usable installed copy.
        if (installedVerdict is BinaryVerification.NotExecutable) {
            return failure(
                ErrorCategory.CONFIGURATION,
                "TRACE_PROCESSOR_NOT_EXECUTABLE",
                "Trace Processor is not executable: $installed. " +
                    "Recover by running scripts/install-trace-processor.sh or configuring " +
                    "$TRACE_PROCESSOR_OVERRIDE_ENVIRONMENT explicitly.",
            )
        }
        return failure(
            ErrorCategory.CONFIGURATION,
            "TRACE_PROCESSOR_NOT_FOUND",
            "Pinned Trace Processor ${TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION} was not packaged or installed. " +
                "Run scripts/install-trace-processor.sh or configure $TRACE_PROCESSOR_OVERRIDE_ENVIRONMENT explicitly.",
        )
    }

    private fun selfHeal(
        source: Path,
        binaryName: String,
    ): StudioResult<TraceProcessorTool> {
        val target = installedToolsPath.resolve(binaryName)
        return runCatching {
            Files.createDirectories(target.parent)
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            if (!Files.isExecutable(target) && !target.toFile().setExecutable(true, false)) {
                error("Unable to set the executable bit on $target")
            }
            TraceProcessorTool(target, TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION, ArtifactFileEvidence.sha256(target).value)
        }.fold(
            onSuccess = { StudioResult.Success(it) },
            onFailure = {
                failure(
                    ErrorCategory.CONFIGURATION,
                    "TRACE_PROCESSOR_NOT_EXECUTABLE",
                    "Trace Processor is not executable: $source. " +
                        "Recover by running scripts/install-trace-processor.sh or configuring " +
                        "$TRACE_PROCESSOR_OVERRIDE_ENVIRONMENT explicitly.",
                )
            },
        )
    }

    private fun verifyPinned(
        path: Path,
        expectedSha256: String,
    ): BinaryVerification {
        if (!Files.isRegularFile(path)) return BinaryVerification.NotRegular
        val actual = ArtifactFileEvidence.sha256(path).value
        // A wrong checksum is surfaced as a mismatch even when the executable bit is also missing:
        // the fallback only applies to the right binary that lost its bit, not to unexpected bytes.
        if (actual != expectedSha256) return BinaryVerification.ChecksumMismatch(path, actual)
        if (!Files.isExecutable(path)) return BinaryVerification.NotExecutable(path)
        return BinaryVerification.Valid(TraceProcessorTool(path, TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION, actual))
    }

    private suspend fun verifiedSignedMacResource(
        path: Path,
        upstreamSha256: String,
        actualSha256: String,
    ): StudioResult<TraceProcessorTool> =
        try {
            currentCoroutineContext().ensureActive()
            val binary = path.toAbsolutePath()
            require(binary == binary.normalize())
            requireNoSymlinks(binary)
            val app =
                generateSequence(binary.parent) { it.parent }.firstOrNull {
                    it.fileName?.toString()?.endsWith(".app") == true && Files.isDirectory(it.resolve("Contents"), NOFOLLOW_LINKS)
                } ?: error("Missing application bundle")
            require(binary == app.resolve("Contents/app/resources/perfetto-tools/$UNIX_BINARY"))
            val metadataPath = binary.resolveSibling(SIGNATURE_METADATA)
            val metadataBytes = readSignatureMetadata(metadataPath)
            val metadata = Json.decodeFromString(SignedTraceProcessorMetadata, metadataBytes.decodeToString(throwOnInvalidSequence = true))
            val schemaVersion = metadata.getValue("schemaVersion")
            require(!schemaVersion.isString && schemaVersion.content == "1")
            fun stringValue(key: String): String = metadata.getValue(key).also { require(it.isString) }.content
            require(stringValue("upstreamVersion") == TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION)
            require(stringValue("binary") == UNIX_BINARY)
            require(stringValue("upstreamSha256") == upstreamSha256)
            require(stringValue("signedSha256") == actualSha256)
            val teamId = stringValue("teamId")
            require(Regex("[A-Z0-9]{10}").matches(teamId))
            val verification =
                processRunner.executeText(
                    HostProcessRequest(
                        executable = Path.of("/usr/bin/codesign"),
                        arguments =
                            listOf(
                                "--verify",
                                "--deep",
                                "--strict",
                                "-R",
                                "=anchor apple generic and certificate leaf[subject.OU] = \"$teamId\"",
                                app.toString(),
                            ),
                        timeout = 15.seconds,
                        maxOutputBytesPerStream = 16 * 1024,
                    ),
                )
            currentCoroutineContext().ensureActive()
            require(verification.exitCode == 0 && !verification.stdoutTruncated && !verification.stderrTruncated)
            requireNoSymlinks(binary)
            require(Files.isRegularFile(binary, NOFOLLOW_LINKS) && Files.isExecutable(binary))
            require(readSignatureMetadata(metadataPath).contentEquals(metadataBytes))
            require(ArtifactFileEvidence.sha256(binary).value == actualSha256)
            // The sealed metadata proves a signed transformation, not native tool qualification.
            StudioResult.Success(TraceProcessorTool(path, TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION, actualSha256))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failure(
                ErrorCategory.DATA_VALIDATION,
                "TRACE_PROCESSOR_SIGNED_RESOURCE_INVALID",
                "Packaged Trace Processor signed-resource integrity verification failed",
            )
        }

    private fun requireNoSymlinks(path: Path) {
        require(generateSequence(path) { it.parent }.none(Files::isSymbolicLink))
    }

    private fun readSignatureMetadata(path: Path): ByteArray {
        requireNoSymlinks(path)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            input.readNBytes(MAX_SIGNATURE_METADATA_BYTES + 1).also { require(it.size in 1..MAX_SIGNATURE_METADATA_BYTES) }
        }
    }

    private suspend fun resolveOverride(path: Path): StudioResult<TraceProcessorTool> {
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            return failure(ErrorCategory.CONFIGURATION, "TRACE_PROCESSOR_OVERRIDE_INVALID", "Configured Trace Processor is not executable: $path")
        }
        val versionResult =
            runCatching {
                processRunner.executeText(
                    HostProcessRequest(
                        executable = path,
                        arguments = listOf("--version"),
                        timeout = 5.seconds,
                    ),
                )
            }.getOrElse { error ->
                return failure(
                    ErrorCategory.PROCESS_START,
                    "TRACE_PROCESSOR_OVERRIDE_PROBE_FAILED",
                    error.message ?: "Configured Trace Processor could not be inspected",
                )
            }
        val reportedVersion = sequenceOf(versionResult.stdout, versionResult.stderr).joinToString("\n")
        if (versionResult.exitCode != 0 || !reportedVersion.contains(TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION)) {
            return failure(
                ErrorCategory.CONFIGURATION,
                "TRACE_PROCESSOR_INCOMPATIBLE",
                "Configured Trace Processor must report ${TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION}",
            )
        }
        return StudioResult.Success(
            TraceProcessorTool(
                path,
                TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION,
                ArtifactFileEvidence.sha256(path).value,
            ),
        )
    }

    private companion object {
        const val UNIX_BINARY: String = "trace_processor_shell"
        const val WINDOWS_BINARY: String = "trace_processor_shell.exe"
        const val SIGNATURE_METADATA: String = "trace-processor-signature.json"
        const val MAX_SIGNATURE_METADATA_BYTES: Int = 4 * 1024
        const val COMPOSE_APPLICATION_RESOURCES_PROPERTY: String = "compose.application.resources.dir"
        const val TRACE_PROCESSOR_OVERRIDE_PROPERTY: String = "androidperformancestudio.traceProcessorPath"
        const val TRACE_PROCESSOR_OVERRIDE_ENVIRONMENT: String = "PERFETTO_TRACE_PROCESSOR_PATH"
    }
}

private object SignedTraceProcessorMetadata : DeserializationStrategy<Map<String, JsonPrimitive>> {
    private val keys = listOf("schemaVersion", "upstreamVersion", "binary", "upstreamSha256", "signedSha256", "teamId")

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("SignedTraceProcessorMetadata") {
            keys.forEach { element<JsonPrimitive>(it) }
        }

    override fun deserialize(decoder: Decoder): Map<String, JsonPrimitive> =
        decoder.decodeStructure(descriptor) {
            val values = mutableMapOf<String, JsonPrimitive>()
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                val key = keys[index]
                require(key !in values)
                values[key] = decodeSerializableElement(descriptor, index, JsonPrimitive.serializer())
            }
            require(values.keys == keys.toSet())
            values
        }
}

private fun <T> failure(
    category: ErrorCategory,
    code: String,
    message: String,
): StudioResult<T> = StudioResult.Failure(StudioError(category, code, message))

private sealed interface BinaryVerification {
    data class Valid(val tool: TraceProcessorTool) : BinaryVerification

    /** The file is a regular file with the pinned checksum but is not executable (bit lost in packaging). */
    data class NotExecutable(val path: Path) : BinaryVerification

    /** The file is a regular file whose SHA-256 does not match the pinned manifest. */
    data class ChecksumMismatch(val path: Path, val actualSha256: String) : BinaryVerification

    /** The path exists but is not a regular file (e.g. a directory). */
    data object NotRegular : BinaryVerification
}
