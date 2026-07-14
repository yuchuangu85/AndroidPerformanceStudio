# Capture Archive Import/Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Advanced menu with a File menu that exports the current inspection capture as a portable `.apinspect`/`.zip` archive and imports that archive for offline display.

**Architecture:** Add a versioned ZIP-compatible archive codec and a protocol-aware archive service inside the Desktop module. Keep archive I/O independent from Compose and ADB, let `DesktopViewerApp` coordinate file selection and optional raw Visible Window Views capture, and add an explicit offline-archive state to the existing application store.

**Tech Stack:** Kotlin/JVM 2.3, Compose Desktop, kotlinx.serialization JSON, Java NIO/ZIP/SHA-256, JUnit 5, existing `ProtocolCodec`, `InspectorStore`, and `VisibleWindowViewsTextRenderer`.

---

## File Structure

### Create

- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchive.kt`
  - Archive inputs, outputs, metadata, limits, format exceptions, and raw attachment model.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveCodec.kt`
  - Versioned manifest serialization, ZIP read/write, checksums, limits, and atomic replacement.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveService.kt`
  - Converts between protocol snapshots and archive payloads and validates imported PNG data.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveFileChooser.kt`
  - Native open/save dialogs and extension normalization.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveUiState.kt`
  - Import/export progress, success, warning, and failure state.
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveCodecTest.kt`
  - Archive round-trip, manifest, integrity, safety, size, and atomic-write tests.
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveServiceTest.kt`
  - Snapshot/screenshot round-trip, protocol compatibility, and PNG validation tests.
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveFileChooserTest.kt`
  - Export extension normalization tests without opening Swing.
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveUiStateTest.kt`
  - Operation-state payload tests.

### Modify

- `desktop-viewer/desktop-app/build.gradle.kts`
  - Apply the existing serialization plugin, declare the existing JSON library explicitly, and expose the application version to the runtime.
- `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorState.kt`
  - Add the offline archive connection state.
- `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorStore.kt`
  - Add atomic archive loading with analysis and selection repair.
- `desktop-viewer/application/src/test/kotlin/dev/agentperf/application/InspectorStoreTest.kt`
  - Lock offline-load behavior.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
  - File/import/export/archive-status strings in English and Simplified Chinese.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
  - Present the offline archive state.
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`
  - Verify offline archive status and tone.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`
  - Replace Advanced with File and add Import plus Export.
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`
  - Verify labels, ordering, enablement, and busy state.
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`
  - Replace legacy export strings with new localized strings.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
  - Coordinate chooser, async archive operations, optional raw capture, auto-scan shutdown, state replacement, and dialogs.

### Delete

- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ExportDirectoryChooser.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporter.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/VisibleWindowViewsExportUiState.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporterTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/VisibleWindowViewsExportUiStateTest.kt`

## Task 1: Build the ZIP-Compatible Archive Codec

**Files:**
- Modify: `desktop-viewer/desktop-app/build.gradle.kts`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchive.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveCodec.kt`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveCodecTest.kt`

- [ ] **Step 1: Add a failing archive round-trip test**

