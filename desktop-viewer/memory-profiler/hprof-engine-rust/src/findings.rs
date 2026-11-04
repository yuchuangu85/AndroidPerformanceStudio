//! Reviewable Kotlin-compatible retention heuristics, not calibrated leak proofs.
use crate::analysis::{assignable_named, id_of, node_of, Traversal, NONE};
use crate::model::{ObjectKind, Value as HeapValue};
use crate::query::Engine;
use crate::wire::{self, number, Value};
use std::collections::HashMap;

struct Candidate {
    node: u32,
    reason: String,
    lifecycle: bool,
    count: u64,
}
#[derive(Default)]
struct Activity {
    live: u64,
    destroyed: u64,
    representative: Option<u32>,
}

fn primitive(values: &[HeapValue], name: &str) -> Option<u64> {
    values
        .iter()
        .rev()
        .find(|v| v.ty != 2 && v.name == name)
        .map(|v| v.bits)
}
fn dimension(values: &[HeapValue], names: &[&str]) -> Option<u32> {
    names.iter().find_map(|name| {
        primitive(values, name)
            .map(|v| v as i32)
            .filter(|v| *v >= 0)
            .map(|v| v as u32)
    })
}
fn context(name: &str) -> bool {
    name.ends_with("Activity")
        || name.contains(".Activity$")
        || name.ends_with("Context")
        || name.ends_with("ContextWrapper")
}
fn handler(name: &str) -> bool {
    name.ends_with("Handler") || name.ends_with("Thread") || name.contains("Handler$")
}
fn insert(
    engine: &Engine,
    candidates: &mut HashMap<(String, String), Candidate>,
    node: u32,
    reason: String,
    lifecycle: bool,
    count: u64,
) {
    let key = (engine.group_name(node).to_owned(), reason.clone());
    let value = Candidate {
        node,
        reason,
        lifecycle,
        count,
    };
    match candidates.entry(key) {
        std::collections::hash_map::Entry::Vacant(entry) => {
            entry.insert(value);
        }
        std::collections::hash_map::Entry::Occupied(mut entry) => {
            if engine.analysis.retained[node as usize]
                > engine.analysis.retained[entry.get().node as usize]
            {
                entry.insert(value);
            }
        }
    }
}
fn push_bounded(rows: &mut Vec<Value>, row: Value, remaining: &mut usize) -> Result<(), String> {
    let size = wire::encode(&row)?.len();
    *remaining = remaining
        .checked_sub(size)
        .ok_or("findings exceed 32 MiB; findings are not truncated")?;
    rows.try_reserve(1)
        .map_err(|_| "cannot allocate findings")?;
    rows.push(row);
    Ok(())
}

