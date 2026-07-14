# Profile Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the versioned Canonical Android Profile, SQLite v2 persistence, cancellable generation-safe queries, and immutable view snapshots that all Firefox-parity stages build upon.

**Architecture:** Keep Simpleperf normalization as an input adapter and extend the canonical record stream with source provenance, lifecycle, category, marker, counter, slice, screenshot, and clock records. Persist facts in SQLite v2, expose immutable projection snapshots through a query coordinator, and preserve the existing report UI through a compatibility facade while `ReportController` is decomposed.

**Tech Stack:** Kotlin 2.4, JVM 21, Compose Multiplatform Desktop 1.11.1, kotlinx-coroutines 1.11.0, SQLite JDBC 3.53.1.0, kotlin.test, Gradle composite build.

## Global Constraints

- Simpleperf remains the primary source for CPU samples and call stacks.
- No React, WebView, or embedded Firefox Profiler runtime is introduced.
- No browser-only event is fabricated.
- Existing `.apsession` packages remain readable after migration.
- Failed migration leaves the source session unchanged and readable through the legacy path.
- Compose panels consume immutable snapshots and never query SQLite directly.
- Background queries are cancellable and stale generations cannot overwrite current state.
- No new dependency is added without a separate justified decision.
- All warnings remain errors; ktlint, detekt, tests, and `checkAll` must pass.
- Commits follow the repository Lore Commit Protocol.

## Plan Suite Boundary

This plan implements Stage 1 only. The remaining approved scope receives separate plans after this one is integrated:

1. `core-analysis-parity`: multi-track timeline, committed ranges, complete trees/graphs, filters, and stack transforms.
2. `markers-and-counters`: marker schemas, charts/tables, counters, memory, and application markers.
3. `android-enrichment`: Perfetto alignment, sched, Binder, FrameTimeline, GC/JIT, screenshots, and network.
4. `symbols-and-source`: native/Java/ART/JIT symbolication, mapping, source, and disassembly.
5. `comparison-and-productization`: compare, analysis-state persistence, complete round trips, accessibility, i18n, packaging, and performance.

## File Structure

New files have one responsibility each:

- `profile-model/.../ProfileIdentity.kt`: stable source, process, thread, category, and clock identifiers.
- `profile-model/.../ProfileFacts.kt`: canonical fact value types.
- `profile-model/.../ProfileRecords.kt`: the sealed ingestion record contract.
- `storage-sqlite/.../SQLiteSchemaV2.kt`: only v1-to-v2 schema statements.
- `storage-sqlite/.../ProfileProjectionModels.kt`: immutable storage-facing projections.
- `storage-sqlite/.../SQLiteProfileProjectionQueries.kt`: projection query implementation.
- `application/.../ProfileWorkspaceState.kt`: user-owned workspace state and generation.
- `application/.../ProfileQueryCoordinator.kt`: cancellation, dispatch, and stale-result rejection.
- `application/.../ProfileWorkspaceController.kt`: public application facade.

Existing files retain their responsibilities:

- `NormalizedProfile.kt` remains a compatibility surface for existing parser call sites until adapters migrate.
- `SQLiteProfileRecordWriter.kt` remains the streaming fact writer.
- `ReportController.kt` becomes a deprecated compatibility facade rather than owning query execution.

---

### Task 1: Canonical Identity and Provenance Types

**Files:**
- Create: `profile-model/src/main/kotlin/com/androidperformancestudio/model/ProfileIdentity.kt`
- Test: `profile-model/src/test/kotlin/com/androidperformancestudio/model/ProfileIdentityTest.kt`

**Interfaces:**
- Consumes: no new interfaces.
- Produces: `ProfileSourceId`, `ProfileSourceKind`, `ProfileProcessKey`, `ProfileThreadKey`, `ProfileCategory`, `ProfileClockDomain`, and `ProfileTimePoint`.

- [ ] **Step 1: Write the failing identity validation tests**

```kotlin
class ProfileIdentityTest {
    @Test fun `time point requires a non-negative error bound`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileTimePoint(ProfileClockDomain("simpleperf"), 10, -1)
        }
    }

    @Test fun `thread identity includes its source and process`() {
        val source = ProfileSourceId("simpleperf")
        assertNotEquals(
            ProfileThreadKey(source, ProfileProcessKey(source, 10), 20),
            ProfileThreadKey(source, ProfileProcessKey(source, 11), 20),
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify the types are unresolved**

Run: `./gradlew :profile-model:test --tests '*ProfileIdentityTest'`

Expected: FAIL during Kotlin compilation with unresolved `ProfileTimePoint` and related symbols.

- [ ] **Step 3: Implement the identity contract**

```kotlin
@JvmInline value class ProfileSourceId(val value: String) {
    init { require(value.isNotBlank()) { "source id must not be blank" } }
}

enum class ProfileSourceKind { SIMPLEPERF, PERFETTO, APP_INSTRUMENTATION, IMPORTED }

data class ProfileProcessKey(val sourceId: ProfileSourceId, val processId: Int)

data class ProfileThreadKey(
    val sourceId: ProfileSourceId,
    val process: ProfileProcessKey,
    val threadId: Int,
)

data class ProfileCategory(val name: String, val subcategory: String? = null) {
    init { require(name.isNotBlank()) { "category name must not be blank" } }
}

@JvmInline value class ProfileClockDomain(val value: String) {
    init { require(value.isNotBlank()) { "clock domain must not be blank" } }
}