Create `CaptureArchiveCodecTest.kt` with a temporary directory and the following first test:

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CaptureArchiveCodecTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `archive round trip preserves required and optional capture files`() {
        val target = tempDir.resolve("capture.apinspect")
        val input = CaptureArchivePayload(
            snapshotJson = """{"protocolVersion":{"major":1,"minor":0}}""",
            screenshotPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47),
            rawArtifacts = CaptureRawArtifacts(
                zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
                text = "visible hierarchy",
            ),
        )
        val metadata = CaptureArchiveMetadata(
            producerVersion = "0.1.2",
            packageName = "dev.agentperf.sample",
            capturedAtEpochMillis = 1234L,
            protocolMajor = 1,
            protocolMinor = 0,
        )

        val result = CaptureArchiveCodec().write(target, metadata, input)
        val output = CaptureArchiveCodec().read(target)

        assertEquals(target, result.path)
        assertTrue(result.rawArtifactsIncluded)
        assertEquals(input.snapshotJson, output.payload.snapshotJson)
        assertArrayEquals(input.screenshotPng, output.payload.screenshotPng)
        assertArrayEquals(input.rawArtifacts!!.zip, output.payload.rawArtifacts!!.zip)
        assertEquals(input.rawArtifacts!!.text, output.payload.rawArtifacts!!.text)
        assertEquals("dev.agentperf.sample", output.metadata.packageName)
        ZipFile(target.toFile()).use { zip ->
            assertEquals(
                setOf(
                    "manifest.json",
                    "capture/layout-snapshot.json",
                    "capture/screenshot.png",
                    "raw/visible-window-views.zip",
                    "raw/visible-window-views.txt",
                ),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
        }
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests dev.agentperf.desktop.CaptureArchiveCodecTest
```

Expected: compilation fails because `CaptureArchiveCodec`, `CaptureArchivePayload`,
`CaptureRawArtifacts`, and `CaptureArchiveMetadata` do not exist.

- [ ] **Step 3: Enable manifest serialization**

Update `desktop-viewer/desktop-app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

dependencies {
    implementation(project(":adb-gateway"))
    implementation(project(":application"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    // existing test dependencies remain unchanged
}
```

- [ ] **Step 4: Define the archive contract**

Create `CaptureArchive.kt` with these exact public-to-module contracts:

```kotlin
package dev.agentperf.desktop

import java.nio.file.Path
import kotlinx.serialization.Serializable

internal const val CAPTURE_ARCHIVE_FORMAT = "agentperf-inspector-capture"
internal const val CAPTURE_ARCHIVE_VERSION = 1

internal object CaptureArchivePaths {
    const val MANIFEST = "manifest.json"
    const val SNAPSHOT = "capture/layout-snapshot.json"
    const val SCREENSHOT = "capture/screenshot.png"
    const val RAW_ZIP = "raw/visible-window-views.zip"
    const val RAW_TEXT = "raw/visible-window-views.txt"
}

internal data class CaptureRawArtifacts(
    val zip: ByteArray,
    val text: String,
)

internal data class CaptureArchivePayload(
    val snapshotJson: String,
    val screenshotPng: ByteArray,
    val rawArtifacts: CaptureRawArtifacts? = null,
)

internal data class CaptureArchiveMetadata(
    val producerVersion: String,
    val packageName: String,
    val capturedAtEpochMillis: Long,
    val protocolMajor: Int,
    val protocolMinor: Int,
)

internal data class CaptureArchiveDocument(
    val metadata: CaptureArchiveMetadata,
    val payload: CaptureArchivePayload,
)

internal data class CaptureArchiveWriteResult(
    val path: Path,
    val rawArtifactsIncluded: Boolean,
)

internal class CaptureArchiveFormatException(message: String) :
    IllegalArgumentException(message)

@Serializable
internal data class CaptureArchiveManifest(
    val format: String,
    val archiveVersion: Int,
    val producerVersion: String,
    val packageName: String,
    val capturedAtEpochMillis: Long,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val entries: List<CaptureArchiveManifestEntry>,
)

@Serializable
internal data class CaptureArchiveManifestEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val required: Boolean,
)
```

- [ ] **Step 5: Implement deterministic write/read behavior**

Create `CaptureArchiveCodec.kt`. The implementation must:

```kotlin
internal class CaptureArchiveCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {
    fun write(
        target: Path,
        metadata: CaptureArchiveMetadata,
        payload: CaptureArchivePayload,
    ): CaptureArchiveWriteResult

    fun read(source: Path): CaptureArchiveDocument
}
```

Implement `write` using this exact data flow:

1. Convert snapshot and raw text to UTF-8.
2. Build an insertion-ordered map containing the required files and optional raw pair.
3. Build manifest entries from byte length and lowercase SHA-256.
4. Serialize `CaptureArchiveManifest`.
5. Write `manifest.json` first, followed by the insertion-ordered content map, using
   `ZipOutputStream`.
6. Write to `Files.createTempFile(target.parent, ".agentperf-capture-", ".tmp")`.
7. Move with `ATOMIC_MOVE` and `REPLACE_EXISTING`, falling back to `REPLACE_EXISTING`.
8. Delete the temporary file in `finally`.

Implement `read` by opening `ZipFile`, rejecting duplicate names, reading the manifest,
validating format/version, reading manifest-declared entries, verifying length and SHA-256,
then constructing `CaptureArchiveDocument`. Require both raw files or neither.

Use these limits in the companion object:

```kotlin
private const val MAX_ARCHIVE_BYTES = 96L * 1024 * 1024
private const val MAX_ENTRY_COUNT = 16
private const val MAX_MANIFEST_BYTES = 256 * 1024
private const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
private const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
private const val MAX_RAW_ZIP_BYTES = 32 * 1024 * 1024
private const val MAX_RAW_TEXT_BYTES = 8 * 1024 * 1024
private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 80L * 1024 * 1024
```

Read every entry through a bounded loop rather than `readBytes()` so unknown or dishonest
ZIP sizes cannot bypass the limits. Reject blank paths, absolute paths, backslashes, `.` or
`..` path segments, directory entries, and entries not declared by the manifest.

- [ ] **Step 6: Run the round-trip test**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests dev.agentperf.desktop.CaptureArchiveCodecTest
```

Expected: PASS.

- [ ] **Step 7: Commit the archive foundation**

```bash
git add desktop-viewer/desktop-app/build.gradle.kts \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchive.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveCodec.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveCodecTest.kt
git commit -m "Make inspection captures portable across desktop sessions" \
  -m "Constraint: The application-specific extension must remain a standard ZIP container." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: ./gradlew :desktop-app:test --tests dev.agentperf.desktop.CaptureArchiveCodecTest"
```

## Task 2: Lock Archive Validation and Atomic Replacement

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveCodec.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveCodecTest.kt`

- [ ] **Step 1: Add failing corruption and safety tests**

Add tests that create ZIP files with a small `writeZip(path, entries: List<Pair<String,
ByteArray>>)` helper and assert `CaptureArchiveFormatException` for:

```kotlin
@Test
fun `read rejects an archive without a manifest`() {
    val archive = tempDir.resolve("missing-manifest.zip")
    writeZip(archive, listOf(CaptureArchivePaths.SNAPSHOT to "{}".toByteArray()))

    assertThrows(CaptureArchiveFormatException::class.java) {
        CaptureArchiveCodec().read(archive)
    }
}

@Test
fun `read rejects duplicate manifest paths and traversal entries`() {
    val duplicate = tempDir.resolve("duplicate.zip")
    writeZip(
        duplicate,
        listOf(
            CaptureArchivePaths.MANIFEST to manifestWithDuplicateSnapshotPaths(),
            CaptureArchivePaths.SNAPSHOT to "{}".toByteArray(),
        ),
    )
    val traversal = tempDir.resolve("traversal.zip")
    writeZip(traversal, listOf("../outside" to byteArrayOf(1)))

    assertThrows(CaptureArchiveFormatException::class.java) {
        CaptureArchiveCodec().read(duplicate)
    }
    assertThrows(CaptureArchiveFormatException::class.java) {
        CaptureArchiveCodec().read(traversal)
    }
}

@Test
fun `read rejects checksum mismatch and incomplete raw pair`() {
    val corrupt = createValidArchive()
    rewriteEntry(corrupt, CaptureArchivePaths.SCREENSHOT, byteArrayOf(1, 2, 3))
    assertThrows(CaptureArchiveFormatException::class.java) {
        CaptureArchiveCodec().read(corrupt)
    }

    val incomplete = createArchiveWithRawZipButNoRawText()
    assertThrows(CaptureArchiveFormatException::class.java) {
        CaptureArchiveCodec().read(incomplete)
    }
}

@Test
fun `failed replacement preserves an existing target and removes temp files`() {
    val target = tempDir.resolve("capture.apinspect")
    Files.writeString(target, "existing")
    val codec = CaptureArchiveCodec(moveIntoPlace = { _, _ -> error("move failed") })

    assertThrows(IllegalStateException::class.java) {
        codec.write(target, validMetadata(), validPayload())
    }

    assertEquals("existing", Files.readString(target))
    assertEquals(setOf("capture.apinspect"), Files.list(tempDir).use {
        it.map { path -> path.fileName.toString() }.toList().toSet()
    })
}
```

Add package-private constructor injection:

```kotlin
internal class CaptureArchiveCodec(
    private val json: Json = defaultArchiveJson(),
    private val moveIntoPlace: (Path, Path) -> Unit = ::moveReplacing,
)
```

- [ ] **Step 2: Run the focused tests and verify failures**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests dev.agentperf.desktop.CaptureArchiveCodecTest
```

Expected: new validation and injected-move tests fail until all checks are implemented.

- [ ] **Step 3: Complete validation**

Implement the exact checks exercised above plus:

- source must be a regular file and no larger than `MAX_ARCHIVE_BYTES`;
- 1–16 non-directory entries;
- `manifest.json` must exist exactly once;
- format equals `CAPTURE_ARCHIVE_FORMAT`;
- archive version equals `CAPTURE_ARCHIVE_VERSION`;
- manifest entry paths are unique and safe;
- required snapshot and screenshot entries exist;
- actual ZIP entries equal manifest entries plus `manifest.json`;
- each entry stays within its per-path limit;
- cumulative bytes stay within `MAX_TOTAL_UNCOMPRESSED_BYTES`;
- actual byte size and SHA-256 equal the manifest;
- the old standalone `visible-window-views.zip` shape fails as an incomplete archive.

Move replacement must not delete or rename the existing target before the completed temporary
archive is ready. `Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)` provides the atomic
replacement on supported file systems; the fallback replaces only after ZIP creation succeeds.

- [ ] **Step 4: Run codec tests**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests dev.agentperf.desktop.CaptureArchiveCodecTest
```

Expected: PASS, including corruption and preservation tests.

- [ ] **Step 5: Commit archive hardening**

```bash
git add desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveCodec.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveCodecTest.kt
git commit -m "Reject unsafe or corrupted inspection archives before loading" \
  -m "Constraint: Imported files are untrusted and must not replace current UI state until fully verified." \
  -m "Rejected: Extracting archives to a temporary directory | Fixed-name in-memory reads avoid path traversal entirely." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: ./gradlew :desktop-app:test --tests dev.agentperf.desktop.CaptureArchiveCodecTest"
```

## Task 3: Add Protocol-Aware Archive Import and Offline Store State

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveService.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveServiceTest.kt`
- Modify: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorState.kt`
- Modify: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorStore.kt`
- Modify: `desktop-viewer/application/src/test/kotlin/dev/agentperf/application/InspectorStoreTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] **Step 1: Write failing service and store tests**

Create a valid PNG fixture with a complete 1×1 PNG byte sequence and add:

```kotlin
@Test
fun `service export then import restores snapshot screenshot and raw files`() {
    val path = tempDir.resolve("round-trip.apinspect")
    val snapshot = SampleSnapshots.dashboard
    val raw = CaptureRawArtifacts(
        zip = byteArrayOf(0x50, 0x4b, 0x03, 0x04),
        text = "raw text",
    )
    val service = CaptureArchiveService(
        archiveCodec = CaptureArchiveCodec(),
        protocolCodec = ProtocolCodec(supportedMajor = 1),
    )

    service.export(path, "0.1.2", snapshot, ONE_PIXEL_PNG, raw)
    val imported = service.import(path)

    assertEquals(snapshot, imported.snapshot)
    assertArrayEquals(ONE_PIXEL_PNG, imported.screenshotPng)
    assertArrayEquals(raw.zip, imported.rawArtifacts!!.zip)
    assertEquals(raw.text, imported.rawArtifacts!!.text)
}

@Test
fun `service rejects invalid png and unsupported protocol`() {
    val invalidPng = createArchiveWithScreenshot(byteArrayOf(1, 2, 3))
    assertThrows(CaptureArchiveFormatException::class.java) {
        service.import(invalidPng)
    }

    val unsupported = createArchiveWithProtocolMajor(2)
    assertThrows(UnsupportedProtocolVersionException::class.java) {
        service.import(unsupported)
    }
}
```

Add to `InspectorStoreTest.kt`:

```kotlin
@Test
fun `loading an archive publishes offline state and repairs selection`() {
    val store = InspectorStore().apply {
        load(SampleSnapshots.dashboard)
        selectNode("title")
    }
    val importedSnapshot = SampleSnapshots.dashboard.copy(
        root = SampleSnapshots.dashboard.root.children.first(),
    )

    store.loadArchive(importedSnapshot, byteArrayOf(1, 2, 3))

    assertEquals(ConnectionStatus.ARCHIVE, store.state.connectionStatus)
    assertEquals(importedSnapshot.root.id, store.state.selectedNodeId)
    assertTrue(store.state.analysis.metrics.nodeCount > 0)
}
```

Add to `InspectorPresenterTest.kt`:

```kotlin
@Test
fun `offline archive has a neutral localized connection status`() {
    val state = InspectorState(connectionStatus = ConnectionStatus.ARCHIVE)

    val english = InspectorPresenter.present(state, ViewerStrings.English)
    val chinese = InspectorPresenter.present(
        state,
        ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE),
    )

    assertEquals("Offline archive", english.connectionLabel)
    assertEquals("离线归档", chinese.connectionLabel)
    assertEquals(ConnectionTone.NEUTRAL, english.connectionTone)
}
```

- [ ] **Step 2: Run focused tests and verify failures**

Run:

```bash
cd desktop-viewer
./gradlew :application:test :desktop-app:test \
  --tests dev.agentperf.application.InspectorStoreTest \
  --tests dev.agentperf.desktop.CaptureArchiveServiceTest \
  --tests dev.agentperf.desktop.InspectorPresenterTest
```

Expected: compilation fails because the service, `loadArchive`, `ARCHIVE`, and localized label
do not exist.

- [ ] **Step 3: Implement `CaptureArchiveService`**

Use this module-level API:

```kotlin
internal data class ImportedCapture(
    val snapshot: LayoutSnapshot,
    val screenshotPng: ByteArray,
    val rawArtifacts: CaptureRawArtifacts?,
)

internal class CaptureArchiveService(
    private val archiveCodec: CaptureArchiveCodec,
    private val protocolCodec: ProtocolCodec,
) {
    fun export(
        target: Path,
        producerVersion: String,
        snapshot: LayoutSnapshot,
        screenshotPng: ByteArray,
        rawArtifacts: CaptureRawArtifacts?,
    ): CaptureArchiveWriteResult {
        validatePng(screenshotPng)
        return archiveCodec.write(
            target = target,
            metadata = CaptureArchiveMetadata(
                producerVersion = producerVersion,
                packageName = snapshot.packageName,
                capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                protocolMajor = snapshot.protocolVersion.major,
                protocolMinor = snapshot.protocolVersion.minor,
            ),
            payload = CaptureArchivePayload(
                snapshotJson = protocolCodec.encodeSnapshot(snapshot),
                screenshotPng = screenshotPng,
                rawArtifacts = rawArtifacts,
            ),
        )
    }

    fun import(source: Path): ImportedCapture {
        val document = archiveCodec.read(source)
        val snapshot = protocolCodec.decodeSnapshot(document.payload.snapshotJson)
        validatePng(document.payload.screenshotPng)
        require(snapshot.packageName == document.metadata.packageName) {
            "Archive package name does not match its snapshot"
        }
        require(snapshot.capturedAtEpochMillis == document.metadata.capturedAtEpochMillis) {
            "Archive capture time does not match its snapshot"
        }
        return ImportedCapture(
            snapshot = snapshot,
            screenshotPng = document.payload.screenshotPng,
            rawArtifacts = document.payload.rawArtifacts,
        )
    }
}
```

`validatePng` must require the eight-byte PNG signature, an IHDR chunk at bytes 12–15,
and positive big-endian width/height at bytes 16–23. Convert validation failures to
`CaptureArchiveFormatException("Screenshot is not a valid PNG")`.

- [ ] **Step 4: Add offline archive state**

Extend:

```kotlin
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ARCHIVE,
    ERROR,
}
```

Add `InspectorStore.loadArchive` with the same analysis and selection-repair behavior as
`loadCapture`, but set `ConnectionStatus.ARCHIVE`. Refactor the duplicated state construction
into one private method only if it makes both methods shorter and preserves their current
selection behavior.

In `InspectorPresenter`, map `ARCHIVE` to `strings.offlineArchive` and
`ConnectionTone.NEUTRAL`.

- [ ] **Step 5: Run focused tests**

Run:

```bash
cd desktop-viewer
./gradlew :application:test :desktop-app:test \
  --tests dev.agentperf.application.InspectorStoreTest \
  --tests dev.agentperf.desktop.CaptureArchiveServiceTest \
  --tests dev.agentperf.desktop.InspectorPresenterTest
