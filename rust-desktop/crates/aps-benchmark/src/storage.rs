//! Transactional SQLite run store (port of `SqliteBenchmarkStore.kt`).
//!
//! The schema is the Compose schema: one summary row per content-addressed run
//! plus the case, trace and metric evidence needed by the report rail and the
//! metric-history trend. Re-importing identical bytes replaces the row instead
//! of duplicating it because the run id is a content digest.
use std::path::Path;
use std::time::Duration;

use rusqlite::{Connection, params};

use crate::model::{BenchmarkRun, ProfileStatus};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredBenchmarkRun {
    pub id: String,
    pub source_path: String,
    pub imported_at: String,
    pub device_model: Option<String>,
    pub case_count: usize,
    pub trace_artifact_count: usize,
    pub baseline_profile_case_count: usize,
    pub version_name: Option<String>,
    pub git_commit: Option<String>,
    pub api_level: Option<u32>,
}

/// One case/metric pair that has stored history, used to drive the trend selector.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredMetric {
    pub case_identity: String,
    pub metric_name: String,
    pub unit: String,
    pub point_count: usize,
}

/// One metric value per run, ordered chronologically by the store.
#[derive(Debug, Clone, PartialEq)]
pub struct StoredMetricPoint {
    pub run_id: String,
    pub imported_at: String,
    pub build_label: Option<String>,
    pub git_commit: Option<String>,
    pub device_model: Option<String>,
    pub api_level: Option<u32>,
    pub value: f64,
    pub sample_count: usize,
}

