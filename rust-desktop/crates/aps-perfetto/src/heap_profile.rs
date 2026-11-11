//! Bounded heapprofd projections. ART rows are positive allocation samples,
//! never exact object allocations, class identities, GC or retention evidence.
use super::*;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HeapProfileKind {
    Native,
    Art,
}

#[derive(Clone, Debug)]
pub struct HeapProfileRow {
    pub upid: u64,
    pub pid: u32,
    pub callsite_id: u64,
    pub sample_rows: u64,
    pub allocated_count: i64,
    pub allocated_bytes: i64,
    pub net_count: i64,
    pub net_bytes: i64,
    pub leaf_frame: Option<String>,
}

#[derive(Clone, Debug)]
pub struct HeapProfilePage {
    pub kind: HeapProfileKind,
    pub source_digest: SourceDigest,
    pub rows: Vec<HeapProfileRow>,
    pub offset: usize,
    pub has_more: bool,
}

impl TraceSession {
    /// All SQL is fixed here. Optional PID is a validated integer, not SQL text.
    /// Native net values are signed sampled accounting over the recorded trace,
    /// not the full process live heap. Pages must not be summed as global totals.
    pub fn heap_profile(
        &self,
        kind: HeapProfileKind,
        pid: Option<u32>,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<HeapProfilePage, PerfettoError> {
        if limit == 0
            || limit > MAX_PAGE_SIZE
            || offset > i64::MAX as usize
            || offset.checked_add(limit).is_none()
            || pid == Some(0)
        {
            return Err(PerfettoError::InvalidPage);
        }
        self.prepare(cancel)?;
        let heap = match kind {
            HeapProfileKind::Native => "a.heap_name != 'com.android.art'",
            HeapProfileKind::Art => {
                "a.heap_name = 'com.android.art' AND a.count > 0 AND a.size > 0"
            }
        };
        let target = pid
            .map(|pid| format!(" AND p.pid = {pid}"))
            .unwrap_or_default();
        // Hex-encode only text before the tool's CSV encoder; names can contain
        // commas/newlines/quotes and must never change the result column shape.
        let sql = format!(
            "SELECT a.upid,p.pid,a.callsite_id,COUNT(*) AS sample_rows,\
            SUM(CASE WHEN a.count > 0 THEN a.count ELSE 0 END) AS allocated_count,\
            SUM(CASE WHEN a.size > 0 THEN a.size ELSE 0 END) AS allocated_bytes,\
            SUM(a.count) AS net_count,SUM(a.size) AS net_bytes,\
            CASE WHEN f.name IS NULL OR f.name = '' THEN NULL ELSE hex(CAST(substr(f.name,1,4096) AS BLOB)) END AS leaf_frame \
            FROM heap_profile_allocation a JOIN process p USING(upid) \
            LEFT JOIN stack_profile_callsite c ON c.id = a.callsite_id \
            LEFT JOIN stack_profile_frame f ON f.id = c.frame_id \
            WHERE {heap}{target} GROUP BY a.upid,p.pid,a.callsite_id,f.name \
            ORDER BY allocated_bytes DESC,a.upid,a.callsite_id LIMIT {} OFFSET {offset}",
            limit + 1
        );
        let columns = &[
            "upid",
            "pid",
            "callsite_id",
            "sample_rows",
            "allocated_count",
            "allocated_bytes",
            "net_count",
            "net_bytes",
            "leaf_frame",
        ];
        let table = self.run_sql(&sql, columns, limit + 1, cancel)?;
        let has_more = table.rows.len() > limit;
        let mut rows = Vec::new();
        for row in table.rows.into_iter().take(limit) {
            let number = |i: usize| {
                row.get(i)
                    .and_then(|v| v.parse::<i64>().ok())
                    .ok_or(PerfettoError::InvalidResult)
            };
            let unsigned = |i: usize| {
                number(i).and_then(|v| u64::try_from(v).map_err(|_| PerfettoError::InvalidResult))
            };
            rows.push(HeapProfileRow {
                upid: unsigned(0)?,
                pid: u32::try_from(unsigned(1)?).map_err(|_| PerfettoError::InvalidResult)?,
                callsite_id: unsigned(2)?,
                sample_rows: unsigned(3)?,
                allocated_count: number(4)?,
                allocated_bytes: number(5)?,
                net_count: number(6)?,
                net_bytes: number(7)?,
                leaf_frame: match row.get(8).map(String::as_str) {
                    Some("[NULL]") => None,
                    Some(value) => Some(decode_hex(value)?),
                    None => return Err(PerfettoError::InvalidResult),
                },
            });
        }
        Ok(HeapProfilePage {
            kind,
            source_digest: self.digest,
            rows,
            offset,
            has_more,
        })
    }
}