```

Expected: PASS.

- [ ] **Step 6: Commit protocol and store integration**

```bash
git add desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorState.kt \
  desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorStore.kt \
  desktop-viewer/application/src/test/kotlin/dev/agentperf/application/InspectorStoreTest.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveService.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveServiceTest.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt
git commit -m "Restore exported inspections as explicit offline sessions" \
  -m "Constraint: Archive loading must reuse the current protocol decoder and analysis engine." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: focused application and desktop archive service tests"
```

## Task 4: Replace Advanced with a Localized File Menu

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveFileChooser.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveFileChooserTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`

- [ ] **Step 1: Write failing chooser, strings, and menu tests**

Add `CaptureArchiveFileChooserTest.kt`:

```kotlin
class CaptureArchiveFileChooserTest {
    @Test
    fun `export path preserves supported extension or appends selected default`() {
        assertEquals(
            Path.of("/tmp/capture.apinspect"),
            normalizeCaptureArchiveExportPath(Path.of("/tmp/capture"), "apinspect"),
        )
        assertEquals(
            Path.of("/tmp/capture.zip"),
            normalizeCaptureArchiveExportPath(Path.of("/tmp/capture.zip"), "apinspect"),
        )
        assertEquals(
            Path.of("/tmp/capture.apinspect"),
            normalizeCaptureArchiveExportPath(Path.of("/tmp/capture.apinspect"), "zip"),
        )
    }
}
```

