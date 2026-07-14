package com.androidperformancestudio.storage

internal object SQLiteSchemaV2 {
    val statements =
        listOf(
            "CREATE TABLE profile_source (source_id TEXT PRIMARY KEY, kind TEXT NOT NULL, " +
                "clock_domain TEXT NOT NULL, valid_from_nanos INTEGER, valid_until_nanos INTEGER)",
            "ALTER TABLE process ADD COLUMN source_id TEXT REFERENCES profile_source(source_id)",
            "ALTER TABLE process ADD COLUMN start_nanos INTEGER",
            "ALTER TABLE process ADD COLUMN end_nanos INTEGER",
            "ALTER TABLE thread ADD COLUMN start_nanos INTEGER",
            "ALTER TABLE thread ADD COLUMN end_nanos INTEGER",
            "ALTER TABLE sample ADD COLUMN cpu_core INTEGER",
            "ALTER TABLE sample ADD COLUMN on_cpu INTEGER",
            "ALTER TABLE sample ADD COLUMN category_name TEXT",
            "ALTER TABLE sample ADD COLUMN subcategory_name TEXT",
            "CREATE TABLE profile_marker (marker_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "thread_id INTEGER, start_nanos INTEGER NOT NULL, end_nanos INTEGER, " +
                "schema_name TEXT NOT NULL, name TEXT NOT NULL, payload_json TEXT NOT NULL)",
            "CREATE INDEX profile_marker_time ON profile_marker(start_nanos, end_nanos)",
            "CREATE TABLE profile_counter (counter_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "timestamp_nanos INTEGER NOT NULL, name TEXT NOT NULL, unit TEXT NOT NULL, value REAL NOT NULL)",
            "CREATE INDEX profile_counter_name_time ON profile_counter(name, timestamp_nanos)",
            "CREATE TABLE profile_slice (slice_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "thread_id INTEGER, start_nanos INTEGER NOT NULL, end_nanos INTEGER NOT NULL, " +
                "name TEXT NOT NULL, category_name TEXT, subcategory_name TEXT)",
            "CREATE INDEX profile_slice_thread_time ON profile_slice(thread_id, start_nanos, end_nanos)",
            "CREATE TABLE profile_screenshot (screenshot_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "timestamp_nanos INTEGER NOT NULL, artifact_path TEXT NOT NULL)",
            "CREATE TABLE clock_alignment (alignment_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "source_nanos INTEGER NOT NULL, canonical_nanos INTEGER NOT NULL, " +
                "error_bound_nanos INTEGER NOT NULL)",
        )
}
