# Visible Window View Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a localized Advanced menu that lets the user choose a directory and export both the raw `dump-visible-window-views` ZIP and a complete readable TXT representation.

**Architecture:** The ADB gateway selects the single authorized device, captures the binary dump, and renders all ZIP entries through a reusable decoder. The desktop module owns directory selection, rollback-safe two-file output, asynchronous Compose state, and localized success or failure dialogs.

**Tech Stack:** Kotlin/JVM 17, Compose Desktop Material 3, Kotlin coroutines, Java NIO, ADB, JUnit 5.

---

## File Structure

- Modify `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/LiveDeviceClient.kt`
  to expose single-device visible-window dump capture.
- Modify `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParser.kt`
  to share decoded document data with a complete text renderer.
- Modify `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/LiveDeviceClientTest.kt`
  to lock command execution, selection, and empty-output failures.
- Modify `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParserTest.kt`
  to lock multi-window text rendering and malformed-entry isolation.
- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporter.kt`
  for rollback-safe two-file output.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporterTest.kt`
  for output, replacement, rollback, and cleanup behavior.
- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ExportDirectoryChooser.kt`
  for the user-selected destination boundary.
- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/AdvancedMenu.kt`
  for the menu UI and pure menu-state model.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/AdvancedMenuTest.kt`
  for menu labels and enabled state.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
  for English and Simplified Chinese export strings.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`
  to lock localization.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
  to coordinate chooser, background export, menu state, and result dialog.

### Task 1: Capture raw Visible Window View ZIP

**Files:**
- Modify: `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/LiveDeviceClientTest.kt`
- Modify: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/LiveDeviceClient.kt`

- [ ] **Step 1: Write failing capture tests**

Add tests that use `RecordingProcessRunner`:

```kotlin
@Test
fun `visible window dump uses selected physical device and preserves binary output`() {
    val expected = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    val runner = recordingRunner(
        devices = "List of devices attached\nphysical-1 device product:test\n",
        processResults = mapOf(
            listOf("-s", "physical-1", "exec-out", "cmd", "window", "dump-visible-window-views") to
                ProcessResult(0, "", "", expected),
        ),
    )

    assertArrayEquals(expected, LiveDeviceClient(runner).dumpVisibleWindowViews())
}