Update language assertions to require:

```kotlin
assertEquals("文件", chinese.file)
assertEquals("File", english.file)
assertEquals("导入", chinese.importArchive)
assertEquals("Import", english.importArchive)
assertEquals("导出", chinese.exportArchive)
assertEquals("Export", english.exportArchive)
assertEquals("离线归档", chinese.offlineArchive)
assertEquals("Offline archive", english.offlineArchive)
```

Update `NativeViewerMenuBarTest` to assert:

```kotlin
assertEquals("文件", model.fileTitle)
assertEquals("导入", model.importLabel)
assertEquals("导出", model.exportLabel)
assertTrue(model.importEnabled)
assertTrue(model.exportEnabled)
```

Add cases proving export is disabled with no snapshot/screenshot and both actions are disabled
while an archive operation is running.

- [ ] **Step 2: Run focused tests and verify failures**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests dev.agentperf.desktop.CaptureArchiveFileChooserTest \
  --tests dev.agentperf.desktop.LanguagePreferenceTest \
  --tests dev.agentperf.desktop.NativeViewerMenuBarTest
```

Expected: compilation/assertion failures for the new chooser, strings, and menu fields.

- [ ] **Step 3: Implement the file chooser**

Create:

```kotlin
internal interface CaptureArchiveFileChooser {
    fun chooseImport(title: String): Path?
    fun chooseExport(title: String, initialFileName: String): Path?
}