data class ProfileTimePoint(
    val clockDomain: ProfileClockDomain,
    val timestampNanos: Long,
    val errorBoundNanos: Long = 0,
) {
    init { require(errorBoundNanos >= 0) { "error bound must be non-negative" } }
}
```

- [ ] **Step 4: Run model tests**

Run: `./gradlew :profile-model:test`

Expected: PASS.

- [ ] **Step 5: Commit the identity contract**

```bash
git add profile-model/src/main/kotlin/com/androidperformancestudio/model/ProfileIdentity.kt profile-model/src/test/kotlin/com/androidperformancestudio/model/ProfileIdentityTest.kt
git commit -m "Preserve profile identity across heterogeneous data sources" -m "Confidence: high
Scope-risk: narrow
Tested: ./gradlew :profile-model:test"
```

### Task 2: Canonical Fact and Record Contracts

**Files:**
- Create: `profile-model/src/main/kotlin/com/androidperformancestudio/model/ProfileFacts.kt`
- Create: `profile-model/src/main/kotlin/com/androidperformancestudio/model/ProfileRecords.kt`
- Modify: `profile-model/src/main/kotlin/com/androidperformancestudio/model/NormalizedProfile.kt`
- Test: `profile-model/src/test/kotlin/com/androidperformancestudio/model/CanonicalProfileRecordTest.kt`

**Interfaces:**
- Consumes: identity types from Task 1 and existing `ProfileFrame`, `ProfileExecutionType`, and `ProfileUnwindError`.
- Produces: `CanonicalProfileRecord` and facts for source, process, thread, sample, category, marker, counter, slice, screenshot, context switch, and quality evidence.

- [ ] **Step 1: Write exhaustive record-contract tests**

```kotlin
class CanonicalProfileRecordTest {
    @Test fun `sample retains source clock cpu and category`() {
        val sample = canonicalSampleFixture()
        assertEquals(ProfileSourceId("simpleperf"), sample.sourceId)
        assertEquals(ProfileClockDomain("monotonic"), sample.time.clockDomain)
        assertEquals(4, sample.cpuCore)
        assertEquals(ProfileCategory("Native", "System"), sample.category)
    }

    @Test fun `interval facts reject reversed ranges`() {
        assertFailsWith<IllegalArgumentException> {
            ProfileSliceFact(threadFixture(), time(20), time(10), "Binder", null)
        }
    }
}
```

- [ ] **Step 2: Run the test and verify it fails on unresolved canonical records**

Run: `./gradlew :profile-model:test --tests '*CanonicalProfileRecordTest'`

Expected: FAIL during Kotlin compilation.

- [ ] **Step 3: Implement focused fact types**

```kotlin
data class ProfileSourceFact(
    val id: ProfileSourceId,
    val kind: ProfileSourceKind,
    val clockDomain: ProfileClockDomain,
    val validFromNanos: Long?,
    val validUntilNanosExclusive: Long?,
)

data class ProfileProcessFact(val key: ProfileProcessKey, val name: String?, val start: ProfileTimePoint?, val end: ProfileTimePoint?)
data class ProfileThreadFact(val key: ProfileThreadKey, val name: String, val start: ProfileTimePoint?, val end: ProfileTimePoint?)

data class ProfileSampleFact(
    val sourceId: ProfileSourceId,
    val time: ProfileTimePoint,
    val thread: ProfileThreadKey,
    val eventType: String,
    val eventCount: Long,
    val cpuCore: Int?,
    val onCpu: Boolean?,
    val category: ProfileCategory?,
    val frames: List<ProfileFrame>,
    val unwindError: ProfileUnwindError?,
)

data class ProfileMarkerFact(val sourceId: ProfileSourceId, val thread: ProfileThreadKey?, val start: ProfileTimePoint, val end: ProfileTimePoint?, val schema: String, val name: String, val payloadJson: String)
data class ProfileCounterFact(val sourceId: ProfileSourceId, val time: ProfileTimePoint, val name: String, val unit: String, val value: Double)
data class ProfileSliceFact(val sourceId: ProfileSourceId, val thread: ProfileThreadKey?, val start: ProfileTimePoint, val end: ProfileTimePoint, val name: String, val category: ProfileCategory?) {
    init { require(end.timestampNanos >= start.timestampNanos) { "slice end must not precede start" } }
}
data class ProfileScreenshotFact(val sourceId: ProfileSourceId, val time: ProfileTimePoint, val artifactPath: String)
```

- [ ] **Step 4: Implement the sealed record stream and legacy adapter**

```kotlin
sealed interface CanonicalProfileRecord {
    data class Source(val value: ProfileSourceFact) : CanonicalProfileRecord
    data class Process(val value: ProfileProcessFact) : CanonicalProfileRecord
    data class Thread(val value: ProfileThreadFact) : CanonicalProfileRecord
    data class Sample(val value: ProfileSampleFact) : CanonicalProfileRecord
    data class Marker(val value: ProfileMarkerFact) : CanonicalProfileRecord
    data class Counter(val value: ProfileCounterFact) : CanonicalProfileRecord
    data class Slice(val value: ProfileSliceFact) : CanonicalProfileRecord
    data class Screenshot(val value: ProfileScreenshotFact) : CanonicalProfileRecord
    data class Legacy(val value: NormalizedProfileRecord) : CanonicalProfileRecord
}

