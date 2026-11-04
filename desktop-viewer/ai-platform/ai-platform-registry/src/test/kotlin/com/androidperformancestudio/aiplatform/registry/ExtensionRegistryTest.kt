package com.androidperformancestudio.aiplatform.registry

import com.androidperformancestudio.aiplatform.model.AuditAction
import com.androidperformancestudio.aiplatform.model.AuditRecord
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.ExtensionOrigin
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.model.TrustStatus
import kotlinx.serialization.encodeToString
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionRegistryTest {
    @Test
    fun `explicit version choice resolves ambiguity and missing selected version never falls back`() =
        Fixture().use { fixture ->
            val first = fixture.registry.install(fixture.bundle())
            val next = fixture.registry.install(fixture.bundle(version = "2.0.0"))
            assertCode("E_DUPLICATE_ID") { fixture.registry.resolve(ExtensionKind.AGENT, first.key.id) }
            fixture.registry.selectVersion(first.key)
            assertEquals(first, fixture.registry.resolve(ExtensionKind.AGENT, first.key.id))
            fixture.registry.selectVersion(next.key)
            assertEquals(next, fixture.registry.resolve(ExtensionKind.AGENT, first.key.id))
            fixture.registry.deleteImported(next.key)
            assertCode("E_SELECTED_VERSION_UNAVAILABLE") { fixture.registry.resolve(ExtensionKind.AGENT, first.key.id) }
            fixture.registry.selectVersion(first.key)
            assertEquals(first, fixture.registry.resolve(ExtensionKind.AGENT, first.key.id))
        }

    @Test
    fun `legacy builtin version is retained disabled and does not poison upgraded scan`() =
        Fixture().use { fixture ->
            val current = fixture.registry.installBuiltins().first()
            val legacyDirectory = current.directory.parent.resolve("1.0.0")
            current.directory.toFile().copyRecursively(legacyDirectory.toFile())
            val oldManifestPath = legacyDirectory.resolve("manifest.json")
            val oldManifest = current.manifest.copy(version = "1.0.0")
            val oldBytes = PlatformJson.codec.encodeToString(oldManifest).toByteArray()
            Files.write(oldManifestPath, oldBytes)
            val statePath = legacyDirectory.resolve("installation.json")
            val state = PlatformJson.codec.decodeFromString<InstallationState>(Files.readString(statePath))
            val files = oldManifest.files.mapValues { (path, _) -> Files.readAllBytes(legacyDirectory.resolve(path)) }
            Files.writeString(
                statePath,
                PlatformJson.codec.encodeToString(state.copy(version = "1.0.0", contentHash = packageHash(oldBytes, files))),
            )
            val scan = fixture.registry.scan()
            assertTrue(scan.issues.isEmpty())
            val legacy = scan.entries.single { it.key.id == current.key.id && it.key.version == "1.0.0" }
            assertFalse(legacy.usable)
            assertTrue(Files.exists(legacy.directory))
            assertEquals(current, fixture.registry.resolve(current.key.kind, current.key.id))
        }

    @Test
    fun `deleting exact imported version preserves other versions and protects builtin`() =
        Fixture().use { fixture ->
            val first = fixture.registry.install(fixture.bundle())
            val next = fixture.registry.install(fixture.bundle(version = "2.0.0"))
            fixture.registry.deleteImported(first.key)
            assertCode("E_EXTENSION_UNAVAILABLE") { fixture.registry.resolve(first.key) }
            assertEquals(next, fixture.registry.resolve(next.key))
            val builtin = fixture.registry.installBuiltins().first()
            assertCode("E_BUILTIN_PROTECTED") { fixture.registry.deleteImported(builtin.key) }
            assertEquals(builtin, fixture.registry.resolve(builtin.key))
        }

    @Test
    fun `construction does not install or touch the profile`() =
        Fixture().use { fixture ->
            ExtensionRegistry(fixture.directories)
            assertFalse(Files.exists(fixture.directories.root))
        }

    @Test
    fun `initialize creates private managed directories and preserves unrelated data`() =
        Fixture().use { fixture ->
            Files.createDirectories(fixture.directories.root)
            val unrelated = fixture.directories.root.resolve("existing-settings.json")
            Files.writeString(unrelated, "existing data")
            fixture.directories.initialize()
            val paths =
                listOf(
                    fixture.directories.root,
                    fixture.directories.agents,
                    fixture.directories.skills,
                    fixture.directories.conversations,
                    fixture.directories.artifacts,
                    fixture.directories.providerState,
                    fixture.directories.audit,
                )
            assertTrue(paths.all { Files.isDirectory(it) })
            if (Files.getFileStore(fixture.temp).supportsFileAttributeView("posix")) {
                paths.forEach { assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(it)) }
            }
            assertEquals("existing data", Files.readString(unrelated))
        }

    @Test
    fun `import is verified immutable untrusted disabled and source remains unchanged`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            val before = Files.readAllBytes(source.resolve("manifest.json"))
            val entry = fixture.registry.install(source)
            assertEquals(ExtensionOrigin.IMPORTED, entry.key.origin)
            assertEquals(TrustStatus.UNTRUSTED, entry.trustStatus)
            assertFalse(entry.enabled)
            assertFalse(entry.usable)
            assertEquals(entry, fixture.registry.resolve(entry.key))
            assertTrue(before.contentEquals(Files.readAllBytes(source.resolve("manifest.json"))))
            assertFalse(Files.exists(source.resolve("installation.json")))
            assertTrue(entry.directory.endsWith(Path.of("agents", "imported", "example.agent", "1.0.0")))
            assertEquals(entry, fixture.registry.install(source))
        }

    @Test
    fun `trust enable disable revoke require explicit host transitions and survive scans`() =
        Fixture().use { fixture ->
            val key = fixture.registry.install(fixture.bundle()).key
            assertCode("E_EXTENSION_UNTRUSTED") { fixture.registry.enable(key) }
            assertFalse(fixture.registry.trust(key).enabled)
            assertTrue(fixture.registry.enable(key).usable)
            assertFalse(fixture.registry.disable(key).usable)
            assertTrue(fixture.registry.enable(key).usable)
            val revoked = fixture.registry.revoke(key)
            assertEquals(TrustStatus.REVOKED, revoked.trustStatus)
            assertFalse(revoked.usable)
            assertEquals(revoked, ExtensionRegistry(fixture.directories).resolve(key))
            assertCode("E_EXTENSION_REVOKED") { fixture.registry.trust(key) }
            assertCode("E_EXTENSION_UNTRUSTED") { fixture.registry.enable(key) }
            assertEquals(revoked, fixture.registry.install(fixture.bundle()))
        }

    @Test
    fun `builtin installation is verified idempotent and does not change imported packages`() =
        Fixture().use { fixture ->
            val imported = fixture.registry.install(fixture.bundle())
            val builtin = fixture.registry.installBuiltins()
            assertEquals(8, builtin.size)
            assertTrue(builtin.all { it.usable && it.key.origin == ExtensionOrigin.BUILTIN })
            assertEquals(5, builtin.count { it.key.kind == ExtensionKind.AGENT })
            assertEquals(3, builtin.count { it.key.kind == ExtensionKind.SKILL })
            assertTrue(builtin.all { it.manifest.runtime == ScriptRuntime.NONE && it.manifest.scripts.isEmpty() })
            assertEquals(builtin, fixture.registry.installBuiltins())
            assertEquals(imported, fixture.registry.resolve(imported.key))
            val main = builtin.single { it.key.id == "aps.main" }
            fixture.registry.revoke(main.key)
            fixture.registry.installBuiltins()
            assertEquals(TrustStatus.REVOKED, fixture.registry.resolve(main.key).trustStatus)
        }

    @Test
    fun `import cannot impersonate protected builtin identity before builtin installation`() =
        Fixture().use { fixture ->
            assertCode("E_RESERVED_ID") { fixture.registry.install(fixture.bundle(id = "aps.main")) }
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun `same version with different content conflicts while distinct versions are preserved`() =
        Fixture().use { fixture ->
            val original = fixture.registry.install(fixture.bundle())
            assertCode("E_VERSION_CONFLICT") { fixture.registry.install(fixture.bundle(text = "changed prompt")) }
            assertEquals(original, fixture.registry.resolve(original.key))
            val next = fixture.registry.install(fixture.bundle(version = "2.0.0"))
            assertEquals(
                2,
                fixture.registry
                    .scan()
                    .entries.size,
            )
            assertEquals(original, fixture.registry.resolve(original.key))
            assertEquals(next, fixture.registry.resolve(next.key))
            assertCode("E_DUPLICATE_ID") { fixture.registry.resolve(ExtensionKind.AGENT, "example.agent") }
        }

    @Test
    fun `unknown stable ID is unavailable and does not fall back to builtin`() =
        Fixture().use { fixture ->
            fixture.registry.installBuiltins()
            assertCode("E_EXTENSION_UNAVAILABLE") { fixture.registry.resolve(ExtensionKind.AGENT, "missing.agent") }
        }

    @Test
    fun `malformed manifest and unsupported schema runtime or trust claims are rejected`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            val path = source.resolve("manifest.json")
            val valid = Files.readString(path)
            listOf(
                "{",
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"runtime\":\"NONE\"", "\"runtime\":\"SHELL\""),
                valid.replaceFirst("{", "{\"trustStatus\":\"TRUSTED\","),
                valid.replaceFirst("{", "{\"origin\":\"BUILTIN\","),
                valid.replaceFirst("{", "{\"enabled\":true,"),
            ).forEach { malformed ->
                Files.writeString(path, malformed)
                assertCode("E_MANIFEST_INVALID") { fixture.registry.install(source) }
            }
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun `path traversal absolute paths and reserved metadata filenames cannot enter manifest`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            val path = source.resolve("manifest.json")
            val valid = Files.readString(path)
            listOf("../escape.md", "/absolute.md", "installation.json", "scripts/../../escape.md", "C:\\\\escape.md").forEach { invalid ->
                Files.writeString(path, valid.replace("prompt.md", invalid))
                assertCode("E_MANIFEST_INVALID") { fixture.registry.install(source) }
            }
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun `undeclared files are rejected without copying content`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            Files.writeString(source.resolve("unlisted-secret.txt"), "never publish")
            assertCode("E_UNDECLARED_FILE") { fixture.registry.install(source) }
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun `imported installation state file is never accepted`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            Files.writeString(source.resolve("installation.json"), "{\"enabled\":true}")
            assertCode("E_UNDECLARED_FILE") { fixture.registry.install(source) }
        }

    @Test
    fun `missing declared files and wrong hash fail closed`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            Files.writeString(source.resolve("prompt.md"), "different content")
            assertCode("E_HASH_MISMATCH") { fixture.registry.install(source) }
            Files.delete(source.resolve("prompt.md"))
            assertCode("E_MISSING_FILE") { fixture.registry.install(source) }
        }

    @Test
    fun `manifest file script and total package byte limits are enforced before publication`() =
        Fixture().use { fixture ->
            val source = fixture.bundle(text = "1234567890")
            assertCode("E_FILE_LIMIT") {
                ExtensionRegistry(fixture.directories, RegistryLimits(maxManifestBytes = 8)).install(source)
            }
            assertCode("E_FILE_LIMIT") {
                ExtensionRegistry(fixture.directories, RegistryLimits(maxFileBytes = 9)).install(source)
            }
            assertCode("E_PACKAGE_LIMIT") {
                ExtensionRegistry(fixture.directories, RegistryLimits(maxPackageBytes = 9)).install(source)
            }
            val scripted = fixture.bundle(script = "print('oversized')")
            assertCode("E_FILE_LIMIT") {
                ExtensionRegistry(fixture.directories, RegistryLimits(maxScriptBytes = 2)).install(scripted)
            }
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun `script packages can be verified but no execution occurs on import or trust`() =
        Fixture().use { fixture ->
            val sentinel = fixture.temp.resolve("must-not-exist")
            val source = fixture.bundle(script = "from pathlib import Path\nPath('$sentinel').write_text('unsafe')")
            val entry = fixture.registry.install(source)
            assertEquals(ScriptRuntime.PYTHON, entry.manifest.runtime)
            fixture.registry.trust(entry.key)
            fixture.registry.enable(entry.key)
            assertFalse(Files.exists(sentinel))
        }

    @Test
    fun `deep or excessive directory trees are bounded`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            Files.createDirectories(source.resolve("extra/a/b/c/d/e"))
            assertCode("E_TREE_LIMIT") {
                ExtensionRegistry(fixture.directories, RegistryLimits(maxTreeEntries = 4)).install(source)
            }
        }

    @Test
    fun `source symlink file directory and ancestor are rejected`() =
        Fixture().use { fixture ->
            val source = fixture.bundle()
            val link = source.resolve("symlink.md")
            Files.createSymbolicLink(link, source.resolve("prompt.md"))
            assertCode("E_SYMLINK") { fixture.registry.install(source) }
            Files.delete(link)
            val directoryLink = fixture.temp.resolve("linked-source")
            Files.createSymbolicLink(directoryLink, source)
            assertCode("E_SYMLINK") { fixture.registry.install(directoryLink) }
            assertCode("E_SYMLINK") { fixture.registry.install(directoryLink.resolve("nested")) }
            Files.delete(source.resolve("prompt.md"))
            Files.createSymbolicLink(source.resolve("prompt.md"), fixture.temp.resolve("unrelated"))
            assertCode("E_SYMLINK") { fixture.registry.install(source) }
        }

    @Test
    fun `managed root and existing ancestors cannot be symlinks`() =
        Fixture().use { fixture ->
            val external = fixture.temp.resolve("external")
            Files.createDirectory(external)
            Files.createSymbolicLink(fixture.directories.root, external)
            assertCode("E_SYMLINK") { fixture.directories.initialize() }
            assertTrue(Files.list(external).use { it.findAny().isEmpty })
            val linkedParent = fixture.temp.resolve("linked-parent")
            Files.createSymbolicLink(linkedParent, external)
            assertCode("E_SYMLINK") { PlatformDirectories(linkedParent.resolve("profile")).initialize() }
        }

    @Test
    fun `managed extension parent symlink is rejected before writing outside root`() =
        Fixture().use { fixture ->
            fixture.directories.initialize()
            val external = Files.createDirectory(fixture.temp.resolve("external"))
            Files.createSymbolicLink(
                fixture.directories.agents
                    .resolve("imported")
                    .resolve("example.agent"),
                external,
            )
            assertCode("E_SYMLINK") { fixture.registry.install(fixture.bundle()) }
            assertTrue(Files.list(external).use { it.findAny().isEmpty })
        }

    @Test
    fun `atomic publication failure cleans private staging and leaves no published version`() =
        Fixture().use { fixture ->
            val registry =
                ExtensionRegistry(
                    fixture.directories,
                    atomicMove = {
                        source,
                        target,
                        ->
                        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "fixture")
                    },
                )
            assertCode("E_ATOMIC_MOVE_UNAVAILABLE") { registry.install(fixture.bundle()) }
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
            assertTrue(Files.list(fixture.directories.root.resolve(".registry-staging")).use { it.findAny().isEmpty })
        }

    @Test
    fun `filesystem failures expose only stable code without path secret message or cause`() =
        Fixture().use { fixture ->
            val registry =
                ExtensionRegistry(
                    fixture.directories,
                    atomicMove = { _, _ -> throw java.io.IOException("/private/secret/path fixture-sensitive-value") },
                )
            val failure = assertFailsWith<RegistryException> { registry.install(fixture.bundle()) }
            assertEquals("E_REGISTRY_IO", failure.code)
            assertEquals("E_REGISTRY_IO", failure.message)
            assertEquals(null, failure.cause)
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun `same JVM profile lock contention fails closed with stable busy state`() =
        Fixture().use { fixture ->
            fixture.directories.initialize()
            val lockPath = fixture.directories.providerState.resolve("registry.lock")
            FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    assertCode("E_REGISTRY_BUSY") { fixture.registry.scan() }
                    assertCode("E_REGISTRY_BUSY") { ExtensionRegistry(fixture.directories).install(fixture.bundle()) }
                }
            }
            assertTrue(
                fixture.registry
                    .scan()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun `scan revalidates content and identity rather than trusting stale installation state`() =
        Fixture().use { fixture ->
            val entry = fixture.registry.install(fixture.bundle())
            Files.writeString(entry.directory.resolve("prompt.md"), "tampered")
            val scan = fixture.registry.scan()
            assertTrue(scan.entries.isEmpty())
            assertEquals(listOf(RegistryIssue("E_HASH_MISMATCH")), scan.issues)
            assertCode("E_HASH_MISMATCH") { fixture.registry.resolve(entry.key) }
        }

    @Test
    fun `moving a version to another identity is rejected`() =
        Fixture().use { fixture ->
            val entry = fixture.registry.install(fixture.bundle())
            val wrongParent =
                Files.createDirectory(
                    entry.directory.parent.parent
                        .resolve("different.agent"),
                )
            Files.move(entry.directory, wrongParent.resolve(entry.key.version))
            val scan = fixture.registry.scan()
            assertTrue(scan.entries.isEmpty())
            assertTrue(scan.issues.any { it.code == "E_PACKAGE_IDENTITY" })
        }

    @Test
    fun `builtin tampering is detected even if installation hash is rewritten`() =
        Fixture().use { fixture ->
            val entry = fixture.registry.installBuiltins().first()
            val prompt = entry.directory.resolve(entry.manifest.entrypoint)
            val replacement = "tampered builtin".toByteArray()
            Files.write(prompt, replacement)
            val manifest = entry.manifest.copy(files = mapOf(entry.manifest.entrypoint to sha256(replacement)))
            val manifestBytes = PlatformJson.codec.encodeToString(manifest).toByteArray()
            Files.write(entry.directory.resolve("manifest.json"), manifestBytes)
            val stateFile = entry.directory.resolve("installation.json")
            val state = PlatformJson.codec.decodeFromString<InstallationState>(Files.readString(stateFile))
            val newHash = packageHash(manifestBytes, mapOf(manifest.entrypoint to replacement))
            Files.writeString(stateFile, PlatformJson.codec.encodeToString(state.copy(contentHash = newHash)))
            assertCode("E_BUILTIN_CONTENT") { fixture.registry.resolve(entry.key) }
        }

    @Test
    fun `installation origin cannot be changed by metadata or manifest`() =
        Fixture().use { fixture ->
            val entry = fixture.registry.install(fixture.bundle())
            val path = entry.directory.resolve("installation.json")
            Files.writeString(path, Files.readString(path).replace("IMPORTED", "BUILTIN"))
            assertCode("E_INSTALLATION_IDENTITY") { fixture.registry.resolve(entry.key) }
        }

    @Test
    fun `audit stores only stable reference metadata without prompt paths or script contents`() =
        Fixture().use { fixture ->
            val entry = fixture.registry.install(fixture.bundle(text = "secret-prompt-body-not-for-audit"))
            fixture.registry.trust(entry.key)
            fixture.registry.enable(entry.key)
            fixture.registry.disable(entry.key)
            fixture.registry.revoke(entry.key)
            val records =
                Files.list(fixture.directories.audit.resolve("registry")).use { files ->
                    files
                        .map { path ->
                            val text = Files.readString(path)
                            assertFalse(text.contains("secret-prompt-body-not-for-audit"))
                            assertFalse(text.contains(fixture.temp.toString()))
                            PlatformJson.codec.decodeFromString<AuditRecord>(text)
                        }.toList()
                }
            assertEquals(
                setOf(
                    AuditAction.INSTALLED,
                    AuditAction.TRUSTED,
                    AuditAction.ENABLED,
                    AuditAction.DISABLED,
                    AuditAction.REVOKED,
                ),
                records
                    .map {
                        it.action
                    }.toSet(),
            )
            assertTrue(records.all { it.subjectId == "example.agent" && it.contentHash == entry.contentHash })
        }

    private fun assertCode(
        code: String,
        block: () -> Unit,
    ) {
        assertEquals(code, assertFailsWith<RegistryException>(block = block).code)
    }
}