internal class SwingCaptureArchiveFileChooser : CaptureArchiveFileChooser {
    override fun chooseImport(title: String): Path?
    override fun chooseExport(title: String, initialFileName: String): Path?
}

internal fun normalizeCaptureArchiveExportPath(
    selected: Path,
    defaultExtension: String,
): Path
```

Use two `FileNameExtensionFilter`s:

```kotlin
FileNameExtensionFilter("AgentPerf Inspector Capture (*.apinspect)", "apinspect")
FileNameExtensionFilter("ZIP Archive (*.zip)", "zip")
```

Import uses `showOpenDialog`; export uses `showSaveDialog`, initializes the file name, and
appends the selected filter extension only when the user supplied neither supported extension.

- [ ] **Step 4: Implement File menu strings and model**

Replace `advanced` and the legacy Visible Window Views export strings with:

```kotlin
val file: String get() = text("File", "文件")
val importArchive: String get() = text("Import", "导入")
val exportArchive: String get() = text("Export", "导出")
val chooseArchiveToImport: String
    get() = text("Choose archive to import", "选择要导入的归档")
val chooseArchiveExportFile: String
    get() = text("Choose export file", "选择导出文件")
val offlineArchive: String get() = text("Offline archive", "离线归档")
```

Change `NativeViewerMenuModel` to:

```kotlin
val fileTitle: String
val importLabel: String
val exportLabel: String
val importEnabled: Boolean
val exportEnabled: Boolean
```

Its constructor receives:

```kotlin
archiveOperationInProgress: Boolean,
canExportArchive: Boolean,
```

Set import enabled when not busy and export enabled when not busy and `canExportArchive`.
Render Import first, then Export, under `Menu(model.fileTitle)`. Add
`onImportArchive` and rename the export callback to `onExportArchive`.

- [ ] **Step 5: Run focused tests**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests dev.agentperf.desktop.CaptureArchiveFileChooserTest \
  --tests dev.agentperf.desktop.LanguagePreferenceTest \
  --tests dev.agentperf.desktop.NativeViewerMenuBarTest
```