fun NormalizedProfileRecord.asCanonical(): CanonicalProfileRecord = CanonicalProfileRecord.Legacy(this)
```

- [ ] **Step 5: Run the complete model suite**

Run: `./gradlew :profile-model:test`

Expected: PASS.

- [ ] **Step 6: Commit canonical facts**

```bash
git add profile-model/src/main/kotlin/com/androidperformancestudio/model profile-model/src/test/kotlin/com/androidperformancestudio/model
git commit -m "Retain Android profiler facts beyond sampled stacks" -m "Constraint: Existing normalized parser records remain source-compatible.
Confidence: high
Scope-risk: moderate
Tested: ./gradlew :profile-model:test"
```

### Task 3: SQLite v2 Transactional Migration

**Files:**
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteSchemaV2.kt`
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteSchema.kt`
- Test: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteSchemaMigrationTest.kt`

**Interfaces:**
- Consumes: existing v1 schema.
- Produces: schema version `2` with `profile_source`, lifecycle/category columns, marker, counter, slice, screenshot, and clock-alignment tables.

- [ ] **Step 1: Write migration rollback and preservation tests**

```kotlin
@Test fun `v1 database migrates to v2 without changing sample totals`() = withVersionOneDatabase { path ->
    SQLiteSampleStore.open(path).use { store ->
        assertEquals(2L, store.sampleCount())
        assertEquals(2, pragmaUserVersion(path))
    }
}

@Test fun `failed migration rolls back every v2 table`() = withInjectedMigrationFailure { connection ->
    assertFails { SQLiteSchema.migrate(connection) }
    assertEquals(1, connection.userVersionForTest())
    assertFalse(connection.tableExistsForTest("profile_marker"))
}
```

- [ ] **Step 2: Run the migration test and verify the expected-version assertion fails**

Run: `./gradlew :storage-sqlite:test --tests '*SQLiteSchemaMigrationTest'`

Expected: FAIL because the supported schema version is still `1`.

- [ ] **Step 3: Add the v2 statements**

```kotlin
internal object SQLiteSchemaV2 {
    val statements = listOf(
        "CREATE TABLE profile_source (source_id TEXT PRIMARY KEY, kind TEXT NOT NULL, clock_domain TEXT NOT NULL, valid_from_nanos INTEGER, valid_until_nanos INTEGER)",
        "ALTER TABLE process ADD COLUMN source_id TEXT REFERENCES profile_source(source_id)",
        "ALTER TABLE process ADD COLUMN start_nanos INTEGER",
        "ALTER TABLE process ADD COLUMN end_nanos INTEGER",
        "ALTER TABLE thread ADD COLUMN start_nanos INTEGER",
        "ALTER TABLE thread ADD COLUMN end_nanos INTEGER",
        "ALTER TABLE sample ADD COLUMN cpu_core INTEGER",
        "ALTER TABLE sample ADD COLUMN on_cpu INTEGER",
        "ALTER TABLE sample ADD COLUMN category_name TEXT",
        "ALTER TABLE sample ADD COLUMN subcategory_name TEXT",
        "CREATE TABLE profile_marker (marker_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, thread_id INTEGER, start_nanos INTEGER NOT NULL, end_nanos INTEGER, schema_name TEXT NOT NULL, name TEXT NOT NULL, payload_json TEXT NOT NULL)",
        "CREATE INDEX profile_marker_time ON profile_marker(start_nanos, end_nanos)",
        "CREATE TABLE profile_counter (counter_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, timestamp_nanos INTEGER NOT NULL, name TEXT NOT NULL, unit TEXT NOT NULL, value REAL NOT NULL)",
        "CREATE INDEX profile_counter_name_time ON profile_counter(name, timestamp_nanos)",
        "CREATE TABLE profile_slice (slice_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, thread_id INTEGER, start_nanos INTEGER NOT NULL, end_nanos INTEGER NOT NULL, name TEXT NOT NULL, category_name TEXT, subcategory_name TEXT)",
        "CREATE INDEX profile_slice_thread_time ON profile_slice(thread_id, start_nanos, end_nanos)",
        "CREATE TABLE profile_screenshot (screenshot_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, timestamp_nanos INTEGER NOT NULL, artifact_path TEXT NOT NULL)",
        "CREATE TABLE clock_alignment (alignment_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, source_nanos INTEGER NOT NULL, canonical_nanos INTEGER NOT NULL, error_bound_nanos INTEGER NOT NULL)",
    )
}
```

- [ ] **Step 4: Apply v2 in one transaction**

```kotlin
internal object SQLiteSchema {
    const val VERSION = 2

    fun migrate(connection: Connection) {
        val version = connection.userVersion()
        require(version <= VERSION) { "Database schema version $version is newer than supported version $VERSION" }
        if (version == 0) migrateToVersionOne(connection)
        if (connection.userVersion() == 1) migrateToVersionTwo(connection)
    }

    private fun migrateToVersionTwo(connection: Connection) = inTransaction(connection) {
        SQLiteSchemaV2.statements.forEach { sql -> connection.createStatement().use { it.execute(sql) } }
        connection.createStatement().use { it.execute("PRAGMA user_version=2") }
    }
}
```

- [ ] **Step 5: Run storage tests**

Run: `./gradlew :storage-sqlite:test`

Expected: PASS, including preserved v1 sample totals and rollback behavior.

- [ ] **Step 6: Commit the migration**

```bash
git add storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteSchema.kt storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteSchemaV2.kt storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteSchemaMigrationTest.kt
git commit -m "Keep existing sessions readable as profile facts expand" -m "Constraint: Version 1 data must survive migration unchanged.
Confidence: high
Scope-risk: moderate
Tested: ./gradlew :storage-sqlite:test"
```