@Test
fun `visible window dump rejects empty command output`() {
    val runner = recordingRunner(
        devices = "List of devices attached\nphysical-1 device product:test\n",
        processResults = mapOf(
            listOf("-s", "physical-1", "exec-out", "cmd", "window", "dump-visible-window-views") to
                ProcessResult(0, "", "", byteArrayOf()),
        ),
    )

    assertThrows<VisibleWindowViewsUnavailableException> {
        LiveDeviceClient(runner).dumpVisibleWindowViews()
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :adb-gateway:test --tests 'dev.agentperf.adb.LiveDeviceClientTest'
```

Expected: compilation fails because `dumpVisibleWindowViews` and
`VisibleWindowViewsUnavailableException` do not exist.

- [ ] **Step 3: Implement raw dump capture**

Add:

```kotlin
class VisibleWindowViewsUnavailableException(message: String) : IllegalStateException(message)

fun dumpVisibleWindowViews(): ByteArray {
    val device = selectDevice()
    val result = checkedRun(AdbCommandFactory.dumpVisibleWindowViews(device.serial))
    if (result.stdoutBytes.isEmpty()) {
        throw VisibleWindowViewsUnavailableException("Visible Window View dump is empty")
    }
    return result.stdoutBytes
}
```

Keep `selectDevice()` as the single source of the physical-device preference and
authorized-device error.

- [ ] **Step 4: Run the capture tests and verify GREEN**

Run the Task 1 command again.

Expected: all `LiveDeviceClientTest` tests pass.

### Task 2: Render every ZIP window as readable text

**Files:**
- Modify: `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParserTest.kt`
- Modify: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParser.kt`

- [ ] **Step 1: Extend the fixture and write failing renderer tests**

Expose fixture helpers for a ZIP with a valid app entry, an empty wallpaper
entry, and a malformed status-bar entry. Add:

```kotlin
@Test
fun `text renderer includes every window and complete view properties`() {
    val text = VisibleWindowViewsTextRenderer.render(
        EncodedHierarchyFixture.multiWindowZip(),
    )

    assertTrue(text.contains("Window count: 3"))
    assertTrue(text.contains("ImageWallpaper"))
    assertTrue(text.contains("No hierarchy payload was supplied"))
    assertTrue(text.contains("com.codemx.ui.RealRootLayout"))
    assertTrue(text.contains("drawing:elevation: 8"))
    assertTrue(text.contains("com.codemx.ui.RealTitleView"))
    assertTrue(text.contains("Parse error:"))
    assertTrue(text.contains("SUMMARY: parsed 1 of 3 windows"))
}

@Test
fun `text renderer rejects non zip input`() {
    assertThrows<IllegalArgumentException> {
        VisibleWindowViewsTextRenderer.render("not a zip".toByteArray())
    }
}
```

- [ ] **Step 2: Run the renderer tests and verify RED**

Run:

```bash
./gradlew :adb-gateway:test --tests 'dev.agentperf.adb.VisibleWindowHierarchyParserTest'
```

Expected: compilation fails because `VisibleWindowViewsTextRenderer` and the
multi-window fixture do not exist.

- [ ] **Step 3: Separate decoding from ViewNode projection**

Change `EncodedHierarchyDecoder.decode()` to build an internal document:

```kotlin
private data class DecodedHierarchy(
    val prefix: Map<String, Any>,
    val root: EncodedMap,
    val propertyNames: Map<Short, String>,
) {
    fun toViewNode(): ViewNode = root.toViewNode(
        propertyNames = propertyNames,
        path = "root",
        parentLeft = (prefix["window:left"] as? Number)?.toInt() ?: 0,
        parentTop = (prefix["window:top"] as? Number)?.toInt() ?: 0,
        parentScrollX = 0,
        parentScrollY = 0,
        parentVisible = true,
    )
}
```

Keep `VisibleWindowHierarchyParser.parse()` behavior unchanged by calling
`decodeDocument().toViewNode()`.

- [ ] **Step 4: Implement complete multi-window text rendering**

Add public API:

```kotlin
object VisibleWindowViewsTextRenderer {
    fun render(zipBytes: ByteArray): String
}
```

The renderer must:

1. validate ZIP structure;
2. preserve archive entry order;
3. write each entry name and byte count;
4. describe zero-byte entries without failure;
5. decode non-empty entries independently;
6. include prefix properties, node path, implementation class, ID, local
   bounds, visibility, all scalar properties, and nested maps;
7. omit child maps from the property list and render them recursively as tree
   nodes;
8. record an entry-local parse error and continue;
9. finish with `SUMMARY: parsed X of Y windows`.

- [ ] **Step 5: Run parser and renderer tests and verify GREEN**

Run the Task 2 command.

Expected: all parser and renderer tests pass, and existing `ViewNode` assertions
remain unchanged.

### Task 3: Write ZIP and TXT safely as one export

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporterTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/VisibleWindowViewsExporter.kt`

- [ ] **Step 1: Write failing filesystem tests**

Create tests using `@TempDir`:

```kotlin
@Test
fun `export writes raw zip and rendered text`() {
    val zip = byteArrayOf(1, 2, 3)
    val exporter = VisibleWindowViewsExporter(
        captureDump = { zip },
        renderText = { "decoded" },
    )

    val result = exporter.export(tempDir)

    assertArrayEquals(zip, Files.readAllBytes(result.zipPath))
    assertEquals("decoded", Files.readString(result.textPath))
}

@Test
fun `render failure leaves existing final files unchanged and removes temps`() {
    Files.writeString(tempDir.resolve("visible-window-views.zip"), "old zip")
    Files.writeString(tempDir.resolve("visible-window-views.txt"), "old txt")
    val exporter = VisibleWindowViewsExporter(
        captureDump = { byteArrayOf(1) },
        renderText = { error("decode failed") },
    )

    assertThrows<IllegalStateException> { exporter.export(tempDir) }
    assertEquals("old zip", Files.readString(tempDir.resolve("visible-window-views.zip")))
    assertEquals("old txt", Files.readString(tempDir.resolve("visible-window-views.txt")))
    assertEquals(
        setOf("visible-window-views.zip", "visible-window-views.txt"),
        Files.list(tempDir).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() },
    )
}
```

Also test replacement of both existing files and a non-directory destination.

- [ ] **Step 2: Run exporter tests and verify RED**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.VisibleWindowViewsExporterTest'
```

Expected: compilation fails because the exporter types do not exist.

- [ ] **Step 3: Implement exporter with temporary files and rollback**

Create:

```kotlin
internal data class VisibleWindowViewsExportResult(
    val zipPath: Path,
    val textPath: Path,
)

internal class VisibleWindowViewsExporter(
    private val captureDump: () -> ByteArray,
    private val renderText: (ByteArray) -> String,
) {
    fun export(directory: Path): VisibleWindowViewsExportResult
}
```

Implementation order:

1. require `Files.isDirectory(directory)`;
2. capture ZIP and render TXT fully before changing final files;
3. write both temporary files in the destination directory;
4. move existing finals to temporary backups;
5. move both new files to final names with `REPLACE_EXISTING`;
6. on any move failure, delete newly moved finals and restore backups;
7. always delete remaining temporary and backup files.

- [ ] **Step 4: Run exporter tests and verify GREEN**

Run the Task 3 command.

Expected: all exporter tests pass.

### Task 4: Add localized Advanced menu model and directory chooser

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/AdvancedMenuTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/AdvancedMenu.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ExportDirectoryChooser.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`

- [ ] **Step 1: Write failing menu and localization tests**

Add:

```kotlin
@Test
fun `advanced menu exposes one export child item`() {
    val strings = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)
    assertEquals("高级", strings.advanced)
    assertEquals("导出 Visible Window Views…", AdvancedMenuModel(strings).exportLabel)
}