Expected: PASS.

- [ ] **Step 6: Commit the File menu**

```bash
git add desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveFileChooser.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveFileChooserTest.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt
git commit -m "Expose portable capture workflows through the File menu" \
  -m "Constraint: Menu labels and file dialogs must follow the selected application language." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Tested: focused chooser, localization, and native menu tests"
```

## Task 5: Wire Import and Export into the Desktop Application

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveUiState.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveUiStateTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- Modify: `desktop-viewer/desktop-app/build.gradle.kts`

- [ ] **Step 1: Add failing operation-state and naming tests**

Create:

```kotlin
internal enum class CaptureArchiveOperation { IMPORT, EXPORT }

internal sealed interface CaptureArchiveUiState {
    data object Idle : CaptureArchiveUiState
    data class Working(val operation: CaptureArchiveOperation) : CaptureArchiveUiState
    data class Success(
        val operation: CaptureArchiveOperation,
        val path: Path,
        val rawArtifactsIncluded: Boolean = true,
    ) : CaptureArchiveUiState
    data class Failure(
        val operation: CaptureArchiveOperation,
        val message: String,
    ) : CaptureArchiveUiState
}
```

Test that every state preserves its operation/path/message and add a pure filename test:

```kotlin
@Test
fun `default export name sanitizes package and includes capture time`() {
    assertEquals(
        "dev.agentperf.sample-19700101-000001.apinspect",
        captureArchiveDefaultFileName("dev.agentperf.sample", 1_000L, ZoneOffset.UTC),
    )
}
```