const SCHEMA: &str = "
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS benchmark_run(
    id TEXT PRIMARY KEY,
    source_path TEXT NOT NULL,
    imported_at TEXT NOT NULL,
    device_model TEXT,
    case_count INTEGER NOT NULL,
    version_name TEXT,
    git_commit TEXT,
    api_level INTEGER
);
CREATE TABLE IF NOT EXISTS benchmark_metric(
    run_id TEXT NOT NULL,
    case_identity TEXT NOT NULL,
    metric_name TEXT NOT NULL,
    unit TEXT NOT NULL,
    median REAL,
    samples INTEGER NOT NULL,
    PRIMARY KEY(run_id, case_identity, metric_name),
    FOREIGN KEY(run_id) REFERENCES benchmark_run(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS benchmark_case(
    run_id TEXT NOT NULL,
    case_identity TEXT NOT NULL,
    scenario TEXT NOT NULL,
    startup_mode TEXT,
    compilation_mode TEXT,
    baseline_profile_status TEXT,
    baseline_profile_source TEXT,
    baseline_profile_artifact TEXT,
    baseline_profile_hash TEXT,
    PRIMARY KEY(run_id, case_identity),
    FOREIGN KEY(run_id) REFERENCES benchmark_run(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS benchmark_trace(
    run_id TEXT NOT NULL,
    case_identity TEXT NOT NULL,
    path TEXT NOT NULL,
    PRIMARY KEY(run_id, case_identity, path),
    FOREIGN KEY(run_id) REFERENCES benchmark_run(id) ON DELETE CASCADE
);
";

pub struct BenchmarkStore {
    connection: Connection,
}

impl BenchmarkStore {
    pub fn open(path: &Path) -> Result<Self, String> {
        if let Some(parent) = path.parent()
            && !parent.as_os_str().is_empty()
        {
            std::fs::create_dir_all(parent).map_err(|_| {
                format!(
                    "Cannot create benchmark database directory: {}",
                    parent.display()
                )
            })?;
        }
        let connection = Connection::open(path)
            .map_err(|error| format!("Cannot open benchmark database: {error}"))?;
        connection
            .busy_timeout(Duration::from_secs(5))
            .map_err(store_error)?;
        connection.execute_batch(SCHEMA).map_err(store_error)?;
        Ok(Self { connection })
    }

    /// One transaction: upsert the run, replace its evidence, then insert everything.
    pub fn save(&self, run: &BenchmarkRun) -> Result<(), String> {
        let transaction = self
            .connection
            .unchecked_transaction()
            .map_err(store_error)?;
        if let Err(error) = write_run(&transaction, run) {
            let _ = transaction.rollback();
            return Err(error);
        }
        transaction.commit().map_err(store_error)
    }

    /// Newest `imported_at` first, with the evidence counts the report rail shows.
    pub fn list_recent(&self, limit: usize) -> Result<Vec<StoredBenchmarkRun>, String> {
        let mut statement = self
            .connection
            .prepare(
                "SELECT r.id, r.source_path, r.imported_at, r.device_model, r.case_count, r.version_name, \
                 r.git_commit, r.api_level, \
                 (SELECT COUNT(*) FROM benchmark_trace t WHERE t.run_id = r.id), \
                 (SELECT COUNT(*) FROM benchmark_case c WHERE c.run_id = r.id \
                    AND c.baseline_profile_status IN ('INSTALLED', 'VERIFIED')) \
                 FROM benchmark_run r ORDER BY r.imported_at DESC LIMIT ?1",
            )
            .map_err(store_error)?;
        let rows = statement
            .query_map(params![limit as i64], |row| {
                let api_level: Option<i64> = row.get(7)?;
                Ok(StoredBenchmarkRun {
                    id: row.get(0)?,
                    source_path: row.get(1)?,
                    imported_at: row.get(2)?,
                    device_model: row.get(3)?,
                    case_count: row.get::<_, i64>(4)? as usize,
                    version_name: row.get(5)?,
                    git_commit: row.get(6)?,
                    api_level: api_level.and_then(|value| u32::try_from(value).ok()),
                    trace_artifact_count: row.get::<_, i64>(8)? as usize,
                    baseline_profile_case_count: row.get::<_, i64>(9)? as usize,
                })
            })
            .map_err(store_error)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(store_error)
    }

    /// Case/metric pairs with at least one stored value, most recently measured first.
    pub fn list_metric_keys(&self, limit: usize) -> Result<Vec<StoredMetric>, String> {
        let mut statement = self
            .connection
            .prepare(
                "SELECT m.case_identity, m.metric_name, m.unit, COUNT(*), MAX(r.imported_at) AS last_seen \
                 FROM benchmark_metric m JOIN benchmark_run r ON r.id = m.run_id \
                 WHERE m.median IS NOT NULL \
                 GROUP BY m.case_identity, m.metric_name, m.unit \
                 ORDER BY last_seen DESC LIMIT ?1",
            )
            .map_err(store_error)?;
        let rows = statement
            .query_map(params![limit as i64], |row| {
                Ok(StoredMetric {
                    case_identity: row.get(0)?,
                    metric_name: row.get(1)?,
                    unit: row.get(2)?,
                    point_count: row.get::<_, i64>(3)? as usize,
                })
            })
            .map_err(store_error)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(store_error)
    }

    /// Newest `limit` values for one case/metric, returned oldest-first so a chart reads left to right.
    pub fn metric_history(
        &self,
        case_identity: &str,
        metric_name: &str,
        limit: usize,
    ) -> Result<Vec<StoredMetricPoint>, String> {
        let mut statement = self
            .connection
            .prepare(
                "SELECT r.id, r.imported_at, r.version_name, r.git_commit, r.device_model, r.api_level, \
                 m.median, m.samples \
                 FROM benchmark_metric m JOIN benchmark_run r ON r.id = m.run_id \
                 WHERE m.case_identity = ?1 AND m.metric_name = ?2 AND m.median IS NOT NULL \
                 ORDER BY r.imported_at DESC LIMIT ?3",
            )
            .map_err(store_error)?;
        let rows = statement
            .query_map(params![case_identity, metric_name, limit as i64], |row| {
                let api_level: Option<i64> = row.get(5)?;
                Ok(StoredMetricPoint {
                    run_id: row.get(0)?,
                    imported_at: row.get(1)?,
                    build_label: row.get(2)?,
                    git_commit: row.get(3)?,
                    device_model: row.get(4)?,
                    api_level: api_level.and_then(|value| u32::try_from(value).ok()),
                    value: row.get(6)?,
                    sample_count: row.get::<_, i64>(7)? as usize,
                })
            })
            .map_err(store_error)?;
        let mut points = rows.collect::<Result<Vec<_>, _>>().map_err(store_error)?;
        points.reverse();
        Ok(points)
    }
}

fn write_run(connection: &Connection, run: &BenchmarkRun) -> Result<(), String> {
    connection
        .execute(
            "INSERT OR REPLACE INTO benchmark_run(id, source_path, imported_at, device_model, \
             case_count, version_name, git_commit, api_level) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                run.id,
                run.source_path.to_string_lossy(),
                run.imported_at,
                run.device.model,
                run.cases.len() as i64,
                run.build.version_name,
                run.build.git_commit,
                run.device.api_level,
            ],
        )
        .map_err(store_error)?;
    for table in ["benchmark_metric", "benchmark_case", "benchmark_trace"] {
        connection
            .execute(
                &format!("DELETE FROM {table} WHERE run_id = ?1"),
                params![run.id],
            )
            .map_err(store_error)?;
    }
    {
        let mut statement = connection
            .prepare(
                "INSERT INTO benchmark_case(run_id, case_identity, scenario, startup_mode, \
                 compilation_mode, baseline_profile_status, baseline_profile_source, \
                 baseline_profile_artifact, baseline_profile_hash) \
                 VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            )
            .map_err(store_error)?;
        for case in &run.cases {
            let (status, source, artifact, hash) = match &case.profile {
                Some(profile) => (
                    profile.status.label(),
                    profile.source.as_deref(),
                    profile
                        .artifact
                        .as_ref()
                        .map(|path| path.to_string_lossy().into_owned()),
                    profile.profile_hash.as_deref(),
                ),
                None => (ProfileStatus::NotRequested.label(), None, None, None),
            };
            statement
                .execute(params![
                    run.id,
                    case.identity(),
                    case.scenario.label(),
                    case.startup_mode,
                    case.compilation_mode,
                    status,
                    source,
                    artifact,
                    hash,
                ])
                .map_err(store_error)?;
        }
    }
    {
        let mut statement = connection
            .prepare("INSERT INTO benchmark_trace(run_id, case_identity, path) VALUES(?1, ?2, ?3)")
            .map_err(store_error)?;
        for case in &run.cases {
            for trace in &case.trace_artifacts {
                statement
                    .execute(params![run.id, case.identity(), trace.to_string_lossy()])
                    .map_err(store_error)?;
            }
        }
    }
    {
        let mut statement = connection
            .prepare(
                "INSERT INTO benchmark_metric(run_id, case_identity, metric_name, unit, median, samples) \
                 VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
            )
            .map_err(store_error)?;
        for case in &run.cases {
            let identity = case.identity();
            for metric in &case.metrics {
                statement
                    .execute(params![
                        run.id,
                        identity,
                        metric.name,
                        metric.unit,
                        metric.representative_value(),
                        metric.samples.len() as i64,
                    ])
                    .map_err(store_error)?;
            }
        }
    }
    Ok(())
}

fn store_error(error: rusqlite::Error) -> String {
    format!("Benchmark store error: {error}")
}