impl Engine {
    pub fn findings(&self) -> Result<Value, String> {
        let activities = assignable_named(&self.heap, &self.class_names, "android.app.Activity");
        let mut fragments = vec![false; self.heap.classes.len()];
        for base in [
            "android.app.Fragment",
            "android.support.v4.app.Fragment",
            "androidx.fragment.app.Fragment",
        ] {
            for (target, source) in
                fragments
                    .iter_mut()
                    .zip(assignable_named(&self.heap, &self.class_names, base))
            {
                *target |= source;
            }
        }
        // Deep-analysis chains sort array slots lexically; inspector chains visit numeric indices.
        let traversal = Traversal::build(&self.heap, &self.analysis.graph, true)?;
        let mut counts: HashMap<&str, u64> = HashMap::new();
        for (node, object) in self.heap.objects.iter().enumerate() {
            if matches!(object.kind, ObjectKind::Instance) {
                *counts.entry(self.group_name(node as u32)).or_default() += 1;
            }
        }
        let mut candidates = HashMap::new();
        let mut activity_totals: HashMap<&str, Activity> = HashMap::new();
        let mut bitmaps = Vec::new();
        let mut lifecycle = Vec::new();
        let mut remaining = wire::MAX_FRAME - 2048;
        let mut bitmap_nodes = Vec::new();
        for (index, object) in self.heap.objects.iter().enumerate() {
            if !matches!(object.kind, ObjectKind::Instance) {
                continue;
            }
            let node = index as u32;
            let name = self.group_name(node);
            let ci = self.heap.class_by_id.get(&object.class_id).copied();
            let activity = ci.is_some_and(|i| activities[i]);
            let fragment = ci.is_some_and(|i| fragments[i]);
            let bitmap = name == "android.graphics.Bitmap" || name.ends_with(".Bitmap");
            if !activity && !fragment && !bitmap && !handler(name) {
                continue;
            }
            if bitmap {
                bitmap_nodes.push(node);
            }
            if !activity && !fragment && !handler(name) {
                continue;
            }
            let values = self.all_values(node)?;
            let depth = traversal.depth[index];
            let reachable = depth != NONE;
            let nonroot = reachable && depth > 0;
            let destroyed = primitive(&values, "mDestroyed") == Some(1)
                || primitive(&values, "mFinished") == Some(1);
            let detached = values
                .iter()
                .any(|v| v.ty == 2 && v.name == "mFragmentManager" && v.bits == 0);
            if (activity && destroyed || fragment && detached) && nonroot {
                push_bounded(
                    &mut lifecycle,
                    Value::map([
                        ("objectId", Value::Int(object.id as i64)),
                        ("className", name.into()),
                        ("heapName", self.heap_name(node).into()),
                    ]),
                    &mut remaining,
                )?;
                let reason = if activity && destroyed {
                    "Destroyed or finished Activity remains strongly reachable"
                } else {
                    "Detached Fragment remains strongly reachable (mFragmentManager is null; may be a false positive)"
                };
                insert(
                    self,
                    &mut candidates,
                    node,
                    reason.to_owned(),
                    true,
                    counts.get(name).copied().unwrap_or(1),
                );
            }
            if activity && nonroot {
                let report = activity_totals.entry(name).or_default();
                report.live += 1;
                if destroyed {
                    report.destroyed += 1;
                    if report.representative.is_none_or(|r| {
                        self.analysis.retained[index] > self.analysis.retained[r as usize]
                    }) {
                        report.representative = Some(node);
                    }
                }
            }
            if handler(name) {
                for reference in values.iter().filter(|v| v.ty == 2) {
                    let Some(target) = node_of(&self.heap, reference.bits) else {
                        continue;
                    };
                    let Some(target_object) = self.heap.objects.get(target as usize) else {
                        continue;
                    };
                    if self
                        .heap
                        .class_by_id
                        .get(&target_object.class_id)
                        .is_some_and(|i| activities[*i])
                        && traversal.depth[target as usize] != NONE
                    {
                        insert(
                            self,
                            &mut candidates,
                            target,
                            format!("{name} retains an Activity through {}", reference.name),
                            false,
                            counts.get(self.group_name(target)).copied().unwrap_or(1),
                        );
                    }
                }
            }
        }
        for (ci, class) in self.heap.classes.iter().enumerate() {
            for reference in class.static_fields.iter().filter(|v| v.ty == 2) {
                let Some(target) = node_of(&self.heap, reference.bits) else {
                    continue;
                };
                let name = self.group_name(target);
                if context(name) && traversal.depth[target as usize] != NONE {
                    insert(
                        self,
                        &mut candidates,
                        target,
                        format!(
                            "Static/singleton field {}.{} retains a Context",
                            self.class_names[ci],
                            reference
                                .name
                                .strip_prefix("static ")
                                .unwrap_or(&reference.name)
                        ),
                        false,
                        counts.get(name).copied().unwrap_or(1),
                    );
                }
            }
        }
        bitmap_nodes.sort_by(|a, b| {
            self.analysis.retained[*b as usize]
                .cmp(&self.analysis.retained[*a as usize])
                .then_with(|| (id_of(&self.heap, *a) as i64).cmp(&(id_of(&self.heap, *b) as i64)))
        });
        for node in bitmap_nodes {
            let values = self.all_values(node)?;
            let width = dimension(&values, &["mWidth", "width"]);
            let height = dimension(&values, &["mHeight", "height"]);
            let row = dimension(&values, &["mRowBytes", "rowBytes"]);
            let bytes_per_row = row.filter(|n| *n > 0).map(|n| n as u64).or_else(|| {
                width
                    .filter(|n| *n > 0)
                    .and_then(|n| (n as u64).checked_mul(4))
            });
            let estimate = height
                .filter(|n| *n > 0)
                .and_then(|h| bytes_per_row.and_then(|r| r.checked_mul(h as u64)));
            push_bounded(
                &mut bitmaps,
                Value::map([
                    ("objectId", Value::Int(id_of(&self.heap, node) as i64)),
                    ("width", width.into()),
                    ("height", height.into()),
                    (
                        "retainedSize",
                        number(self.analysis.retained[node as usize])?,
                    ),
                    ("referenceChain", self.chain(node, &traversal)?),
                    (
                        "estimatedPixelBytes",
                        estimate.map(number).transpose()?.unwrap_or(Value::Null),
                    ),
                    ("javaSizeBytes", number(self.shallow(node))?),
                    ("nativeSizeBytes", Value::Null),
                    ("className", self.group_name(node).into()),
                    (
                        "bitmapId",
                        primitive(&values, "mId")
                            .map(|v| Value::Int(v as i64))
                            .unwrap_or(Value::Null),
                    ),
                    (
                        "bitmapSourceId",
                        primitive(&values, "mSourceId")
                            .map(|v| Value::Int(v as i64))
                            .unwrap_or(Value::Null),
                    ),
                ]),
                &mut remaining,
            )?;
        }
        let mut candidates: Vec<_> = candidates.into_values().collect();
        candidates.sort_by(|a, b| {
            self.analysis.retained[b.node as usize]
                .cmp(&self.analysis.retained[a.node as usize])
                .then_with(|| self.group_name(a.node).cmp(self.group_name(b.node)))
                .then_with(|| a.reason.cmp(&b.reason))
        });
        let mut suspects = Vec::new();
        for candidate in candidates {
            push_bounded(
                &mut suspects,
                Value::map([
                    ("className", self.group_name(candidate.node).into()),
                    ("reason", candidate.reason.into()),
                    (
                        "retainedSize",
                        number(self.analysis.retained[candidate.node as usize])?,
                    ),
                    ("instanceCount", number(candidate.count)?),
                    ("referenceChain", self.chain(candidate.node, &traversal)?),
                    ("confidence", Value::Int(0)),
                    ("requiresManualVerification", true.into()),
                    ("activityOrFragmentLeak", candidate.lifecycle.into()),
                ]),
                &mut remaining,
            )?;
        }
        let mut reports: Vec<_> = activity_totals
            .into_iter()
            .filter(|(_, r)| r.destroyed > 0)
            .collect();
        reports.sort_by(|(an, a), (bn, b)| {
            b.destroyed
                .cmp(&a.destroyed)
                .then_with(|| b.live.cmp(&a.live))
                .then_with(|| an.cmp(bn))
        });
        let mut activity_leaks = Vec::new();
        for (name, report) in reports {
            let representative = report
                .representative
                .ok_or("destroyed Activity representative unavailable")?;
            push_bounded(
                &mut activity_leaks,
                Value::map([
                    ("className", name.into()),
                    ("liveInstanceCount", number(report.live)?),
                    ("destroyedInstanceCount", number(report.destroyed)?),
                    (
                        "retainedSize",
                        number(self.analysis.retained[representative as usize])?,
                    ),
                    ("referenceChain", self.chain(representative, &traversal)?),
                ]),
                &mut remaining,
            )?;
        }
        self.heap.check_source()?;
        Ok(Value::map([
            ("leakSuspects", Value::List(suspects)),
            ("bitmapInstances", Value::List(bitmaps)),
            ("activityLeaks", Value::List(activity_leaks)),
            ("lifecycleLeakObjects", Value::List(lifecycle)),
        ]))
    }
}
