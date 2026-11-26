//! Parser for the per-process, human-readable `dumpsys meminfo -a` report.

use std::collections::BTreeMap;

#[derive(Debug)]
pub(super) struct MeminfoSample {
    pub metrics: BTreeMap<MetricKey, u64>,
    pub raw: String,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub(super) struct MetricKey {
    pub section: MetricSection,
    pub category: String,
    pub measure: String,
    pub unit: MetricUnit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(super) enum MetricSection {
    Summary,
    Detail,
    Objects,
    Sql,
    Budget,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub(super) enum MetricUnit {
    Kib,
    Count,
}

use MetricSection::{Budget, Detail, Objects, Sql, Summary};
use MetricUnit::{Count, Kib};

type Metrics = BTreeMap<MetricKey, u64>;
type Columns = Vec<(&'static str, usize)>; // Measure and right-aligned column end.

pub(super) fn parse_meminfo(
    stdout: &str,
    expected_pid: u32,
    expected_name: &str,
) -> Result<MeminfoSample, String> {
    let mut lines = stdout.lines();
    let (pid, name) = lines
        .find_map(process_heading)
        .ok_or_else(|| "meminfo is missing a valid per-process header".to_string())?;
    if pid != expected_pid || name != expected_name {
        return Err(format!(
            "meminfo process mismatch: expected {expected_pid} [{expected_name}], got {pid} [{name}]"
        ));
    }

    let mut metrics = Metrics::new();
    let mut section = Some(Detail);
    let mut detail_top = None;
    let mut detail_columns = Columns::new();
    let mut summary_columns = Columns::new();
    let mut budget_columns = Columns::new();
    for line in lines {
        let trimmed = line.trim();
        if trimmed.starts_with("** MEMINFO in pid ") {
            return Err("meminfo contains multiple per-process headers".into());
        }
        match trimmed.trim_end_matches(':') {
            "App Summary" => section = Some(Summary),
            "Objects" => section = Some(Objects),
            "SQL" => section = Some(Sql),
            "Memory Budget" => section = Some(Budget),
            "Dalvik Details" => section = Some(Detail),
            "DATABASES" | "Asset Allocations" | "Unreachable memory" | "Native Allocations" => {
                section = None;
            }
            _ => match section {
                Some(Detail) => {
                    if trimmed.starts_with("Pss ") || trimmed.starts_with("Pss\t") {
                        detail_top = Some(line);
                    } else if let Some(top) = detail_top.take() {
                        detail_columns = detail_header(top, line);
                    } else {
                        table_row(&mut metrics, Detail, &detail_columns, line);
                    }
                }
                Some(Summary) => {
                    if trimmed.starts_with("Pss(KB)") || trimmed.starts_with("Rss(KB)") {
                        summary_columns = words(line)
                            .filter_map(|(word, _, end)| match word {
                                "Pss(KB)" => Some(("PSS", end)),
                                "Rss(KB)" => Some(("RSS", end)),
                                _ => None,
                            })
                            .collect();
                    } else if trimmed.starts_with("TOTAL") {
                        for (label, value) in pairs(line) {
                            let measure = match label {
                                "TOTAL" | "TOTAL PSS" => "PSS",
                                "TOTAL RSS" => "RSS",
                                "TOTAL SWAP (KB)" | "TOTAL SWAP" => "Swap",
                                "TOTAL SWAP PSS (KB)" | "TOTAL SWAP PSS" => "Swap PSS",
                                _ => continue,
                            };
                            insert(&mut metrics, Summary, "Total", measure, Kib, value);
                        }
                    } else {
                        table_row(&mut metrics, Summary, &summary_columns, line);
                    }
                }
                Some(Objects) => {
                    for (label, value) in pairs(line) {
                        let (measure, unit) = if label == "Parcel memory" {
                            ("Size", Kib)
                        } else {
                            ("Count", Count)
                        };
                        insert(&mut metrics, Objects, label, measure, unit, value);
                    }
                }
                Some(Sql) => {
                    for (label, value) in pairs(line) {
                        match label {
                            "MEMORY_USED" | "PAGECACHE_OVERFLOW" | "MALLOC_SIZE" => {
                                insert(&mut metrics, Sql, label, "Size", Kib, value);
                            }
                            _ => {}
                        }
                    }
                }
                Some(Budget) => {
                    if trimmed.starts_with("Process(KB)") || trimmed.starts_with("Package(KB)") {
                        budget_columns = words(line)
                            .filter_map(|(word, _, end)| match word {
                                "Process(KB)" => Some(("Process", end)),
                                "Package(KB)" => Some(("Package", end)),
                                _ => None,
                            })
                            .collect();
                    } else {
                        budget_row(&mut metrics, &budget_columns, line);
                    }
                }
                None => {}
            },
        }
    }

    if !metrics.keys().any(|key| {
        matches!(key.section, Summary | Detail) && matches!(key.measure.as_str(), "PSS" | "RSS")
    }) {
        return Err("meminfo does not contain a readable process memory table".into());
    }
    Ok(MeminfoSample {
        metrics,
        raw: stdout.to_owned(),
    })
}

fn process_heading(line: &str) -> Option<(u32, &str)> {
    let (pid, rest) = line
        .trim()
        .strip_prefix("** MEMINFO in pid ")?
        .split_once('[')?;
    let name = rest.strip_suffix("**")?.trim_end().strip_suffix(']')?;
    Some((pid.trim().parse().ok()?, name))
}

fn insert(
    metrics: &mut Metrics,
    section: MetricSection,
    category: &str,
    measure: &str,
    unit: MetricUnit,
    value: u64,
) {
    metrics.insert(
        MetricKey {
            section,
            category: category.to_owned(),
            measure: measure.to_owned(),
            unit,
        },
        value,
    );
}

fn words(line: &str) -> impl DoubleEndedIterator<Item = (&str, usize, usize)> + Clone {
    line.split_ascii_whitespace().map(move |word| {
        let start = word.as_ptr() as usize - line.as_ptr() as usize;
        (word, start, start + word.len())
    })
}

fn detail_header(top: &str, bottom: &str) -> Columns {
    let top = words(top);
    let bottom = words(bottom);
    if top.clone().count() != bottom.clone().count() {
        return Columns::new();
    }
    top.zip(bottom)
        .map(|((top, _, _), (bottom, _, end))| {
            let measure = match (top, bottom) {
                ("Pss", "Total") => "PSS",
                ("Pss", "Clean") => "PSS Clean",
                ("Shared", "Dirty") => "Shared Dirty",
                ("Shared", "Clean") => "Shared Clean",
                ("Private", "Dirty") => "Private Dirty",
                ("Private", "Clean") => "Private Clean",
                ("Swapped" | "Swap", "Dirty") => "Swap Dirty",
                ("SwapPss", "Dirty") | ("Swap", "Pss") => "Swap PSS",
                ("Rss", "Total") => "RSS",
                ("Heap", "Size") => "Heap Size",
                ("Heap", "Alloc") => "Heap Alloc",
                ("Heap", "Free") => "Heap Free",
                _ => "", // Keep the slot even when its meaning is unknown.
            };
            (measure, end)
        })
        .collect()
}

fn table_row(metrics: &mut Metrics, section: MetricSection, columns: &Columns, line: &str) {
    if columns.is_empty() {
        return;
    }
    let mut cells = Vec::new();
    for (word, start, end) in words(line).rev().take(columns.len()) {
        let value = word.parse::<u64>().ok();
        // Signed negative counters (e.g. residual Unknown) cannot be stored
        // as u64, but must still occupy their original column.
        let numeric = word
            .trim_start_matches(['+', '-'])
            .bytes()
            .all(|byte| byte.is_ascii_digit());
        if value.is_none() && !numeric && word != "N/A" {
            break;
        }
        cells.push((value, start, end));
    }
    cells.reverse();
    let Some(&(_, first, _)) = cells.first() else {
        return;
    };
    let label = line[..first].trim().trim_end_matches(':');
    if label.is_empty() {
        return;
    }
    // Full rows use header order, as do compact detail rows with the heap
    // columns omitted. Sparse rows use the right edges to keep interior gaps.
    let aligned = cells
        .iter()
        .all(|&(_, _, end)| columns.iter().any(|&(_, column_end)| end == column_end));
    let detail_prefix = section == Detail
        && columns[cells.len()..]
            .iter()
            .all(|&(measure, _)| measure.starts_with("Heap "));
    for (index, &(value, _, end)) in cells.iter().enumerate() {
        let Some(value) = value else { continue };
        let measure = if cells.len() == columns.len() {
            columns[index].0
        } else if aligned {
            columns.iter().find(|&&(_, col)| col == end).unwrap().0
        } else if detail_prefix {
            columns[index].0
        } else if section == Summary && label == "Unknown" {
            // AOSP's Unknown row is RSS-only. Keep that meaning even if the
            // report has had its alignment collapsed by a caller.
            "RSS"
        } else if section == Summary {
            columns[index].0
        } else {
            continue;
        };
        if !measure.is_empty() {
            let category = if section == Detail && label == "TOTAL" {
                "Total"
            } else {
                label
            };
            insert(metrics, section, category, measure, Kib, value);
        }
    }
}

fn budget_row(metrics: &mut Metrics, columns: &[(&str, usize)], line: &str) {
    let Some((label, values)) = line.split_once(':') else {
        return;
    };
    let value_offset = label.len() + 1;
    let label = label.trim();
    let unit = match label {
        "Throttle Events" | "Refaults (Anon)" | "Refaults (File)" => Count,
        "Current Usage" | "Limit" | "Peak Usage" | "Swap Used" | "Anonymous" | "File"
        | "File Mapped" | "Shared Memory" | "Kernel" | "Page Tables" | "Slab" => Kib,
        // Unknown raw cgroup counters may be sizes or event counts.
        _ => return,
    };
    let cells: Vec<_> = words(values).collect();
    for (index, &(word, _, end)) in cells.iter().enumerate() {
        let Ok(value) = word.parse() else { continue };
        let column = if cells.len() == columns.len() {
            columns.get(index)
        } else {
            columns.iter().find(|&&(_, col)| col == value_offset + end)
        };
        if let Some(&(scope, _)) = column {
            insert(metrics, Budget, label, scope, unit, value);
        }
    }
}

/// Read consecutive `label: value` pairs. Split each numeric field from the
/// following label so two counters on one line do not obscure each other.
fn pairs(mut line: &str) -> impl Iterator<Item = (&str, u64)> {
    std::iter::from_fn(move || {
        while let Some((label, tail)) = line.split_once(':') {
            let tail = tail.trim_start();
            let end = tail.find(char::is_whitespace).unwrap_or(tail.len());
            let (value, rest) = tail.split_at(end);
            line = rest;
            if let Ok(value) = value.parse() {
                return Some((label.trim(), value));
            }
        }
        None
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn get(
        sample: &MeminfoSample,
        section: MetricSection,
        category: &str,
        measure: &str,
        unit: MetricUnit,
    ) -> Option<u64> {
        sample
            .metrics
            .get(&MetricKey {
                section,
                category: category.into(),
                measure: measure.into(),
                unit,
            })
            .copied()
    }

    #[test]
    fn parses_modern_report_without_losing_raw_or_missing_cells() {
        // AOSP right-aligns these fields independently. Keep the empty PSS
        // slot for Unknown and the empty RSS slot on the synthetic OEM row.
        let summary = format!(
            "{:>23} {:>10} {:>10}\n{:>23} {:>10} {:>10}\n{:>23} {:>10} {:>10}\n{:>23} {:>10} {:>10}\n{:>23} {:>10} {:>10}\n",
            "",
            "Pss(KB)",
            "Rss(KB)",
            "Java Heap:",
            "17",
            "120",
            "Native Heap:",
            "0",
            "22",
            "Vendor Memory:",
            "9",
            "",
            "Unknown:",
            "",
            "0",
        );
        let raw = format!(
            "adb notice\r\n\r\n** MEMINFO in pid 42 [com.example:remote] **\r\n\
             Pss Private Private SwapPss Rss Heap Heap Heap\n\
             Total Dirty Clean Dirty Total Size Alloc Free\n\
             Native Heap 1 2 3 4 5 6 7 0\n\
             Dalvik Heap 10 11 12 13 14 15 16 17\n\
             .apk mmap 0 0 20 0 30\n\
             .custom mmap 5 6 7 8 9\n\
             TOTAL 21 22 23 24 25 26 27 28\n\
             Dalvik Details\n\
             .Heap 2 3 4 5 6\n\
             .Boot vdex mmap 7 8 9 10 11\n\
             App Summary\n{summary}\
             TOTAL PSS: 201 TOTAL RSS: 300 TOTAL SWAP PSS: 41\n\
             Objects\n\
             Views: 0 ViewRootImpl: 2\n\
             AppContexts: 9 Activities: 0\n\
             Parcel memory: 9 Parcel count: 4\n\
             SQL\n\
             MEMORY_USED: 0\n\
             PAGECACHE_OVERFLOW: 3 MALLOC_SIZE: 7\n\
             DATABASES\n\
             pgsz dbsz Lookaside(b) cache Dbname\n\
             4 72 20 8/9/0 /data/local/db.sqlite\n\
             Memory Budget\n\
             Process(KB) Package(KB)\n\
             Current Usage: 81 91\n\
             Limit: none 340\n\
             Limit Source: none MemoryLimiter\n\
             Peak Usage: N/A 120\n\
             Swap Used: 0 0\n\
             Usage Breakdown\n\
             Anonymous: 66 71\n\
             Pressure & Reclaim\n\
             Throttle Events: 0 2\n\
             Unreachable memory\n\
             not a table: 99\n"
        );
        let sample = parse_meminfo(&raw, 42, "com.example:remote").unwrap();
        assert_eq!(sample.raw, raw);
        for (section, category, measure, unit, value) in [
            (Detail, "Native Heap", "Heap Free", Kib, 0),
            (Detail, "Dalvik Heap", "Heap Alloc", Kib, 16),
            (Detail, ".apk mmap", "PSS", Kib, 0),
            (Detail, ".custom mmap", "Private Clean", Kib, 7),
            (Detail, ".Heap", "Swap PSS", Kib, 5),
            (Detail, ".Boot vdex mmap", "RSS", Kib, 11),
            (Detail, "Total", "PSS", Kib, 21),
            (Summary, "Java Heap", "PSS", Kib, 17),
            (Summary, "Java Heap", "RSS", Kib, 120),
            (Summary, "Native Heap", "PSS", Kib, 0),
            (Summary, "Vendor Memory", "PSS", Kib, 9),
            (Summary, "Unknown", "RSS", Kib, 0),
            (Summary, "Total", "PSS", Kib, 201),
            (Summary, "Total", "RSS", Kib, 300),
            (Summary, "Total", "Swap PSS", Kib, 41),
            (Objects, "Views", "Count", Count, 0),
            (Objects, "ViewRootImpl", "Count", Count, 2),
            (Objects, "Parcel memory", "Size", Kib, 9),
            (Objects, "Parcel count", "Count", Count, 4),
            (Sql, "MEMORY_USED", "Size", Kib, 0),
            (Sql, "PAGECACHE_OVERFLOW", "Size", Kib, 3),
            (Sql, "MALLOC_SIZE", "Size", Kib, 7),
            (Budget, "Current Usage", "Process", Kib, 81),
            (Budget, "Current Usage", "Package", Kib, 91),
            (Budget, "Limit", "Package", Kib, 340),
            (Budget, "Peak Usage", "Package", Kib, 120),
            (Budget, "Swap Used", "Process", Kib, 0),
            (Budget, "Anonymous", "Package", Kib, 71),
            (Budget, "Throttle Events", "Process", Count, 0),
            (Budget, "Throttle Events", "Package", Count, 2),
        ] {
            assert_eq!(
                get(&sample, section, category, measure, unit),
                Some(value),
                "{category} {measure}"
            );
        }
        assert_eq!(get(&sample, Summary, "Unknown", "PSS", Kib), None);
        assert_eq!(get(&sample, Summary, "Vendor Memory", "RSS", Kib), None);
        assert_eq!(get(&sample, Detail, ".apk mmap", "Heap Size", Kib), None);
        assert_eq!(get(&sample, Summary, "Total", "Swap", Kib), None);
        assert_eq!(
            sample
                .metrics
                .keys()
                .filter(|key| key.section == Sql)
                .count(),
            3
        );
        assert_eq!(get(&sample, Budget, "Limit", "Process", Kib), None);
        assert_eq!(get(&sample, Budget, "Peak Usage", "Process", Kib), None);
        assert_eq!(get(&sample, Budget, "Limit Source", "Package", Kib), None);
    }

    #[test]
    fn parses_legacy_full_columns_and_pss_only_summary() {
        let sample = parse_meminfo(
            "** MEMINFO in pid 1 [system] **\n\
             Pss Pss Shared Private Shared Private Swapped Heap Heap Heap\n\
             Total Clean Dirty Dirty Clean Clean Dirty Size Alloc Free\n\
             Ashmem 1 2 3 4 5 6 0\n\
             .IndirectRef 7 - 0 10 11 12 13\n\
             TOTAL 14 15 16 17 18 19 20 21 22 23\n\
             App Summary\n\
             Pss(KB)\n\
             Java Heap: 2\n\
             Native Heap: 0\n\
             TOTAL: 14 TOTAL SWAP (KB): 20\n",
            1,
            "system",
        )
        .unwrap();
        assert_eq!(get(&sample, Detail, "Ashmem", "PSS Clean", Kib), Some(2));
        assert_eq!(get(&sample, Detail, "Ashmem", "Shared Dirty", Kib), Some(3));
        assert_eq!(get(&sample, Detail, "Ashmem", "Swap Dirty", Kib), Some(0));
        assert_eq!(get(&sample, Detail, ".IndirectRef", "PSS Clean", Kib), None);
        assert_eq!(
            get(&sample, Detail, ".IndirectRef", "Shared Dirty", Kib),
            Some(0)
        );
        assert_eq!(get(&sample, Detail, "Total", "Heap Free", Kib), Some(23));
        assert_eq!(get(&sample, Summary, "Native Heap", "PSS", Kib), Some(0));
        assert_eq!(get(&sample, Summary, "Native Heap", "RSS", Kib), None);
        assert_eq!(get(&sample, Summary, "Total", "Swap", Kib), Some(20));
        assert_eq!(get(&sample, Summary, "Total", "Swap PSS", Kib), None);
    }

    #[test]
    fn parses_old_table_without_app_summary() {
        let sample = parse_meminfo(
            "** MEMINFO in pid 1 [system] **\n\
             Pss   Private  Private   Heap   Heap  Heap\n\
             Total   Dirty    Clean   Size  Alloc  Free\n\
             TOTAL       0        1      2      3     4   5\n\
             Unknown 6 -2 8\n",
            1,
            "system",
        )
        .unwrap();
        assert_eq!(get(&sample, Detail, "Total", "PSS", Kib), Some(0));
        assert_eq!(get(&sample, Detail, "Total", "Heap Alloc", Kib), Some(4));
        assert_eq!(get(&sample, Detail, "Unknown", "PSS", Kib), Some(6));
        assert_eq!(get(&sample, Detail, "Unknown", "Private Dirty", Kib), None);
        assert_eq!(
            get(&sample, Detail, "Unknown", "Private Clean", Kib),
            Some(8)
        );
        assert!(!sample.metrics.keys().any(|key| key.section == Summary));
    }

    #[test]
    fn parses_compact_summary_and_keeps_missing_totals_absent() {
        let sample = parse_meminfo(
            "** MEMINFO in pid 1 [system] **\n\
             App Summary\n\
             Pss(KB)\tRss(KB)\n\
             Native Heap: 12 28\n\
             Unknown: 85\n\
             TOTAL PSS: N/A TOTAL RSS: 113\n\
             TOTAL SWAP PSS: 0\n",
            1,
            "system",
        )
        .unwrap();
        assert_eq!(get(&sample, Summary, "Unknown", "RSS", Kib), Some(85));
        assert_eq!(get(&sample, Summary, "Unknown", "PSS", Kib), None);
        assert_eq!(get(&sample, Summary, "Total", "PSS", Kib), None);
        assert_eq!(get(&sample, Summary, "Total", "RSS", Kib), Some(113));
        assert_eq!(get(&sample, Summary, "Total", "Swap PSS", Kib), Some(0));
    }

    #[test]
    fn rejects_wrong_identity_or_unreadable_reports() {
        let raw = "** MEMINFO in pid 42 [com.example:remote] **\nApp Summary\nTOTAL PSS: 0\n";
        assert!(
            parse_meminfo(raw, 43, "com.example:remote")
                .unwrap_err()
                .contains("mismatch")
        );
        assert!(
            parse_meminfo(raw, 42, "com.example")
                .unwrap_err()
                .contains("mismatch")
        );
        assert!(parse_meminfo(&format!("{raw}{raw}"), 42, "com.example:remote").is_err());
        assert!(parse_meminfo("No process found for: 42", 42, "com.example:remote").is_err());
        assert!(
            parse_meminfo(
                "** MEMINFO in pid 42 [com.example:remote] **\nObjects\nViews: 0",
                42,
                "com.example:remote"
            )
            .is_err()
        );
    }
}
