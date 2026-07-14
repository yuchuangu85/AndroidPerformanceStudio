package com.androidperformancestudio.storage

internal object SQLiteSchemaV2 {
    val statements =
        listOf(
            "CREATE TABLE profile_source (source_id TEXT PRIMARY KEY, kind TEXT NOT NULL, " +
                "clock_domain TEXT NOT NULL, valid_from_nanos INTEGER, valid_until_nanos INTEGER)",
            "CREATE TABLE profile_process (process_row_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "process_id INTEGER NOT NULL, name TEXT, start_nanos INTEGER, start_clock_domain TEXT, " +
                "start_error_bound_nanos INTEGER, end_nanos INTEGER, end_clock_domain TEXT, " +
                "end_error_bound_nanos INTEGER, UNIQUE(source_id, process_id), " +
                "FOREIGN KEY(source_id) REFERENCES profile_source(source_id))",
            "CREATE TABLE profile_thread (thread_row_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "process_row_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, name TEXT NOT NULL, " +
                "start_nanos INTEGER, start_clock_domain TEXT, start_error_bound_nanos INTEGER, " +
                "end_nanos INTEGER, end_clock_domain TEXT, end_error_bound_nanos INTEGER, " +
                "UNIQUE(source_id, process_row_id, thread_id), " +
                "FOREIGN KEY(source_id) REFERENCES profile_source(source_id), " +
                "FOREIGN KEY(process_row_id) REFERENCES profile_process(process_row_id))",
            "ALTER TABLE sample ADD COLUMN source_id TEXT REFERENCES profile_source(source_id)",
            "ALTER TABLE sample ADD COLUMN process_row_id INTEGER REFERENCES profile_process(process_row_id)",
            "ALTER TABLE sample ADD COLUMN thread_row_id INTEGER REFERENCES profile_thread(thread_row_id)",
            "ALTER TABLE sample ADD COLUMN clock_domain TEXT",
            "ALTER TABLE sample ADD COLUMN time_error_bound_nanos INTEGER",
            "ALTER TABLE sample ADD COLUMN cpu_core INTEGER",
            "ALTER TABLE sample ADD COLUMN on_cpu INTEGER",
            "ALTER TABLE sample ADD COLUMN category_name TEXT",
            "ALTER TABLE sample ADD COLUMN subcategory_name TEXT",
            "CREATE TABLE profile_marker (marker_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "thread_row_id INTEGER, start_nanos INTEGER NOT NULL, start_clock_domain TEXT NOT NULL, " +
                "start_error_bound_nanos INTEGER NOT NULL, end_nanos INTEGER, end_clock_domain TEXT, " +
                "end_error_bound_nanos INTEGER, schema_name TEXT NOT NULL, name TEXT NOT NULL, " +
                "payload_json TEXT NOT NULL, FOREIGN KEY(source_id) REFERENCES profile_source(source_id), " +
                "FOREIGN KEY(thread_row_id) REFERENCES profile_thread(thread_row_id))",
            "CREATE INDEX profile_marker_time ON profile_marker(start_nanos, end_nanos)",
            "CREATE TABLE profile_counter (counter_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "timestamp_nanos INTEGER NOT NULL, clock_domain TEXT NOT NULL, " +
                "time_error_bound_nanos INTEGER NOT NULL, name TEXT NOT NULL, unit TEXT NOT NULL, " +
                "value REAL NOT NULL, FOREIGN KEY(source_id) REFERENCES profile_source(source_id))",
            "CREATE INDEX profile_counter_name_time ON profile_counter(name, timestamp_nanos)",
            "CREATE TABLE profile_slice (slice_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "thread_row_id INTEGER, start_nanos INTEGER NOT NULL, start_clock_domain TEXT NOT NULL, " +
                "start_error_bound_nanos INTEGER NOT NULL, end_nanos INTEGER NOT NULL, " +
                "end_clock_domain TEXT NOT NULL, end_error_bound_nanos INTEGER NOT NULL, name TEXT NOT NULL, " +
                "category_name TEXT, subcategory_name TEXT, " +
                "FOREIGN KEY(source_id) REFERENCES profile_source(source_id), " +
                "FOREIGN KEY(thread_row_id) REFERENCES profile_thread(thread_row_id))",
            "CREATE INDEX profile_slice_thread_time ON profile_slice(thread_row_id, start_nanos, end_nanos)",
            "CREATE TABLE profile_screenshot (screenshot_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "timestamp_nanos INTEGER NOT NULL, clock_domain TEXT NOT NULL, " +
                "time_error_bound_nanos INTEGER NOT NULL, artifact_path TEXT NOT NULL, " +
                "FOREIGN KEY(source_id) REFERENCES profile_source(source_id))",
            "CREATE TABLE clock_alignment (alignment_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "source_nanos INTEGER NOT NULL, canonical_nanos INTEGER NOT NULL, " +
                "error_bound_nanos INTEGER NOT NULL, " +
                "FOREIGN KEY(source_id) REFERENCES profile_source(source_id))",
        )
}