@Test
fun `export strings follow selected language`() {
    val english = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH)
    val chinese = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)
    assertEquals("Advanced", english.advanced)
    assertEquals("Export Visible Window Views…", english.exportVisibleWindowViews)
    assertEquals("导出成功", chinese.visibleWindowViewsExportSucceededTitle)
    assertEquals("导出失败", chinese.visibleWindowViewsExportFailedTitle)
}
```

- [ ] **Step 2: Run menu tests and verify RED**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.AdvancedMenuTest' \
  --tests 'dev.agentperf.desktop.LanguagePreferenceTest'
```

Expected: compilation fails for missing menu and string properties.

- [ ] **Step 3: Implement pure model and localized strings**

Add `ViewerStrings` properties for the menu item, chooser title, progress,
success, and failure messages. Create:

```kotlin
internal data class AdvancedMenuModel(
    val title: String,
    val exportLabel: String,
    val exportEnabled: Boolean,
) {
    constructor(strings: ViewerStrings, exportInProgress: Boolean = false) : this(
        title = strings.advanced,
        exportLabel = strings.exportVisibleWindowViews,
        exportEnabled = !exportInProgress,
    )
}
```

- [ ] **Step 4: Implement the chooser boundary**

Create:

```kotlin
internal fun interface ExportDirectoryChooser {
    fun chooseDirectory(title: String): Path?
}

internal class SwingExportDirectoryChooser : ExportDirectoryChooser {
    override fun chooseDirectory(title: String): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
        } else {
            null
        }
    }
}
```

Create `AdvancedMenu` as a header-style dropdown matching
`ViewerActionDropdown`, with one `DropdownMenuItem`. Dismiss the menu before
calling `onExport`.

- [ ] **Step 5: Run menu tests and verify GREEN**

Run the Task 4 command.

Expected: all menu and localization tests pass.

### Task 5: Wire asynchronous export and result feedback

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/VisibleWindowViewsExportUiStateTest.kt`

- [ ] **Step 1: Write failing UI-state tests**

Create a pure state model test:

```kotlin
@Test
fun `export state reports success paths and failure message`() {
    val success = VisibleWindowViewsExportUiState.Success(Path.of("/tmp/output"))
    val failure = VisibleWindowViewsExportUiState.Failure("adb failed")

    assertEquals(Path.of("/tmp/output"), success.directory)
    assertEquals("adb failed", failure.message)
}
```

- [ ] **Step 2: Run state test and verify RED**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.VisibleWindowViewsExportUiStateTest'
```

Expected: compilation fails because the state model does not exist.

- [ ] **Step 3: Add state and application coordination**

Add:

```kotlin
internal sealed interface VisibleWindowViewsExportUiState {
    data object Idle : VisibleWindowViewsExportUiState
    data object Exporting : VisibleWindowViewsExportUiState
    data class Success(val directory: Path) : VisibleWindowViewsExportUiState
    data class Failure(val message: String) : VisibleWindowViewsExportUiState
}
```

In `DesktopViewerApp`, remember one chooser, one exporter using
`deviceClient::dumpVisibleWindowViews` and
`VisibleWindowViewsTextRenderer::render`, and one UI state. On menu activation:

1. show the chooser;
2. return without feedback if cancelled;
3. set `Exporting`;
4. call `exporter.export(directory)` inside `withContext(Dispatchers.IO)`;
5. set `Success` or localized `Failure`.

- [ ] **Step 4: Add menu and dialogs to the header**

Pass `exportInProgress` and `onExportVisibleWindowViews` to `Header`. Place
`AdvancedMenu` immediately after `ViewerActionDropdown`, separated by a small
spacer. Use a Material 3 `AlertDialog` for success and failure; success names
both generated files and the chosen directory. Dismissing the dialog returns
state to `Idle`.

- [ ] **Step 5: Run desktop tests and verify GREEN**

Run:

```bash
./gradlew :desktop-app:test
```

Expected: all desktop tests pass.

### Task 6: Full verification and manual smoke test

**Files:**
- No production file changes expected.

- [ ] **Step 1: Run the complete test suite**

Run:

```bash
./gradlew test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` with no failing tasks.

- [ ] **Step 2: Launch the desktop application**

Run:

```bash
./gradlew :desktop-app:run
```

Expected: the header displays localized **Advanced / 高级** beside **Actions /
操作**.

- [ ] **Step 3: Exercise the export against the connected device**

Choose the repository root as the destination and verify:

```bash
unzip -t visible-window-views.zip
grep -F 'VIEW TREE AND PROPERTIES' visible-window-views.txt
grep -F 'SUMMARY:' visible-window-views.txt
```

Expected: ZIP validation reports no errors and both TXT markers exist.

- [ ] **Step 4: Review repository state**

Run:

```bash
git diff --check
git status --short
```

Expected: only intentional source, test, and documentation changes are present;
user-generated `visible-window-views.zip` and `.txt` remain untracked and are
not committed.
