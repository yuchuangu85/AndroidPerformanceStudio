package com.androidperformancestudio.platform.perfetto

import com.androidperformancestudio.contracts.ArtifactFileEvidence
import com.androidperformancestudio.model.StudioResult
import com.androidperformancestudio.platform.toolchain.CpuArchitecture
import com.androidperformancestudio.platform.toolchain.HostOperatingSystem
import com.androidperformancestudio.platform.toolchain.HostPlatform
import com.androidperformancestudio.platform.toolchain.HostPlatformDetector
import com.androidperformancestudio.platform.toolchain.HostProcessBinaryResult
import com.androidperformancestudio.platform.toolchain.HostProcessCancelledException
import com.androidperformancestudio.platform.toolchain.HostProcessLaunchRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRequest
import com.androidperformancestudio.platform.toolchain.HostProcessRunner
import com.androidperformancestudio.platform.toolchain.HostProcessTextResult
import com.androidperformancestudio.platform.toolchain.HostProcessTimeoutException
import com.androidperformancestudio.platform.toolchain.RunningHostProcess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SignedTraceProcessorResourceTest {
    @Test
    fun `signed packaged mac binaries require bounded outer app verification with the metadata team`() =
        runBlocking {
            for (architecture in listOf(CpuArchitecture.ARM64, CpuArchitecture.X64)) {
                Fixture(architecture = architecture).use { fixture ->
                    val tool = assertIs<StudioResult.Success<TraceProcessorTool>>(fixture.resolve()).value

                    assertEquals(fixture.binary, tool.path)
                    assertEquals(TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION, tool.version)
                    assertEquals(ArtifactFileEvidence.sha256(fixture.binary).value, tool.sha256)
                    val request = fixture.runner.requests.single()
                    assertEquals(Path.of("/usr/bin/codesign"), request.executable)
                    assertEquals(
                        listOf(
                            "--verify",
                            "--deep",
                            "--strict",
                            "-R",
                            "=anchor apple generic and certificate leaf[subject.OU] = \"AB12CD34EF\"",
                            fixture.app.toString(),
                        ),
                        request.arguments,
                    )
                    assertTrue(request.timeout.isPositive() && request.timeout <= 30.seconds)
                    assertTrue(request.maxOutputBytesPerStream in 1..64 * 1024)
                    assertTrue(request.environmentOverrides.isEmpty())
                }
            }
        }

    @Test
    fun `upstream matching unsigned development and installed binaries never probe a signature`() =
        runBlocking {
            Fixture().use { fixture ->
                Files.writeString(fixture.binary, "original upstream bytes")
                Files.writeString(fixture.metadata, "not metadata")
                val standaloneResources = fixture.root.resolve("dev-resources")
                val developmentBinary = standaloneResources.resolve("perfetto-tools/trace_processor_shell")
                Files.createDirectories(developmentBinary.parent)
                Files.copy(fixture.binary, developmentBinary)
                assertTrue(developmentBinary.toFile().setExecutable(true))

                assertIs<StudioResult.Success<TraceProcessorTool>>(fixture.resolve())
                assertIs<StudioResult.Success<TraceProcessorTool>>(fixture.resolve(resources = standaloneResources))
                assertIs<StudioResult.Success<TraceProcessorTool>>(
                    fixture.resolve(resources = null, installed = fixture.binary.parent),
                )
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `tampered upstream signed hash version binary and team are rejected before codesign`() =
        runBlocking {
            Fixture().use { fixture ->
                val mutations =
                    listOf(
                        "upstreamSha256" to "0".repeat(64),
                        "upstreamSha256" to fixture.upstreamSha256.uppercase(),
                        "signedSha256" to "0".repeat(64),
                        "signedSha256" to ArtifactFileEvidence.sha256(fixture.binary).value.uppercase(),
                        "upstreamVersion" to "v58.0",
                        "binary" to "../trace_processor_shell",
                        "binary" to fixture.binary.toString(),
                        "binary" to "perfetto-tools/trace_processor_shell",
                        "teamId" to "ab12cd34ef",
                        "teamId" to "AB12CD34E",
                        "teamId" to "AB12CD34EFG",
                        "teamId" to "AB12CD34E-",
                        "teamId" to "ADHOC",
                        "teamId" to "AB12CD34EF\" or true",
                    )
                for ((key, value) in mutations) {
                    fixture.writeMetadata(fixture.fields() + (key to JsonPrimitive(value)))
                    assertSignedFailure(fixture.resolve())
                }
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `metadata requires exactly the known unique keys`() =
        runBlocking {
            Fixture().use { fixture ->
                for (key in fixture.fields().keys) {
                    fixture.writeMetadata(fixture.fields() - key)
                    assertSignedFailure(fixture.resolve())
                }
                fixture.writeMetadata(fixture.fields() + ("host" to JsonPrimitive("macos-arm64")))
                assertSignedFailure(fixture.resolve())
                val valid = JsonObject(fixture.fields()).toString()
                Files.writeString(fixture.metadata, valid.dropLast(1) + ",\"schemaVersion\":1}")
                assertSignedFailure(fixture.resolve())
                Files.writeString(fixture.metadata, valid.dropLast(1) + ",\"teamId\":\"AB12CD34EF\"}")
                assertSignedFailure(fixture.resolve())
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `metadata rejects wrong json value types without coercion`() =
        runBlocking {
            Fixture().use { fixture ->
                for (key in fixture.fields().keys - "schemaVersion") {
                    for (value in listOf(JsonPrimitive(1), JsonPrimitive(true), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))) {
                        fixture.writeMetadata(fixture.fields() + (key to value))
                        assertSignedFailure(fixture.resolve())
                    }
                }
                for (value in listOf(JsonPrimitive("1"), JsonPrimitive(1.0), JsonPrimitive(2), JsonPrimitive(true), JsonNull)) {
                    fixture.writeMetadata(fixture.fields() + ("schemaVersion" to value))
                    assertSignedFailure(fixture.resolve())
                }
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `missing malformed oversized and non utf8 metadata fail closed`() =
        runBlocking {
            Fixture().use { fixture ->
                Files.delete(fixture.metadata)
                assertSignedFailure(fixture.resolve())
                val validJson = JsonObject(fixture.fields()).toString()
                for (text in listOf("", "[]", "null", "{", "{}", validJson + " trailing", validJson.padEnd(4097, ' '))) {
                    Files.writeString(fixture.metadata, text)
                    assertSignedFailure(fixture.resolve())
                }
                val valid = JsonObject(fixture.fields()).toString().toByteArray()
                Files.write(fixture.metadata, valid + byteArrayOf(0xc3.toByte(), 0x28))
                assertSignedFailure(fixture.resolve())
                Files.delete(fixture.metadata)
                Files.createDirectory(fixture.metadata)
                assertSignedFailure(fixture.resolve())
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `metadata byte limit includes otherwise valid trailing whitespace`() =
        runBlocking {
            Fixture().use { fixture ->
                Files.writeString(fixture.metadata, JsonObject(fixture.fields()).toString().padEnd(4096, ' '))
                assertIs<StudioResult.Success<TraceProcessorTool>>(fixture.resolve())
                assertEquals(1, fixture.runner.requests.size)
            }
        }

    @Test
    fun `deeply nested non scalar metadata fails without a signature probe`() =
        runBlocking {
            Fixture().use { fixture ->
                val nested = "[".repeat(1800) + "1" + "]".repeat(1800)
                val malformed = JsonObject(fixture.fields()).toString().replace("\"schemaVersion\":1", "\"schemaVersion\":$nested")
                assertTrue(malformed.toByteArray().size < 4096)
                Files.writeString(fixture.metadata, malformed)

                assertSignedFailure(fixture.resolve())
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `changed binary cannot reuse signed metadata`() =
        runBlocking {
            Fixture().use { fixture ->
                Files.writeString(fixture.binary, "tampered actual bytes")
                assertSignedFailure(fixture.resolve())
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `signature metadata and every ancestor including the binary must not be symlinks`() =
        runBlocking {
            for (relative in listOf("metadata", "binary", "tools", "resources", "app-dir", "contents", "bundle", "parent")) {
                Fixture().use { fixture ->
                    val link =
                        when (relative) {
                            "metadata" -> fixture.metadata
                            "binary" -> fixture.binary
                            "tools" -> fixture.binary.parent
                            "resources" -> fixture.resources
                            "app-dir" -> fixture.resources.parent
                            "contents" -> fixture.app.resolve("Contents")
                            "bundle" -> fixture.app
                            else -> fixture.app.parent
                        }
                    val target = fixture.root.resolve("symlink-target")
                    Files.move(link, target)
                    Files.createSymbolicLink(link, target)

                    assertSignedFailure(fixture.resolve())
                    assertTrue(fixture.runner.requests.isEmpty())
                }
            }
        }

    @Test
    fun `signed metadata is accepted only at the fixed enclosing app resource path`() =
        runBlocking {
            for (relative in listOf("resources", "Other.bundle/Contents/app/resources", "Other.app/resources", "Other.app/Contents/Resources")) {
                Fixture().use { fixture ->
                    val resources = fixture.root.resolve(relative)
                    val tools = Files.createDirectories(resources.resolve("perfetto-tools"))
                    Files.copy(fixture.binary, tools.resolve("trace_processor_shell"))
                    assertTrue(tools.resolve("trace_processor_shell").toFile().setExecutable(true))
                    Files.copy(fixture.metadata, tools.resolve("trace-processor-signature.json"))

                    assertSignedFailure(fixture.resolve(resources = resources))
                    assertTrue(fixture.runner.requests.isEmpty())
                }
            }
        }

    @Test
    fun `path traversal aliases are not signed resource locations`() =
        runBlocking {
            Fixture().use { fixture ->
                Files.createDirectory(fixture.resources.resolve("alias"))
                assertSignedFailure(fixture.resolve(resources = fixture.resources.resolve("alias/..")))
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `non mac packaged and installed tools still require the original pin despite signed inventory`() =
        runBlocking {
            for (os in listOf(HostOperatingSystem.LINUX, HostOperatingSystem.WINDOWS)) {
                Fixture(os).use { fixture ->
                    assertEquals("TRACE_PROCESSOR_CHECKSUM_MISMATCH", assertIs<StudioResult.Failure>(fixture.resolve()).error.code)
                    assertTrue(fixture.runner.requests.isEmpty())
                }
            }
            Fixture().use { fixture ->
                val result = fixture.resolve(resources = null, installed = fixture.binary.parent)
                assertEquals("TRACE_PROCESSOR_CHECKSUM_MISMATCH", assertIs<StudioResult.Failure>(result).error.code)
                assertTrue(fixture.runner.requests.isEmpty())
            }
        }

    @Test
    fun `explicit overrides cannot use inventory instead of the existing version probe`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.runner.respond = { textResult(stdout = "Perfetto v58.0") }
                val rejected = fixture.resolve(override = fixture.binary)
                assertEquals("TRACE_PROCESSOR_INCOMPATIBLE", assertIs<StudioResult.Failure>(rejected).error.code)
                fixture.runner.respond = { textResult(stdout = TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION) }
                assertIs<StudioResult.Success<TraceProcessorTool>>(fixture.resolve(override = fixture.binary))
                assertTrue(fixture.runner.requests.all { it.executable == fixture.binary && it.arguments == listOf("--version") })
            }
        }

    @Test
    fun `invalid unsigned ad hoc and truncated signature results cannot be inventory bypasses`() =
        runBlocking {
            Fixture().use { fixture ->
                for (result in listOf(
                    textResult(exitCode = 1, stdout = "sensitive stdout", stderr = "code object is not signed at all"),
                    textResult(exitCode = 3, stderr = "Signature=adhoc"),
                    textResult().copy(stdoutTruncated = true),
                    textResult().copy(stderrTruncated = true),
                )) {
                    fixture.runner.respond = { result }
                    assertSignedFailure(fixture.resolve())
                }
                assertEquals(4, fixture.runner.requests.size)
            }
        }

    @Test
    fun `valid format tampered team must satisfy the actual app signature requirement`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.writeMetadata(fixture.fields() + ("teamId" to JsonPrimitive("ZZ12CD34EF")))
                fixture.runner.respond = { request ->
                    assertEquals("=anchor apple generic and certificate leaf[subject.OU] = \"ZZ12CD34EF\"", request.arguments[4])
                    textResult(exitCode = 3, stderr = "code failed to satisfy specified code requirement")
                }

                assertSignedFailure(fixture.resolve())
                assertEquals(1, fixture.runner.requests.size)
            }
        }

    @Test
    fun `signature launch and timeout errors are sanitized`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.runner.respond = { throw IllegalStateException("sensitive path\nsecret diagnostic") }
                assertSignedFailure(fixture.resolve())
                fixture.runner.respond = { request -> throw HostProcessTimeoutException(request.command, request.timeout, 123) }
                assertSignedFailure(fixture.resolve())
            }
        }

    @Test
    fun `signature cancellation propagates unchanged`() =
        runBlocking {
            Fixture().use { fixture ->
                for (cancelled in listOf(
                    CancellationException("cancel verification"),
                    HostProcessCancelledException(listOf("/usr/bin/codesign"), 123),
                )) {
                    fixture.runner.respond = { throw cancelled }
                    assertSame(cancelled, assertFailsWith<CancellationException> { fixture.resolve() })
                }
            }
        }

    @Test
    fun `binary or metadata mutation during signature verification is rejected`() =
        runBlocking {
            for (mutateMetadata in listOf(false, true)) {
                Fixture().use { fixture ->
                    fixture.runner.respond = {
                        if (mutateMetadata) {
                            fixture.writeMetadata(fixture.fields() + ("teamId" to JsonPrimitive("ZZ12CD34EF")))
                        } else {
                            Files.writeString(fixture.binary, "changed during verification")
                        }
                        textResult()
                    }
                    assertSignedFailure(fixture.resolve())
                }
            }
        }

    private class Fixture(
        os: HostOperatingSystem = HostOperatingSystem.MACOS,
        architecture: CpuArchitecture = CpuArchitecture.ARM64,
    ) : AutoCloseable {
        val root: Path = Files.createTempDirectory("signed-trace-processor").toRealPath()
        val app: Path = root.resolve("bundle/APS Test.app")
        val resources: Path = app.resolve("Contents/app/resources")
        val binary: Path = resources.resolve("perfetto-tools/" + if (os == HostOperatingSystem.WINDOWS) "trace_processor_shell.exe" else "trace_processor_shell")
        val metadata: Path = binary.resolveSibling("trace-processor-signature.json")
        val runner = RecordingRunner()
        private val platform = HostPlatform(os, architecture)
        val upstreamSha256: String

        init {
            Files.createDirectories(binary.parent)
            Files.writeString(binary, "original upstream bytes")
            assertTrue(binary.toFile().setExecutable(true))
            upstreamSha256 = ArtifactFileEvidence.sha256(binary).value
            Files.writeString(binary, "signed transformed bytes")
            writeMetadata(fields())
        }

        fun fields(): Map<String, JsonElement> =
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "upstreamVersion" to JsonPrimitive(TraceQuerySchema.PINNED_TRACE_PROCESSOR_VERSION),
                "binary" to JsonPrimitive("trace_processor_shell"),
                "upstreamSha256" to JsonPrimitive(upstreamSha256),
                "signedSha256" to JsonPrimitive(ArtifactFileEvidence.sha256(binary).value),
                "teamId" to JsonPrimitive("AB12CD34EF"),
            )

        fun writeMetadata(values: Map<String, JsonElement>) {
            Files.writeString(metadata, JsonObject(values).toString())
        }

        suspend fun resolve(
            resources: Path? = this.resources,
            installed: Path = root.resolve("not-installed"),
            override: Path? = null,
        ): StudioResult<TraceProcessorTool> =
            TraceProcessorToolResolver(
                platformDetector = HostPlatformDetector { StudioResult.Success(platform) },
                manifest = TraceProcessorManifest(mapOf(platform to upstreamSha256)),
                applicationResourcesPath = resources,
                installedToolsPath = installed,
                configuredOverride = override,
                processRunner = runner,
            ).resolve()

        override fun close() {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private class RecordingRunner : HostProcessRunner {
        val requests = mutableListOf<HostProcessRequest>()
        var respond: (HostProcessRequest) -> HostProcessTextResult = { textResult() }

        override suspend fun executeText(request: HostProcessRequest): HostProcessTextResult {
            requests += request
            return respond(request)
        }

        override suspend fun executeBinary(request: HostProcessRequest): HostProcessBinaryResult = error("Unexpected binary process")

        override fun launch(request: HostProcessLaunchRequest): RunningHostProcess = error("Unexpected launch")
    }

    private companion object {
        fun assertSignedFailure(result: StudioResult<TraceProcessorTool>) {
            val error = assertIs<StudioResult.Failure>(result).error
            assertEquals("TRACE_PROCESSOR_SIGNED_RESOURCE_INVALID", error.code)
            assertEquals("Packaged Trace Processor signed-resource integrity verification failed", error.message)
        }

        fun textResult(
            exitCode: Int = 0,
            stdout: String = "",
            stderr: String = "",
        ): HostProcessTextResult = HostProcessTextResult(123, exitCode, stdout, stderr, Duration.ZERO, false, false)
    }
}