private class Fixture : AutoCloseable {
    val temp: Path = Files.createTempDirectory("aps-registry-test-").toRealPath()
    val directories = PlatformDirectories(temp.resolve("profile"))
    val registry = ExtensionRegistry(directories, clock = { 1_793_404_800_000 })

    fun bundle(
        id: String = "example.agent",
        version: String = "1.0.0",
        text: String = "bounded prompt",
        script: String? = null,
    ): Path {
        val source = Files.createTempDirectory(temp, "source-")
        Files.writeString(source.resolve("prompt.md"), text)
        val files = mutableMapOf("prompt.md" to sha256(text.toByteArray()))
        if (script != null) {
            Files.createDirectory(source.resolve("scripts"))
            Files.writeString(source.resolve("scripts/analyze.py"), script)
            files["scripts/analyze.py"] = sha256(script.toByteArray())
        }
        val manifest =
            ExtensionManifest(
                kind = ExtensionKind.AGENT,
                id = id,
                version = version,
                name = "Example Agent",
                description = "fixture",
                entrypoint = "prompt.md",
                inputSchema = "aps.fixture.input.v1",
                outputSchema = "aps.fixture.output.v1",
                contextKinds = setOf("simpleperf"),
                evidenceKinds = setOf("simpleperf-hotspots"),
                permissions = if (script == null) emptySet() else setOf(Permission.EXECUTE_SCRIPT),
                runtime = if (script == null) ScriptRuntime.NONE else ScriptRuntime.PYTHON,
                scripts = if (script == null) emptySet() else setOf("scripts/analyze.py"),
                files = files,
            )
        Files.writeString(source.resolve("manifest.json"), PlatformJson.codec.encodeToString(manifest))
        return source
    }

    override fun close() {
        temp.toFile().deleteRecursively()
    }
}
