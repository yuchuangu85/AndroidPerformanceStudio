//! CPU evidence for a dedicated, single-launch trace. All intervals and sample
//! timestamps are in Trace Processor's trace clock. Never align host clocks,
//! treat am-start TotalTime as TTID, or turn sample counts into method times.
use crate::{CancellationToken, PerfettoError, TraceSession, decode_hex};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StartupCpuEvidence {
    Unavailable(String),
    Measured(StartupCpuWindow),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StartupCpuWindow {
    pub startup_id: u64,
    pub pid: u32,
    pub tid: u32,
    pub start_ns: i64,
    /// Absolute trace timestamp of initial display (exclusive), not a duration.
    pub end_ns: i64,
    pub scheduling: Option<StartupCpuScheduling>,
    pub total_samples: u64,
    pub unresolved_samples: u64,
    /// Kernel perf ring records reported lost across the whole trace, not
    /// necessarily this target/window. Zero means no *reported* ring loss.
    pub trace_perf_lost_records: u64,
    /// Top 12 leaf symbols by observed samples; not invocation counts.
    pub hotspots: Vec<StartupCpuHotspot>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StartupCpuScheduling {
    pub running_ns: u64,
    pub runnable_ns: u64,
    /// Other observed thread states (not a diagnosis of blocking).
    pub other_ns: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StartupCpuHotspot {
    pub symbol: String,
    pub samples: u64,
}

fn number<T: std::str::FromStr>(s: &str) -> Result<T, PerfettoError> {
    s.parse().map_err(|_| PerfettoError::InvalidResult)
}

impl TraceSession {
    pub fn startup_cpu_hotspots(
        &self,
        package: &str,
        cancel: &CancellationToken,
    ) -> Result<StartupCpuEvidence, PerfettoError> {
        if package.is_empty()
            || package.len() > 255
            || !package
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
        {
            return Err(PerfettoError::InvalidPage);
        }
        self.prepare(cancel)?;
        // Keep *all* candidate launches/processes in the LEFT JOIN. Filtering
        // upstream to just usable rows would hide a second, ambiguous launch.
        let sql = format!(
            "INCLUDE PERFETTO MODULE android.startup.time_to_display;
             SELECT s.startup_id, s.ts, d.time_to_initial_display AS dur,
                    t.utid, t.tid, p.pid
             FROM android_startups s
             LEFT JOIN android_startup_processes a USING(startup_id)
             LEFT JOIN android_startup_time_to_display d ON d.startup_id = s.startup_id
             LEFT JOIN process p ON p.upid = a.upid AND p.upid = d.upid
                  AND p.name = '{package}' AND d.ttid_frame_id IS NOT NULL
             LEFT JOIN thread t ON t.upid = p.upid AND t.is_main_thread = 1 AND t.tid = p.pid
             WHERE s.package = '{package}' LIMIT 2"
        );
        let rows = self
            .run_sql(
                &sql,
                &["startup_id", "ts", "dur", "utid", "tid", "pid"],
                2,
                cancel,
            )?
            .rows;
        let row = match rows.as_slice() {
            [] => {
                return Ok(StartupCpuEvidence::Unavailable(
                    "No startup for the target package in this trace.".into(),
                ));
            }
            [row] => row,
            _ => {
                return Ok(StartupCpuEvidence::Unavailable(
                    "Ambiguous target launch or process in this trace.".into(),
                ));
            }
        };
        if row[2] == "[NULL]" {
            return Ok(StartupCpuEvidence::Unavailable(
                "No first-display frame for the target launch.".into(),
            ));
        }
        if row[3..].iter().any(|s| s == "[NULL]") {
            return Ok(StartupCpuEvidence::Unavailable(
                "Frame, target process and main thread could not be matched.".into(),
            ));
        }
        let startup_id = number(&row[0])?;
        let start_ns: i64 = number(&row[1])?;
        let dur: i64 = number(&row[2])?;
        let utid: u32 = number(&row[3])?;
        let tid = number(&row[4])?;
        let pid = number(&row[5])?;
        if start_ns < 0 || dur <= 0 || tid == 0 || tid != pid {
            return Err(PerfettoError::InvalidResult);
        }
        let end_ns = start_ns
            .checked_add(dur)
            .ok_or(PerfettoError::InvalidResult)?;

        // Limit before computing totals to the exact main thread/window.
        // Unobserved periods, e.g. before process birth, are not "blocked".
        let sql = format!(
            "WITH clipped AS (
               SELECT state, MIN(ts + dur,{end_ns}) - MAX(ts,{start_ns}) AS overlap
               FROM thread_state WHERE utid = {utid} AND ts < {end_ns}
                 AND dur > 0 AND ts + dur > {start_ns} AND state IS NOT NULL AND state != '?'
             ) SELECT
               COALESCE(SUM(CASE WHEN state='Running' THEN overlap END),0) AS running,
               COALESCE(SUM(CASE WHEN state IN ('R','R+') THEN overlap END),0) AS runnable,
               COALESCE(SUM(CASE WHEN state NOT IN ('Running','R','R+') THEN overlap END),0) AS other
             FROM clipped"
        );
        let states = self
            .run_sql(&sql, &["running", "runnable", "other"], 1, cancel)?
            .rows;
        let [state] = states.as_slice() else {
            return Err(PerfettoError::InvalidResult);
        };
        let scheduling = StartupCpuScheduling {
            running_ns: number(&state[0])?,
            runnable_ns: number(&state[1])?,
            other_ns: number(&state[2])?,
        };
        let coverage = scheduling
            .running_ns
            .checked_add(scheduling.runnable_ns)
            .and_then(|n| n.checked_add(scheduling.other_ns))
            .ok_or(PerfettoError::InvalidResult)?;
        if coverage > dur as u64 {
            return Err(PerfettoError::InvalidResult);
        }

        // Each sample contributes to exactly one leaf (including unresolved).
        // Perfetto orders inline symbols innermost first by symbol id.
        let sql = format!(
            "WITH leaves AS MATERIALIZED (
               SELECT CASE WHEN COALESCE(ps.unwind_error,'none') = 'none' THEN COALESCE(
                  NULLIF((SELECT sym.name FROM stack_profile_symbol sym
                   WHERE sym.symbol_set_id=f.symbol_set_id ORDER BY sym.id LIMIT 1),''),
                  NULLIF(f.deobfuscated_name,''), NULLIF(f.name,'')) END AS name
               FROM perf_sample ps
               LEFT JOIN stack_profile_callsite c ON c.id = ps.callsite_id
               LEFT JOIN stack_profile_frame f ON f.id = c.frame_id
               WHERE ps.utid = {utid} AND ps.ts >= {start_ns} AND ps.ts < {end_ns}
             ), grouped AS (
               SELECT CASE WHEN lower(trim(name)) NOT IN ('','[unknown]','unknown','??') THEN name END AS sym, COUNT(*) AS n
               FROM leaves GROUP BY sym
             ), top AS (
               SELECT sym, n FROM grouped WHERE sym IS NOT NULL ORDER BY n DESC, sym LIMIT 12
             ) SELECT 0 AS kind, '' AS symbol, COALESCE(SUM(n),0) AS n,
                 COALESCE(SUM(CASE WHEN sym IS NULL THEN n END),0) AS unresolved,
                 (SELECT COALESCE(SUM(value),0) FROM stats WHERE name='perf_cpu_lost_records') AS lost FROM grouped
               UNION ALL SELECT 1, hex(CAST(CASE WHEN length(sym)>256 THEN substr(sym,1,255)||'…' ELSE sym END AS BLOB)), n, 0, 0 FROM top
               ORDER BY kind, n DESC, symbol"
        );
        let rows = self
            .run_sql(
                &sql,
                &["kind", "symbol", "n", "unresolved", "lost"],
                13,
                cancel,
            )?
            .rows;
        let (summary, top) = rows.split_first().ok_or(PerfettoError::InvalidResult)?;
        if summary[0] != "0" || top.len() > 12 {
            return Err(PerfettoError::InvalidResult);
        }
        let total_samples: u64 = number(&summary[2])?;
        let unresolved_samples: u64 = number(&summary[3])?;
        let trace_perf_lost_records: u64 = number(&summary[4])?;
        let mut accounted = unresolved_samples;
        let mut hotspots = Vec::with_capacity(top.len());
        for entry in top {
            let samples: u64 = number(&entry[2])?;
            if entry[0] != "1" || samples == 0 {
                return Err(PerfettoError::InvalidResult);
            }
            accounted = accounted
                .checked_add(samples)
                .ok_or(PerfettoError::InvalidResult)?;
            let symbol = decode_hex(&entry[1])?
                .chars()
                .map(|c| if c.is_control() { ' ' } else { c })
                .collect();
            hotspots.push(StartupCpuHotspot { symbol, samples });
        }
        if accounted > total_samples {
            return Err(PerfettoError::InvalidResult);
        }
        Ok(StartupCpuEvidence::Measured(StartupCpuWindow {
            startup_id,
            pid,
            tid,
            start_ns,
            end_ns,
            scheduling: (coverage > 0).then_some(scheduling),
            total_samples,
            unresolved_samples,
            trace_perf_lost_records,
            hotspots,
        }))
    }
}