### Task 4: Stream Canonical Records into SQLite v2

**Files:**
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteProfileRecordWriter.kt`
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteSampleStore.kt`
- Test: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteCanonicalProfileStoreTest.kt`

**Interfaces:**
- Consumes: `CanonicalProfileRecord` from Task 2 and schema v2 from Task 3.
- Produces: `SQLiteSampleStore.importCanonicalRecords(records, batchSize)`.

- [ ] **Step 1: Write round-trip persistence tests for each new fact family**

```kotlin
@Test fun `canonical import persists provenance and time-series facts`() = withStore { store ->
    val result = store.importCanonicalRecords(canonicalFixtureRecords(), batchSize = 2)
    assertEquals(8L, result.importedRecords)
    assertEquals(listOf("simpleperf"), store.sourceIdsForTest())
    assertEquals(1L, store.markerCountForTest())
    assertEquals(1L, store.counterCountForTest())
    assertEquals(1L, store.sliceCountForTest())
    assertEquals(1L, store.screenshotCountForTest())
}
```

- [ ] **Step 2: Run the focused test and verify `importCanonicalRecords` is unresolved**

Run: `./gradlew :storage-sqlite:test --tests '*SQLiteCanonicalProfileStoreTest'`

Expected: FAIL during compilation.

- [ ] **Step 3: Add the canonical import entry point**

```kotlin
fun importCanonicalRecords(records: Sequence<CanonicalProfileRecord>, batchSize: Int = DEFAULT_BATCH_SIZE): ProfileImportResult =
    connection.inTransactionResult {
        SQLiteProfileRecordWriter(connection, batchSize).use { writer ->
            records.forEach(writer::addCanonical)
            writer.finish()
        }
    }
```

- [ ] **Step 4: Dispatch canonical records without changing legacy dispatch**

```kotlin
fun addCanonical(record: CanonicalProfileRecord) {
    when (record) {
        is CanonicalProfileRecord.Source -> insertSource(record.value)
        is CanonicalProfileRecord.Process -> insertProcess(record.value)
        is CanonicalProfileRecord.Thread -> insertCanonicalThread(record.value)
        is CanonicalProfileRecord.Sample -> insertCanonicalSample(record.value)
        is CanonicalProfileRecord.Marker -> insertMarker(record.value)
        is CanonicalProfileRecord.Counter -> insertCounter(record.value)
        is CanonicalProfileRecord.Slice -> insertSlice(record.value)
        is CanonicalProfileRecord.Screenshot -> insertScreenshot(record.value)
        is CanonicalProfileRecord.Legacy -> add(record.value)
    }
}
```

Add the four new time-series inserts with explicit bindings; `insertCanonicalSample` delegates to the existing frame/callsite path after binding the new sample columns:

```kotlin
private fun insertMarker(value: ProfileMarkerFact) = connection.prepareStatement(
    "INSERT INTO profile_marker(source_id, thread_id, start_nanos, end_nanos, schema_name, name, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
).use {
    it.setString(1, value.sourceId.value)
    it.setObject(2, value.thread?.threadId)
    it.setLong(3, value.start.timestampNanos)
    it.setObject(4, value.end?.timestampNanos)
    it.setString(5, value.schema)
    it.setString(6, value.name)
    it.setString(7, value.payloadJson)
    it.executeUpdate()
}

private fun insertCounter(value: ProfileCounterFact) = connection.prepareStatement(
    "INSERT INTO profile_counter(source_id, timestamp_nanos, name, unit, value) VALUES (?, ?, ?, ?, ?)",
).use {
    it.setString(1, value.sourceId.value)
    it.setLong(2, value.time.timestampNanos)
    it.setString(3, value.name)
    it.setString(4, value.unit)
    it.setDouble(5, value.value)
    it.executeUpdate()
}

private fun insertSlice(value: ProfileSliceFact) = connection.prepareStatement(
    "INSERT INTO profile_slice(source_id, thread_id, start_nanos, end_nanos, name, category_name, subcategory_name) VALUES (?, ?, ?, ?, ?, ?, ?)",
).use {
    it.setString(1, value.sourceId.value)
    it.setObject(2, value.thread?.threadId)
    it.setLong(3, value.start.timestampNanos)
    it.setLong(4, value.end.timestampNanos)
    it.setString(5, value.name)
    it.setString(6, value.category?.name)
    it.setString(7, value.category?.subcategory)
    it.executeUpdate()
}

