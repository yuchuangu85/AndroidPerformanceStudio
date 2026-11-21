//! Wall-time overlap of explicit APS instrumentation with an ACTUAL app frame.
//! A display token is shared across layers/processes and isn't a join key.
use crate::{PerfettoError, TraceSession};
use aps_platform::CancellationToken;

// Retain the Perfetto-facing API; the pure encoding also belongs to offline reports.
pub use aps_frame::probe::{PER_FRAME, Probe, ThreadRole, valid_marker};

const MAX_MATCHES: usize = 4096;

impl TraceSession {
    pub fn frame_probes(
        &self,
        pid: Option<i32>,
        cancel: &CancellationToken,
    ) -> Result<Vec<(i64, Probe)>, PerfettoError> {
        if pid.is_some_and(|pid| pid <= 0) {
            return Err(PerfettoError::InvalidPage);
        }
        self.prepare(cancel)?;
        let target = pid.map_or(String::new(), |pid| format!("AND t.upid = (SELECT upid FROM process WHERE pid={pid} ORDER BY start_ts DESC LIMIT 1)"));
        let sql = format!(
            "WITH markers AS MATERIALIZED (
          SELECT s.id, s.name, s.ts, s.dur, t.utid, t.upid, t.tid,
                 CASE WHEN t.tid=p.pid THEN 'Main' WHEN t.name='RenderThread' THEN 'RenderThread' ELSE 'Other' END AS role
          FROM slice s JOIN thread_track tr ON s.track_id=tr.id JOIN thread t USING(utid) JOIN process p ON p.upid=t.upid
          WHERE s.dur>0 AND length(s.name)=26 AND s.name GLOB 'APS.Frame/*'
            AND substr(s.name,11) NOT GLOB '*[^0-9a-f]*' {target}
        ), hits AS MATERIALIZED (
          SELECT a.id AS frame, m.id AS probe_id, m.name, m.utid, m.tid, m.role,
                 max(a.ts,m.ts) AS lo, min(a.ts+a.dur,m.ts+m.dur) AS hi
          FROM markers m JOIN actual_frame_timeline_slice a ON a.upid=m.upid
          WHERE a.dur>0 AND m.ts<a.ts+a.dur AND a.ts<m.ts+m.dur
          LIMIT {limit}
        )
        SELECT frame, name, tid, role, hi-lo AS overlap_ns,
          (SELECT sum(min(hi,r.ts+r.dur)-max(lo,r.ts))
           FROM sched r WHERE r.utid=h.utid AND r.dur>0
           AND r.ts<hi AND r.ts+r.dur>lo) AS scheduled_ns
        FROM hits h ORDER BY frame, overlap_ns DESC, probe_id",
            limit = MAX_MATCHES + 1
        );
        let rows = self
            .run_sql(
                &sql,
                &["frame", "name", "tid", "role", "overlap_ns", "scheduled_ns"],
                MAX_MATCHES + 1,
                cancel,
            )?
            .rows;
        if rows.len() > MAX_MATCHES {
            return Err(PerfettoError::OutputLimit);
        }
        rows.into_iter()
            .map(|row| {
                let [id, name, tid, role, overlap, running]: [String; 6] =
                    row.try_into().map_err(|_| PerfettoError::InvalidResult)?;
                let int = |s: &str| s.parse::<i64>().map_err(|_| PerfettoError::InvalidResult);
                let (id, tid, overlap_ns) = (int(&id)?, int(&tid)?, int(&overlap)?);
                let scheduled_ns = if running == "[NULL]" {
                    None
                } else {
                    Some(int(&running)?)
                };
                if !valid_marker(&name)
                    || id < 0
                    || tid <= 0
                    || overlap_ns <= 0
                    || scheduled_ns.is_some_and(|n| !(0..=overlap_ns).contains(&n))
                {
                    return Err(PerfettoError::InvalidResult);
                }
                Ok((
                    id,
                    Probe {
                        marker: name,
                        role: ThreadRole::parse(&role).ok_or(PerfettoError::InvalidResult)?,
                        tid,
                        overlap_ns,
                        scheduled_ns,
                    },
                ))
            })
            .collect()
    }
}
