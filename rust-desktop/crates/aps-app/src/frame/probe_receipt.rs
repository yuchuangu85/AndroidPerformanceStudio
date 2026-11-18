//! Local-only binding: records what was exported, not what APK was installed.
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Value};
use aps_source::{SourceLocation, SourceSnapshotId, SourceWorkspaceId};
use std::{io::ErrorKind, path::Path, sync::Mutex};
const FILE: &str = "probe-receipts-v1.json";
const MAX: usize = 256;
static LOCK: Mutex<()> = Mutex::new(());
fn limits() -> Limits {
    Limits {
        max_bytes: 384 * 1024,
        max_depth: 4,
        max_nodes: 4096,
        max_string_bytes: 512,
        max_collection_items: MAX,
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct Receipt {
    pub marker: String,
    pub location: SourceLocation,
    pub line: usize,
    pub method: String,
    pub package: Option<String>,
    pub instrumented: SourceDigest,
}
impl Receipt {
    fn json(&self) -> Value {
        let l = &self.location;
        let mut o = Map::new();
        for (k, v) in [
            ("marker", self.marker.clone()),
            ("path", l.relative_path.clone()),
            ("workspace", l.workspace_id.to_hex()),
            ("snapshot", l.snapshot_id.to_hex()),
            ("original", l.content_hash.to_hex()),
            ("instrumented", self.instrumented.to_hex()),
            ("method", self.method.clone()),
        ] {
            o.insert(k.into(), v.into());
        }
        o.insert("line".into(), (self.line as u64).into());
        o.insert(
            "package".into(),
            self.package.clone().map_or(Value::Null, Value::from),
        );
        Value::Object(o)
    }
    fn parse(v: &Value) -> Option<Self> {
        let o = v.as_object()?;
        if o.len() != 9 {
            return None;
        }
        let s = |k: &str| o.get(k)?.as_str();
        let path = s("path")?;
        let method = s("method")?;
        let line = usize::try_from(o.get("line")?.as_u64()?).ok()?;
        let package = match o.get("package")? {
            Value::Null => None,
            Value::String(p)
                if !p.is_empty()
                    && p.len() <= 255
                    && p.bytes()
                        .all(|c| c.is_ascii_alphanumeric() || b"._".contains(&c)) =>
            {
                Some(p.clone())
            }
            _ => return None,
        };
        if path.len() > 512
            || !(path.ends_with(".java") || path.ends_with(".kt"))
            || path.split('/').any(|p| {
                p.is_empty()
                    || p.starts_with('.')
                    || !p
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
            })
            || !(1..=524288).contains(&line)
            || method.is_empty()
            || method.len() > 128
            || !method
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_')
        {
            return None;
        }
        let loc = SourceLocation {
            relative_path: path.into(),
            workspace_id: SourceWorkspaceId::from_hex(s("workspace")?).ok()?,
            snapshot_id: SourceSnapshotId::from_hex(s("snapshot")?).ok()?,
            range: None,
            content_hash: SourceDigest::from_hex(s("original")?).ok()?,
        };
        let marker = super::trace_patch::marker(path, line, method, loc.content_hash);
        let instrumented = SourceDigest::from_hex(s("instrumented")?).ok()?;
        if marker != s("marker")? || loc.content_hash == instrumented {
            return None;
        }
        Some(Self {
            marker,
            location: loc,
            line,
            method: method.into(),
            package,
            instrumented,
        })
    }
}
fn records(dir: &Path) -> Result<Vec<Receipt>, String> {
    if !dir.is_absolute() || dir.symlink_metadata().is_ok_and(|m| !m.is_dir()) {
        return Err("Invalid local frame data directory.".into());
    }
    let path = dir.join(FILE);
    match path.symlink_metadata() {
        Err(e) if e.kind() == ErrorKind::NotFound => return Ok(vec![]),
        Ok(m) if m.is_file() => {}
        _ => return Err("Invalid probe receipt store.".into()),
    }
    let v = aps_json::read(&path, limits()).map_err(|_| "Invalid probe receipt store.")?;
    v.as_array()
        .ok_or("Invalid probe receipt store.")?
        .iter()
        .map(|v| Receipt::parse(v).ok_or("Invalid probe receipt.".into()))
        .collect()
}
pub(super) fn save(dir: &Path, r: &Receipt) -> Result<(), String> {
    let _g = LOCK.lock().map_err(|_| "Receipt lock unavailable.")?;
    let r = Receipt::parse(&r.json()).ok_or("Invalid probe binding.")?;
    let mut all = records(dir)?;
    if all.contains(&r) {
        return Ok(());
    }
    if all.len() >= MAX {
        return Err("Probe receipt limit reached.".into());
    }
    all.push(r);
    let bytes = aps_json::to_vec(
        &Value::Array(all.iter().map(Receipt::json).collect()),
        limits(),
    )
    .map_err(|_| "Invalid probe store.")?;
    aps_platform::atomic_write(&dir.join(FILE), &bytes)
        .map_err(|_| "Cannot persist local probe binding.".into())
}
pub(super) fn lookup(dir: &Path, marker: &str, package: &str) -> Result<Receipt, String> {
    let mut found = records(dir)?
        .into_iter()
        .filter(|r| r.marker == marker && r.package.as_deref() == Some(package));
    let r = found
        .next()
        .ok_or("No exported probe binding for this package.")?;
    if found.next().is_some() {
        return Err("Probe has ambiguous source bindings.".into());
    }
    Ok(r)
}

/// A label is local context for an exported patch, not verified APK identity.
/// Never annotate another package or an ambiguous marker.
pub(super) fn labels(
    dir: &Path,
    package: &str,
) -> Result<std::collections::BTreeMap<String, String>, String> {
    let mut matches = std::collections::BTreeMap::new();
    for r in records(dir)?
        .into_iter()
        .filter(|r| r.package.as_deref() == Some(package))
    {
        matches
            .entry(r.marker)
            .and_modify(|m| *m = None)
            .or_insert(Some(format!(
                "{}:{} · {}",
                r.location.relative_path, r.line, r.method
            )));
    }
    Ok(matches
        .into_iter()
        .filter_map(|(k, v)| Some((k, v?)))
        .collect())
}

pub(crate) struct ProbeLookup {
    dir: std::path::PathBuf,
    package: String,
    marker: String,
}
impl super::FrameWorkspace {
    pub(crate) fn probe_lookup(&self, marker: &str) -> Option<ProbeLookup> {
        let f = self.selected_frame()?;
        let s = self.current_session.as_ref()?;
        if s.id != f.sample.session_id
            || s.package_name.is_none()
            || s.package_name != f.sample.package_name
            || !super::perfetto::probes(&f.sample)
                .iter()
                .any(|p| p.marker == marker)
        {
            return None;
        }
        Some(ProbeLookup {
            dir: self.data_dir.clone()?,
            package: s.package_name.clone()?,
            marker: marker.into(),
        })
    }
}
impl ProbeLookup {
    pub(crate) fn resolve(
        self,
        registry: crate::source::ProbeRegistry,
    ) -> Result<SourceLocation, String> {
        let r = lookup(&self.dir, &self.marker, &self.package)?;
        let source = registry.current(&r.location, r.instrumented)?;
        let text = source.read()?; // Local navigation does not grant AI access.
        let span = super::trace_patch::edit_range(
            &r.location.relative_path,
            &text,
            r.line,
            &r.method,
            &r.marker,
        )?;
        Ok(SourceLocation {
            range: Some(aps_source::SourceRange {
                start_line: r.line,
                end_line: span.end,
            }),
            ..source.location
        })
    }
}