private fun insertScreenshot(value: ProfileScreenshotFact) = connection.prepareStatement(
    "INSERT INTO profile_screenshot(source_id, timestamp_nanos, artifact_path) VALUES (?, ?, ?)",
).use {
    it.setString(1, value.sourceId.value)
    it.setLong(2, value.time.timestampNanos)
    it.setString(3, value.artifactPath)
    it.executeUpdate()
}
```

- [ ] **Step 5: Run storage tests and the parser-to-storage regression**

Run: `./gradlew :storage-sqlite:test :application:test --tests '*OfflineProfileImporterTest'`

Expected: PASS.

- [ ] **Step 6: Commit canonical persistence**

```bash
git add storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteCanonicalProfileStoreTest.kt
git commit -m "Persist heterogeneous profile evidence through one bounded writer" -m "Constraint: Legacy normalized imports remain supported.
Confidence: high
Scope-risk: moderate
Tested: ./gradlew :storage-sqlite:test :application:test --tests '*OfflineProfileImporterTest'"
```

### Task 5: Immutable Core Projection Models and Queries

**Files:**
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/ProfileProjectionModels.kt`
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteProfileProjectionQueries.kt`
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteSampleStore.kt`
- Test: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteProfileProjectionQueriesTest.kt`

**Interfaces:**
- Consumes: `ProfileQuery` and v2 facts.
- Produces: `ProfileProjectionSnapshot`, `ProfileTrackSnapshot`, and `SQLiteSampleStore.projectCore(query)`.

- [ ] **Step 1: Write deterministic projection tests**

```kotlin
@Test fun `core projection groups tracks and preserves availability`() = withCanonicalStore { store ->
    val snapshot = store.projectCore(ProfileQuery(threadIds = setOf(101)))
    assertEquals(listOf(ProfileTrackKind.CPU_SAMPLES, ProfileTrackKind.CONTEXT_SWITCHES), snapshot.tracks.map { it.kind })
    assertEquals(ProfileDataAvailability.AVAILABLE, snapshot.tracks.first().availability)
    assertEquals(1L, snapshot.overview.sampleCount)
}
```

- [ ] **Step 2: Run the test and verify projection types are unresolved**

Run: `./gradlew :storage-sqlite:test --tests '*SQLiteProfileProjectionQueriesTest'`

Expected: FAIL during compilation.

- [ ] **Step 3: Define immutable projections**

```kotlin
enum class ProfileTrackKind { CPU_SAMPLES, CONTEXT_SWITCHES, MARKERS, COUNTERS, SLICES, SCREENSHOTS }
enum class ProfileDataAvailability { AVAILABLE, EMPTY, NOT_COLLECTED, UNAVAILABLE, UNAUTHORIZED, FAILED, NOT_APPLICABLE }

data class ProfileTrackSnapshot(
    val id: String,
    val kind: ProfileTrackKind,
    val processId: Int?,
    val threadId: Int?,
    val availability: ProfileDataAvailability,
    val startNanos: Long?,
    val endNanosExclusive: Long?,
)

data class ProfileProjectionSnapshot(
    val query: ProfileQuery,
    val overview: ProfileOverview,
    val quality: DataQualitySummary,
    val tracks: List<ProfileTrackSnapshot>,
    val threads: List<ThreadSummary>,
    val timeline: List<TimelineBucket>,
    val topFunctions: List<TopFunction>,
    val forwardCallTree: List<CallTreeNode>,
)
```

- [ ] **Step 4: Implement `projectCore` as one read transaction**

```kotlin
fun SQLiteSampleStore.projectCore(query: ProfileQuery): ProfileProjectionSnapshot = readTransaction {
    ProfileProjectionSnapshot(
        query = query,
        overview = overview(query),
        quality = dataQuality(),
        tracks = coreTracks(query),
        threads = threads(query),
        timeline = timelineBuckets(query, 600),
        topFunctions = topFunctions(query, 200),
        forwardCallTree = callTree(query, CallTreeDirection.FORWARD),
    )
}
```

Sort every returned list explicitly so identical databases yield identical snapshots.

- [ ] **Step 5: Run the storage suite**

Run: `./gradlew :storage-sqlite:test`

Expected: PASS.

- [ ] **Step 6: Commit projections**

```bash
git add storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteProfileProjectionQueriesTest.kt
git commit -m "Give profiler panels one immutable view of current evidence" -m "Confidence: high
Scope-risk: moderate
Tested: ./gradlew :storage-sqlite:test"
```

### Task 6: Workspace State and Generation Contract

**Files:**
- Create: `application/src/main/kotlin/com/androidperformancestudio/application/ProfileWorkspaceState.kt`
- Test: `application/src/test/kotlin/com/androidperformancestudio/application/ProfileWorkspaceStateTest.kt`

**Interfaces:**
- Consumes: `ProfileQuery` and `ProfileProjectionSnapshot`.
- Produces: `ProfileGeneration`, `ProfileWorkspaceSelection`, `ProfileWorkspaceLoadState`, and `ProfileWorkspaceState`.

- [ ] **Step 1: Write state transition tests**

```kotlin
@Test fun `query changes advance generation and retain the last ready snapshot`() {
    val ready = workspaceStateFixture()
    val next = ready.request(ProfileQuery(threadIds = setOf(42)))
    assertEquals(ProfileGeneration(ready.generation.value + 1), next.generation)
    assertEquals(ready.snapshot, next.snapshot)
    assertIs<ProfileWorkspaceLoadState.Refreshing>(next.loadState)
}
```

- [ ] **Step 2: Run the focused test and verify state types are unresolved**

Run: `./gradlew :application:test --tests '*ProfileWorkspaceStateTest'`

Expected: FAIL during compilation.

- [ ] **Step 3: Implement immutable state transitions**

```kotlin
@JvmInline value class ProfileGeneration(val value: Long)

sealed interface ProfileWorkspaceLoadState {
    data object Closed : ProfileWorkspaceLoadState
    data class Loading(val sessionDirectory: Path) : ProfileWorkspaceLoadState
    data class Refreshing(val sessionDirectory: Path) : ProfileWorkspaceLoadState
    data class Ready(val sessionDirectory: Path) : ProfileWorkspaceLoadState
    data class Failed(val sessionDirectory: Path, val error: StudioError) : ProfileWorkspaceLoadState
}

