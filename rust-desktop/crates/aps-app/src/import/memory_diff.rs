//! Class-level HPROF comparison. Object IDs never cross the snapshot boundary.
use super::{PAGE, PageData, PageUi, UiText};
use aps_memory::{HistogramQuery, MemorySession, MemoryValue as Value};
use aps_platform::CancellationToken;
use aps_ui_foundation::TextKey;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

const MAX_CLASSES: usize = 20_000;
const MAX_NAME_BYTES: usize = 2 * 1024 * 1024;
const QUERY_PAGE: usize = 200;

#[derive(Clone, Debug, PartialEq, Eq)]
struct ClassMetrics {
    count: u64,
    shallow: u64,
    retained: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct ClassDelta {
    name: String,
    before: ClassMetrics,
    after: ClassMetrics,
}

#[derive(Clone, Debug)]
pub(super) struct DiffReport {
    pub rows: Vec<ClassDelta>,
    pub before_digest: String,
    pub after_digest: String,
}

impl DiffReport {
    pub fn page(&self, offset: usize) -> PageData {
        let rows = self
            .rows
            .iter()
            .skip(offset)
            .take(PAGE)
            .map(|row| {
                vec![
                    row.name.clone(),
                    row.before.count.to_string(),
                    row.after.count.to_string(),
                    signed_delta(row.before.count, row.after.count),
                    row.before.shallow.to_string(),
                    row.after.shallow.to_string(),
                    signed_delta(row.before.shallow, row.after.shallow),
                    row.before.retained.to_string(),
                    row.after.retained.to_string(),
                    signed_delta(row.before.retained, row.after.retained),
                ]
            })
            .collect();
        PageData {
            ui: PageUi::default(),
            summary: format!("Class-level HPROF comparison · {} classes · separate snapshots", self.rows.len()),
            columns: ["Class","A Count","B Count","Δ Count","A Shallow","B Shallow","Δ Shallow","A Retained","B Retained","Δ Retained"]
                .into_iter().map(str::to_owned).collect(),
            rows,
            offset,
            has_more: offset.saturating_add(PAGE) < self.rows.len(),
            warnings: vec!["Class-level metrics only. Object IDs are local to each dump; no cross-snapshot object identity is inferred.".into()],
            row_actions: Vec::new(),
        }
    }
}

fn signed_delta(before: u64, after: u64) -> String {
    let delta = i128::from(after) - i128::from(before);
    if delta > 0 {
        format!("+{delta}")
    } else {
        delta.to_string()
    }
}

/// Blocking: call only on the background executor. At most 20k class rows and
/// 2 MiB class-name bytes are admitted into the UI-side comparison projection.
pub(super) fn compare(
    before: &Arc<Mutex<MemorySession>>,
    after: &Arc<Mutex<MemorySession>>,
    cancel: &CancellationToken,
) -> Result<DiffReport, UiText> {
    if Arc::ptr_eq(before, after) {
        return Err(TextKey::MemoryDiffSameSnapshot.into());
    }
    let before_digest = before
        .lock()
        .map_err(|_| TextKey::ImportUiMemorySessionUnavailable)?
        .source_digest()
        .to_owned();
    let after_digest = after
        .lock()
        .map_err(|_| TextKey::ImportUiMemorySessionUnavailable)?
        .source_digest()
        .to_owned();
    let before = classes(before, cancel)?;
    let after = classes(after, cancel)?;
    let mut merged = BTreeMap::new();
    for (name, metrics) in before {
        merged.insert(name, (metrics, None));
    }
    for (name, metrics) in after {
        merged
            .entry(name)
            .and_modify(|entry: &mut (ClassMetrics, Option<ClassMetrics>)| {
                entry.1 = Some(metrics.clone())
            })
            .or_insert((
                ClassMetrics {
                    count: 0,
                    shallow: 0,
                    retained: 0,
                },
                Some(metrics),
            ));
    }
    if merged.len() > MAX_CLASSES {
        return Err(TextKey::MemoryDiffLimit.into());
    }
    let mut rows = merged
        .into_iter()
        .map(|(name, (before, after))| ClassDelta {
            name,
            before,
            after: after.unwrap_or(ClassMetrics {
                count: 0,
                shallow: 0,
                retained: 0,
            }),
        })
        .collect::<Vec<_>>();
    rows.sort_by(|a, b| {
        let da = (i128::from(a.after.retained) - i128::from(a.before.retained)).abs();
        let db = (i128::from(b.after.retained) - i128::from(b.before.retained)).abs();
        db.cmp(&da).then_with(|| a.name.cmp(&b.name))
    });
    Ok(DiffReport {
        rows,
        before_digest,
        after_digest,
    })
}

fn classes(
    session: &Arc<Mutex<MemorySession>>,
    cancel: &CancellationToken,
) -> Result<BTreeMap<String, ClassMetrics>, UiText> {
    let mut session = session
        .lock()
        .map_err(|_| TextKey::ImportUiMemorySessionUnavailable)?;
    let mut result = BTreeMap::new();
    let mut name_bytes = 0usize;
    let mut offset = 0usize;
    loop {
        if cancel.is_cancelled() {
            return Err(TextKey::ImportUiImportCancelled.into());
        }
        let value = session
            .histogram_query(None, &HistogramQuery::default(), offset, QUERY_PAGE, cancel)
            .map_err(|_| TextKey::MemoryDiffQueryFailed)?;
        let map = value
            .as_map()
            .map_err(|_| TextKey::ImportUiInvalidMemoryResponse)?;
        let total = positive(map.get("total"))? as usize;
        if total > MAX_CLASSES {
            return Err(TextKey::MemoryDiffLimit.into());
        }
        let rows = map
            .get("rows")
            .and_then(|v| v.as_list().ok())
            .ok_or(TextKey::ImportUiMemoryRowsMissing)?;
        for row in rows {
            let row = row
                .as_map()
                .map_err(|_| TextKey::ImportUiInvalidMemoryResponse)?;
            let name = row
                .get("className")
                .and_then(|v| v.as_str().ok())
                .ok_or(TextKey::ImportUiInvalidMemoryResponse)?;
            name_bytes = name_bytes
                .checked_add(name.len())
                .ok_or(TextKey::MemoryDiffLimit)?;
            if name_bytes > MAX_NAME_BYTES {
                return Err(TextKey::MemoryDiffLimit.into());
            }
            let metrics = ClassMetrics {
                count: positive(row.get("instanceCount"))?,
                shallow: positive(row.get("shallowSize"))?,
                retained: positive(row.get("retainedSize"))?,
            };
            if result.insert(name.to_owned(), metrics).is_some() {
                return Err(TextKey::ImportUiInvalidMemoryResponse.into());
            }
        }
        offset = offset
            .checked_add(rows.len())
            .ok_or(TextKey::MemoryDiffLimit)?;
        if offset >= total {
            break;
        }
        if rows.is_empty() {
            return Err(TextKey::ImportUiMemoryRowsMissing.into());
        }
    }
    Ok(result)
}

fn positive(value: Option<&Value>) -> Result<u64, UiText> {
    value
        .and_then(|v| v.as_int().ok())
        .and_then(|v| u64::try_from(v).ok())
        .ok_or_else(|| TextKey::ImportUiInvalidMemoryResponse.into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[::core::prelude::v1::test]
    fn class_delta_is_signed_and_no_object_identity_is_consulted() {
        assert_eq!(signed_delta(2, 5), "+3");
        assert_eq!(signed_delta(5, 2), "-3");
        assert_eq!(signed_delta(0, 0), "0");
        let report = DiffReport {
            before_digest: "a".into(),
            after_digest: "b".into(),
            rows: vec![ClassDelta {
                name: "example.Class".into(),
                before: ClassMetrics {
                    count: 2,
                    shallow: 20,
                    retained: 30,
                },
                after: ClassMetrics {
                    count: 5,
                    shallow: 50,
                    retained: 70,
                },
            }],
        };
        let page = report.page(0);
        assert_eq!(page.rows[0][3], "+3");
        assert_eq!(page.rows[0][9], "+40");
        assert!(!page.columns.iter().any(|name| name.contains("Object ID")));
    }
}
