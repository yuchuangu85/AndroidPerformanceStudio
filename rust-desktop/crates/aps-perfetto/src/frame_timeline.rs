//! Fixed Android 12+ FrameTimeline query used by the Frame Analyzer.
//!
//! The SQL mirrors Compose FrameTimelineTraceAdapter.timelineQuery. Callers
//! cannot inject SQL: the only variable is a validated positive process id.

use crate::PerfettoError;

pub const MAX_FRAME_TIMELINE_ROWS: usize = 200_000;

pub const COLUMNS: &[&str] = &[
    "frame_id",
    "expected_ts",
    "expected_dur",
    "actual_ts",
    "actual_dur",
    "surface_token",
    "layer_name",
    "jank_type",
    "jank_tag",
    "present_type",
    "process_name",
    "surface_flinger_jank_type",
];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FrameTimelineRow {
    pub frame_id: i64,
    pub expected_ts: Option<i64>,
    pub expected_dur: Option<i64>,
    pub actual_ts: Option<i64>,
    pub actual_dur: Option<i64>,
    pub surface_token: Option<i64>,
    pub layer_name: Option<String>,
    pub jank_type: Option<String>,
    pub jank_tag: Option<String>,
    pub present_type: Option<String>,
    pub process_name: Option<String>,
    pub surface_flinger_jank_type: Option<String>,
}

pub(crate) fn sql(process_id: Option<i32>) -> String {
    let predicate = process_id.map_or_else(String::new, |pid| {
        format!(
            "AND e.upid = (SELECT upid FROM process WHERE pid = {pid} ORDER BY start_ts DESC LIMIT 1)"
        )
    });
    format!(
        "SELECT e.display_frame_token AS frame_id, \
         e.ts AS expected_ts, \
         e.dur AS expected_dur, \
         a.ts AS actual_ts, \
         a.dur AS actual_dur, \
         COALESCE(a.surface_frame_token, e.surface_frame_token) AS surface_token, \
         COALESCE(a.layer_name, e.layer_name) AS layer_name, \
         a.jank_type AS jank_type, \
         a.jank_tag AS jank_tag, \
         a.present_type AS present_type, \
         p.name AS process_name, \
         (SELECT sf.jank_type FROM actual_frame_timeline_slice AS sf \
            JOIN process AS sfp ON sfp.upid = sf.upid \
           WHERE sf.display_frame_token = e.display_frame_token \
             AND lower(sfp.name) GLOB '*surfaceflinger*' \
           ORDER BY sf.id LIMIT 1) AS surface_flinger_jank_type \
         FROM expected_frame_timeline_slice AS e \
         LEFT JOIN actual_frame_timeline_slice AS a \
           ON a.upid = e.upid \
          AND a.display_frame_token = e.display_frame_token \
          AND a.surface_frame_token IS e.surface_frame_token \
         LEFT JOIN process AS p ON p.upid = e.upid \
         WHERE e.display_frame_token IS NOT NULL {predicate} \
         ORDER BY e.ts"
    )
}

pub(crate) fn decode(rows: Vec<Vec<String>>) -> Result<Vec<FrameTimelineRow>, PerfettoError> {
    rows.into_iter()
        .map(|row| {
            if row.len() != COLUMNS.len() {
                return Err(PerfettoError::InvalidResult);
            }
            let mut cells = row.into_iter();
            let frame_id = int(cells.next())?.ok_or(PerfettoError::InvalidResult)?;
            Ok(FrameTimelineRow {
                frame_id,
                expected_ts: int(cells.next())?,
                expected_dur: int(cells.next())?,
                actual_ts: int(cells.next())?,
                actual_dur: int(cells.next())?,
                surface_token: int(cells.next())?,
                layer_name: text(cells.next()),
                jank_type: text(cells.next()),
                jank_tag: text(cells.next()),
                present_type: text(cells.next()),
                process_name: text(cells.next()),
                surface_flinger_jank_type: text(cells.next()),
            })
        })
        .collect()
}

fn int(cell: Option<String>) -> Result<Option<i64>, PerfettoError> {
    match cell.as_deref() {
        None | Some("[NULL]") | Some("") => Ok(None),
        Some(value) => value
            .parse::<i64>()
            .map(Some)
            .map_err(|_| PerfettoError::InvalidResult),
    }
}

fn text(cell: Option<String>) -> Option<String> {
    match cell.as_deref() {
        None | Some("[NULL]") => None,
        Some(value) => Some(value.to_owned()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sql_only_contains_a_validated_integer_pid() {
        assert!(!sql(None).contains("AND e.upid"));
        let with_pid = sql(Some(1234));
        assert!(with_pid.contains("pid = 1234"));
        assert!(!with_pid.contains(';'));
    }

    #[test]
    fn decode_preserves_nulls_and_rejects_short_rows() {
        let row = |id: &str, expected: &str, layer: &str| {
            vec![
                id.into(),
                expected.into(),
                "[NULL]".into(),
                "[NULL]".into(),
                "[NULL]".into(),
                "[NULL]".into(),
                layer.into(),
                "[NULL]".into(),
                "[NULL]".into(),
                "[NULL]".into(),
                "app".into(),
                "[NULL]".into(),
            ]
        };
        let decoded = decode(vec![row("7", "100", "SurfaceView")]).unwrap();
        assert_eq!(decoded[0].frame_id, 7);
        assert_eq!(decoded[0].expected_ts, Some(100));
        assert_eq!(decoded[0].expected_dur, None);
        assert_eq!(decoded[0].layer_name.as_deref(), Some("SurfaceView"));
        assert!(decode(vec![vec!["1".into()]]).is_err());
        assert!(decode(vec![row("nope", "100", "x")]).is_err());
    }
}