data class ProfileWorkspaceState(
    val generation: ProfileGeneration = ProfileGeneration(0),
    val sessionDirectory: Path? = null,
    val query: ProfileQuery = ProfileQuery(),
    val snapshot: ProfileProjectionSnapshot? = null,
    val loadState: ProfileWorkspaceLoadState = ProfileWorkspaceLoadState.Closed,
) {
    fun request(nextQuery: ProfileQuery): ProfileWorkspaceState = copy(
        generation = ProfileGeneration(generation.value + 1),
        query = nextQuery,
        loadState = ProfileWorkspaceLoadState.Refreshing(checkNotNull(sessionDirectory)),
    )
}
```

- [ ] **Step 4: Run application tests**

Run: `./gradlew :application:test`

Expected: PASS.

- [ ] **Step 5: Commit workspace state**

```bash
git add application/src/main/kotlin/com/androidperformancestudio/application/ProfileWorkspaceState.kt application/src/test/kotlin/com/androidperformancestudio/application/ProfileWorkspaceStateTest.kt
git commit -m "Make profiler workspace transitions explicit and immutable" -m "Confidence: high
Scope-risk: narrow
Tested: ./gradlew :application:test"
```

### Task 7: Cancellable Generation-Safe Query Coordinator

**Files:**
- Create: `application/src/main/kotlin/com/androidperformancestudio/application/ProfileQueryCoordinator.kt`
- Test: `application/src/test/kotlin/com/androidperformancestudio/application/ProfileQueryCoordinatorTest.kt`

**Interfaces:**
- Consumes: generation/state from Task 6 and `SQLiteSampleStore.projectCore` from Task 5.
- Produces: `ProfileProjectionLoader` and `ProfileQueryCoordinator.submit(session, generation, query)`.

- [ ] **Step 1: Write cancellation and stale-result tests with a controllable fake loader**

```kotlin
@Test fun `new query cancels the previous load and only publishes its generation`() = runTest {
    val loader = ControllableProjectionLoader()
    val coordinator = ProfileQueryCoordinator(this, loader)
    coordinator.submit(session, ProfileGeneration(1), ProfileQuery(threadIds = setOf(1)))
    coordinator.submit(session, ProfileGeneration(2), ProfileQuery(threadIds = setOf(2)))
    loader.complete(ProfileGeneration(1), snapshotFor(1))
    loader.complete(ProfileGeneration(2), snapshotFor(2))
    assertEquals(ProfileGeneration(2), coordinator.results.first().generation)
    assertEquals(setOf(2), coordinator.results.first().snapshot.query.threadIds)
}
```

- [ ] **Step 2: Run the focused test and verify coordinator symbols are unresolved**

Run: `./gradlew :application:test --tests '*ProfileQueryCoordinatorTest'`

Expected: FAIL during compilation.

- [ ] **Step 3: Implement loader and coordinator contracts**

```kotlin
fun interface ProfileProjectionLoader {
    suspend fun load(sessionDirectory: Path, query: ProfileQuery): ProfileProjectionSnapshot
}

data class GeneratedProjection(val generation: ProfileGeneration, val snapshot: ProfileProjectionSnapshot)