- [ ] **Step 2: Run the tests and verify failure**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests dev.agentperf.desktop.CaptureArchiveUiStateTest
```

Expected: compilation fails because the state and filename helper do not exist.

- [ ] **Step 3: Add operation state, version property, and localized feedback**

Create `CaptureArchiveUiState.kt` with the tested contract.

In `build.gradle.kts`, extend the existing application JVM arguments:

```kotlin
jvmArgs(
    "-Dapple.awt.application.name=AgentPerf Inspector",
    "-Dagentperf.version=$appVersion",
)
```

Add a pure helper returning:

```kotlin
"${sanitizedPackage}-${yyyyMMdd-HHmmss}.apinspect"
```

where characters outside `[A-Za-z0-9._-]` become `_`. Use the system zone in production and
an injectable `ZoneId` in tests.

Add localized titles/messages for import/export success and failure and a distinct export
success message when raw Visible Window Views attachments could not be captured.

- [ ] **Step 4: Replace the legacy export setup in `DesktopViewerApp`**

At application initialization, replace the directory chooser/exporter/state with:

```kotlin
val archiveFileChooser = remember { SwingCaptureArchiveFileChooser() }
val captureArchiveService = remember(protocolCodec) {
    CaptureArchiveService(CaptureArchiveCodec(), protocolCodec)
}
var archiveUiState by remember {
    mutableStateOf<CaptureArchiveUiState>(CaptureArchiveUiState.Idle)
}
var importedRawArtifacts by remember {
    mutableStateOf<CaptureRawArtifacts?>(null)
}
```

Whenever automatic or manual device capture successfully calls `store.loadCapture`, also set:

```kotlin
importedRawArtifacts = null
```

This prevents raw attachments from a previously imported archive being associated with a new
live screenshot.

- [ ] **Step 5: Implement export coordination**

Build the export callback with this sequence:

```kotlin
val snapshot = state.snapshot ?: return
val screenshot = state.screenshotPng ?: return
val captureStatus = state.connectionStatus
val defaultName = captureArchiveDefaultFileName(
    snapshot.packageName,
    snapshot.capturedAtEpochMillis,
)
val target = archiveFileChooser.chooseExport(
    strings.chooseArchiveExportFile,
    defaultName,
) ?: return
archiveUiState = CaptureArchiveUiState.Working(CaptureArchiveOperation.EXPORT)
coroutineScope.launch {
    archiveUiState = try {
        val rawArtifacts = withContext(Dispatchers.IO) {
            if (captureStatus == ConnectionStatus.ARCHIVE) {
                importedRawArtifacts
            } else {
                runCatching {
                    val zip = deviceClient.dumpVisibleWindowViews()
                    CaptureRawArtifacts(zip, VisibleWindowViewsTextRenderer.render(zip))
                }.getOrNull()
            }
        }
        val result = withContext(Dispatchers.IO) {
            captureArchiveService.export(
                target = target,
                producerVersion = System.getProperty("agentperf.version", "development"),
                snapshot = snapshot,
                screenshotPng = screenshot.copyOf(),
                rawArtifacts = rawArtifacts,
            )
        }
        CaptureArchiveUiState.Success(
            operation = CaptureArchiveOperation.EXPORT,
            path = result.path,
            rawArtifactsIncluded = result.rawArtifactsIncluded,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        CaptureArchiveUiState.Failure(
            CaptureArchiveOperation.EXPORT,
            error.message ?: error.javaClass.simpleName,
        )
    }
}
```

Capture `snapshot`, screenshot copy, and connection status before launching so recomposition
cannot mix files from two frames.

- [ ] **Step 6: Implement import coordination**

Build the import callback with this sequence:

```kotlin
val source = archiveFileChooser.chooseImport(strings.chooseArchiveToImport) ?: return
autoScanEnabled = false
archiveUiState = CaptureArchiveUiState.Working(CaptureArchiveOperation.IMPORT)
coroutineScope.launch {
    try {
        val imported = withContext(Dispatchers.IO) {
            captureArchiveService.import(source)
        }
        store.loadArchive(imported.snapshot, imported.screenshotPng)
        state = store.state
        importedRawArtifacts = imported.rawArtifacts
        hierarchyTreeState = HierarchyTreeState()
        archiveUiState = CaptureArchiveUiState.Success(
            operation = CaptureArchiveOperation.IMPORT,
            path = source,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        archiveUiState = CaptureArchiveUiState.Failure(
            CaptureArchiveOperation.IMPORT,
            error.message ?: error.javaClass.simpleName,
        )
    }
}
```

Do not call `store.connecting()` for imports. Because `CaptureArchiveService.import` fully
validates before returning, a failure leaves the existing snapshot, screenshot, selection,
analysis, and optional raw attachments unchanged.

In the existing `LaunchedEffect(autoScanEnabled)` false branch, call `store.disconnected()` only
when `store.state.connectionStatus != ConnectionStatus.ARCHIVE`. This prevents the auto-scan
shutdown triggered by import from racing with and overwriting the successfully loaded offline
archive status.

- [ ] **Step 7: Connect menu enablement and result dialogs**

Pass:

```kotlin
archiveOperationInProgress = archiveUiState is CaptureArchiveUiState.Working
canExportArchive = state.snapshot != null && state.screenshotPng != null
```

and both callbacks to `NativeViewerMenuBar`.

Replace the legacy export dialog branch with one exhaustive `when` over
`CaptureArchiveUiState`. Use operation-specific localized titles and messages, show the actual
absolute file path, and mention missing raw attachments only for a successful export with
`rawArtifactsIncluded == false`.

- [ ] **Step 8: Run Desktop tests**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test
```

Expected: PASS.

- [ ] **Step 9: Commit application integration**

```bash
git add desktop-viewer/desktop-app/build.gradle.kts \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CaptureArchiveUiState.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveUiStateTest.kt
git commit -m "Let users reopen complete captures without a connected device" \
  -m "Constraint: Import must stop auto-scan and replace inspector state only after full archive validation." \
  -m "Confidence: high" \
  -m "Scope-risk: moderate" \
  -m "Tested: ./gradlew :desktop-app:test"
```

## Task 6: Remove the Legacy Directory Export and Verify the Full Product

**Files:**
- Delete: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ExportDirectoryChooser.kt`
- Delete: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporter.kt`
- Delete: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/VisibleWindowViewsExportUiState.kt`
- Delete: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporterTest.kt`
- Delete: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/VisibleWindowViewsExportUiStateTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/HeaderMenuPlacementTest.kt` if it references legacy menu labels.

- [ ] **Step 1: Search for legacy symbols**

Run:

```bash
rg -n "advanced|Advanced|高级|VisibleWindowViewsExporter|VisibleWindowViewsExportUiState|ExportDirectoryChooser|exportVisibleWindowViews|chooseExportDirectory" desktop-viewer
```

Expected: matches only in the legacy files and tests scheduled for deletion, plus any test
assertions that still require migration.

- [ ] **Step 2: Delete legacy implementation and migrate remaining assertions**

Delete the five legacy files. Replace remaining menu-placement assertions with File menu
assertions and ensure no production code offers the old two-file directory export.

- [ ] **Step 3: Run all tests**

Run:

```bash
cd desktop-viewer
./gradlew test
```

Expected: BUILD SUCCESSFUL with every module test passing.

- [ ] **Step 4: Run static checks and repository hygiene checks**

Run:

```bash
git diff --check
rg -n "VisibleWindowViewsExporter|VisibleWindowViewsExportUiState|ExportDirectoryChooser|exportVisibleWindowViews|chooseExportDirectory" desktop-viewer || true
git status --short
```

Expected:

- `git diff --check` prints nothing.
- the legacy-symbol search prints nothing.
- status contains only intended capture archive changes and the pre-existing untracked
  `.superpowers/` directory remains untouched.

- [ ] **Step 5: Perform the desktop round-trip smoke test**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:run
```

With one authorized Android device:

1. Use manual refresh or auto scan to load a frame.
2. Choose **File → Export**, save `smoke.apinspect`, and confirm the success dialog.
3. Turn off/disconnect the device.
4. Choose **File → Import**, open `smoke.apinspect`, and confirm the package, screenshot,
   hierarchy, selected details, findings, and **Offline archive** status appear.
5. Export the imported state as `round-trip.zip`.
6. Inspect both files with `unzip -l` and confirm manifest, snapshot, screenshot, raw ZIP, and
   raw TXT are present.

Expected: the imported view matches the exported frame and no device connection is required
after import.

- [ ] **Step 6: Commit cleanup and final verification**

```bash
git add -A desktop-viewer
git commit -m "Retire the incomplete Visible Window Views directory export" \
  -m "Constraint: File menu archives are now the sole supported export and import boundary." \
  -m "Rejected: Keeping both export paths | Duplicate workflows would produce incompatible user expectations." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Tested: ./gradlew test; git diff --check; desktop export-import smoke test"
```

## Final Self-Review

- Spec coverage:
  - File menu rename and Import/Export items: Task 4.
  - `.apinspect` and `.zip`: Tasks 1 and 4.
  - manifest, snapshot, screenshot, raw ZIP/TXT: Tasks 1–3.
  - offline parsing and display: Tasks 3 and 5.
  - auto-scan shutdown and atomic UI replacement: Task 5.
  - archive limits, checksums, paths, versions, and PNG validation: Tasks 2–3.
  - localization and user feedback: Tasks 3–5.
  - legacy export removal and full verification: Task 6.
- Type consistency:
  - `CaptureRawArtifacts`, `CaptureArchivePayload`, `CaptureArchiveMetadata`, and
    `CaptureArchiveWriteResult` originate in Task 1 and retain the same signatures.
  - `ImportedCapture` originates in Task 3 and is consumed unchanged in Task 5.
  - `CaptureArchiveUiState` and `CaptureArchiveOperation` originate and are consumed in Task 5.
- No extraction of untrusted entries occurs; all imported content is bounded and read by fixed
  entry name.