class ProfileQueryCoordinator(
    private val scope: CoroutineScope,
    private val loader: ProfileProjectionLoader,
) : Closeable {
    private val mutableResults = MutableSharedFlow<GeneratedProjection>(extraBufferCapacity = 1)
    private var activeJob: Job? = null
    val results = mutableResults.asSharedFlow()

    fun submit(sessionDirectory: Path, generation: ProfileGeneration, query: ProfileQuery) {
        activeJob?.cancel()
        activeJob = scope.launch {
            val snapshot = loader.load(sessionDirectory, query)
            ensureActive()
            mutableResults.emit(GeneratedProjection(generation, snapshot))
        }
    }

    fun cancel() { activeJob?.cancel(); activeJob = null }
    override fun close() { activeJob?.cancel() }
}
```

- [ ] **Step 4: Add the production SQLite loader on `Dispatchers.IO`**

```kotlin
fun sqliteProjectionLoader(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    ProfileProjectionLoader { session, query ->
        withContext(ioDispatcher) {
            SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { it.projectCore(query) }
        }
    }
```

- [ ] **Step 5: Run application tests**

Run: `./gradlew :application:test`

Expected: PASS, including the stale-generation test.

- [ ] **Step 6: Commit query coordination**

```bash
git add application/src/main/kotlin/com/androidperformancestudio/application/ProfileQueryCoordinator.kt application/src/test/kotlin/com/androidperformancestudio/application/ProfileQueryCoordinatorTest.kt
git commit -m "Prevent obsolete profile queries from replacing current evidence" -m "Confidence: high
Scope-risk: moderate
Tested: ./gradlew :application:test"
```

### Task 8: Workspace Controller and Report Compatibility Facade

**Files:**
- Create: `application/src/main/kotlin/com/androidperformancestudio/application/ProfileWorkspaceController.kt`
- Modify: `application/src/main/kotlin/com/androidperformancestudio/application/ReportController.kt`
- Modify: `application/src/test/kotlin/com/androidperformancestudio/application/ReportControllerTest.kt`
- Test: `application/src/test/kotlin/com/androidperformancestudio/application/ProfileWorkspaceControllerTest.kt`

**Interfaces:**
- Consumes: Tasks 5–7.
- Produces: `ProfileWorkspaceController.openSession`, `updateQuery`, `closeSession`; preserves the existing `ReportController` API used by presentation.

- [ ] **Step 1: Write controller tests for loading, refresh, failure, cancellation, and last-ready preservation**

```kotlin
@Test fun `workspace publishes only the newest ready projection`() = runTest {
    val harness = WorkspaceHarness(this)
    harness.controller.openSession(harness.session)
    harness.controller.updateQuery(ProfileQuery(threadIds = setOf(101)))
    harness.completeOldRequest()
    harness.completeCurrentRequest()
    val state = harness.controller.state.value
    assertEquals(setOf(101), state.snapshot?.query?.threadIds)
    assertIs<ProfileWorkspaceLoadState.Ready>(state.loadState)
}
```

- [ ] **Step 2: Run the focused test and verify the controller is unresolved**

Run: `./gradlew :application:test --tests '*ProfileWorkspaceControllerTest'`

Expected: FAIL during compilation.

- [ ] **Step 3: Implement the workspace controller**

```kotlin
class ProfileWorkspaceController(
    scope: CoroutineScope,
    loader: ProfileProjectionLoader = sqliteProjectionLoader(),
) : Closeable {
    private val mutableState = MutableStateFlow(ProfileWorkspaceState())
    private val coordinator = ProfileQueryCoordinator(scope, loader)
    val state: StateFlow<ProfileWorkspaceState> = mutableState.asStateFlow()

    init {
        scope.launch {
            coordinator.results.collect { result ->
                val current = mutableState.value
                if (result.generation == current.generation) {
                    mutableState.value = current.copy(
                        snapshot = result.snapshot,
                        loadState = ProfileWorkspaceLoadState.Ready(checkNotNull(current.sessionDirectory())),
                    )
                }
            }
        }
    }

    private var sessionDirectory: Path? = null

    fun openSession(directory: Path) {
        val session = directory.toAbsolutePath().normalize()
        sessionDirectory = session
        val generation = ProfileGeneration(mutableState.value.generation.value + 1)
        mutableState.value = ProfileWorkspaceState(
            generation = generation,
            sessionDirectory = session,
            loadState = ProfileWorkspaceLoadState.Loading(session),
        )
        coordinator.submit(session, generation, ProfileQuery())
    }

    fun updateQuery(query: ProfileQuery) {
        val session = checkNotNull(sessionDirectory) { "No profile session is open" }
        val next = mutableState.value.request(query)
        mutableState.value = next
        coordinator.submit(session, next.generation, query)
    }

    fun closeSession() {
        coordinator.cancel()
        sessionDirectory = null
        mutableState.value = ProfileWorkspaceState()
    }
    override fun close() = coordinator.close()
}
```

- [ ] **Step 4: Convert `ReportController` to mapping and compatibility only**

Inject `ProfileWorkspaceController` into `ReportController`. Replace `reload()` with `workspace.updateQuery(current.filter)` and map `ProfileProjectionSnapshot` using this adapter; remove direct `SQLiteSampleStore.open` and `withContext` calls:

```kotlin
private fun ProfileProjectionSnapshot.toReportData(session: ReportSessionSummary): ReportData =
    ReportData(
        session = session,
        sessionOverview = overview,
        overview = overview,
        quality = quality,
        sessionThreads = threads,
        topThreads = threads,
        topFunctions = topFunctions,
        timeline = timeline,
        callTree = forwardCallTree,
        flameGraph = forwardCallTree.toFlameGraph(),
        diagnostics = diagnosticEngine.analyze(AnalysisSnapshot(overview, quality, threads, topFunctions)),
    )
```

- [ ] **Step 5: Run application and presentation regression tests**

Run: `./gradlew :application:test :presentation:test`

Expected: PASS; existing `ReportControllerTest` assertions remain unchanged.

- [ ] **Step 6: Commit controller decomposition**

```bash
git add application/src/main/kotlin/com/androidperformancestudio/application application/src/test/kotlin/com/androidperformancestudio/application
git commit -m "Separate profiler workspace state from report presentation compatibility" -m "Constraint: Existing report UI behavior remains unchanged.
Confidence: high
Scope-risk: moderate
Tested: ./gradlew :application:test :presentation:test"
```

### Task 9: Legacy Session Migration and Atomic Backup Workflow

**Files:**
- Create: `application/src/main/kotlin/com/androidperformancestudio/application/ProfileSessionMigrator.kt`
- Modify: `application/src/main/kotlin/com/androidperformancestudio/application/ProfileWorkspaceController.kt`
- Test: `application/src/test/kotlin/com/androidperformancestudio/application/ProfileSessionMigratorTest.kt`

**Interfaces:**
- Consumes: SQLite v2 migration from Task 3.
- Produces: `ProfileSessionMigrator.prepare(sessionDirectory): PreparedProfileSession` with migrated or legacy-read-only mode.

- [ ] **Step 1: Write atomicity tests**

```kotlin
@Test fun `successful migration atomically replaces a copied database`() {
    val prepared = migrator.prepare(versionOneSession())
    assertEquals(ProfileSessionMode.READ_WRITE_V2, prepared.mode)
    assertEquals(2, userVersion(prepared.database))
    assertTrue(prepared.originalDatabase.exists())
}

@Test fun `failed migration preserves original and returns legacy read-only mode`() {
    val session = versionOneSession()
    val before = sha256(session.resolve("profile.sqlite"))
    val prepared = failingMigrator.prepare(session)
    assertEquals(ProfileSessionMode.LEGACY_READ_ONLY, prepared.mode)
    assertEquals(before, sha256(session.resolve("profile.sqlite")))
}
```

- [ ] **Step 2: Run the focused test and verify migrator symbols are unresolved**

Run: `./gradlew :application:test --tests '*ProfileSessionMigratorTest'`

Expected: FAIL during compilation.

- [ ] **Step 3: Implement copy-migrate-replace semantics**

```kotlin
enum class ProfileSessionMode { READ_WRITE_V2, LEGACY_READ_ONLY }
data class PreparedProfileSession(val database: Path, val originalDatabase: Path, val mode: ProfileSessionMode)

class ProfileSessionMigrator {
    fun prepare(sessionDirectory: Path): PreparedProfileSession {
        val original = sessionDirectory.resolve("profile.sqlite")
        val backup = sessionDirectory.resolve("profile.v1.sqlite")
        val candidate = sessionDirectory.resolve("profile.sqlite.migrating")
        return try {
            Files.copy(original, candidate, StandardCopyOption.REPLACE_EXISTING)
            SQLiteSampleStore.open(candidate).close()
            if (Files.notExists(backup)) Files.copy(original, backup)
            Files.move(candidate, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            PreparedProfileSession(original, original, ProfileSessionMode.READ_WRITE_V2)
        } catch (failure: Exception) {
            Files.deleteIfExists(candidate)
            PreparedProfileSession(original, original, ProfileSessionMode.LEGACY_READ_ONLY)
        }
    }
}
```

Keep an immutable pre-migration copy named `profile.v1.sqlite` on the first successful migration and record its SHA-256 in `migration.properties`.

- [ ] **Step 4: Route workspace open through the migrator**

Add `sessionMode: ProfileSessionMode?` to `ProfileWorkspaceState`. In `openSession`, call `migrator.prepare(session)` before submitting and copy `prepared.mode` into state. The existing presentation remains functional because both modes expose a readable database; the later UI plan adds the warning banner.

- [ ] **Step 5: Run application, storage, and packaged-session tests**

Run: `./gradlew :application:test :storage-sqlite:test :export-adapters:test`

Expected: PASS.

- [ ] **Step 6: Commit safe session migration**

```bash
git add application/src/main/kotlin/com/androidperformancestudio/application/ProfileSessionMigrator.kt application/src/main/kotlin/com/androidperformancestudio/application/ProfileWorkspaceController.kt application/src/test/kotlin/com/androidperformancestudio/application/ProfileSessionMigratorTest.kt
git commit -m "Protect captured evidence while sessions adopt the new schema" -m "Constraint: Migration failure must leave profile.sqlite byte-for-byte unchanged.
Confidence: high
Scope-risk: moderate
Tested: ./gradlew :application:test :storage-sqlite:test :export-adapters:test"
```

### Task 10: Stage 1 Verification and Documentation

**Files:**
- Modify: `docs/requirements.md`
- Modify: `docs/development-plan.md`
- Modify: `docs/user-guide.md`
- Modify: `docs/release-checklist.md`
- Test: existing module suites and `checkAll`.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: verified Stage 1 baseline and documented migration/availability semantics.

- [ ] **Step 1: Add a million-sample projection regression**

Add this assertion to the P0 fixture after its million records are indexed:

```kotlin
val controller = ProfileWorkspaceController(backgroundScope, sqliteProjectionLoader())
controller.openSession(sessionDirectory)
controller.updateQuery(ProfileQuery(threadIds = setOf(firstThreadId)))
controller.updateQuery(ProfileQuery(threadIds = setOf(secondThreadId)))
advanceUntilIdle()
check(controller.state.value.snapshot?.query?.threadIds == setOf(secondThreadId))
check(controller.state.value.loadState is ProfileWorkspaceLoadState.Ready)
```

- [ ] **Step 2: Document user-visible compatibility behavior**

Add a “Profile database migration” section with these exact behavioral statements: migration works on a copy; the first successful migration retains `profile.v1.sqlite` and its SHA-256; failure opens the original in legacy read-only mode; and availability is reported as Available, Empty, Not collected, Unavailable, Unauthorized, Failed, or Not applicable. State that users must copy the complete session directory before attempting manual SQLite repair.

- [ ] **Step 3: Run formatting and static analysis**

Run: `./gradlew ktlintCheck detekt`

Expected: BUILD SUCCESSFUL with zero warnings.

- [ ] **Step 4: Run all Simpleperf viewer checks**

Run: `./gradlew checkAll`

Expected: BUILD SUCCESSFUL; all module tests pass.

- [ ] **Step 5: Run the P0 performance fixture**

Run: `./gradlew :test-fixtures:runP0PerformancePoc`

Expected: task succeeds and the generated result stays within the documented P0 thresholds.

- [ ] **Step 6: Review the final diff for scope and generated artifacts**

Run: `git status --short && git diff --check && git diff --stat`

Expected: only Stage 1 source, tests, and documentation are present; no build output is tracked; `git diff --check` is silent.

- [ ] **Step 7: Commit Stage 1 verification evidence**

```bash
git add docs test-fixtures
git commit -m "Make the profile-core migration contract reproducible" -m "Constraint: Stage 1 must preserve every V0.1 workflow.
Confidence: high
Scope-risk: moderate
Tested: ./gradlew ktlintCheck detekt checkAll :test-fixtures:runP0PerformancePoc"
```

## Completion Gate

Stage 1 is complete only when:

- all canonical facts have stable types and v2 storage;
- v1 sessions migrate without changing existing sample results;
- failed migration preserves the original bytes and exposes legacy read-only mode;
- report panels receive immutable snapshots through the workspace controller;
- obsolete queries cannot publish after a newer generation;
- the existing report UI, capture path, import path, session package, and export tests pass;
- `ktlintCheck`, `detekt`, `checkAll`, and the P0 performance fixture pass with fresh output.
