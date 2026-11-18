use super::{Engine, NONE, Value, add, analysis, number};
use std::collections::{BTreeMap, HashMap};

#[derive(Default)]
struct Total<'a> {
    count: u64,
    shallow: u64,
    reachable_shallow: u64,
    reachable_shallow_unknown: bool,
    native: u64,
    reachable_native: u64,
    retained_native: u64,
    retained: u64,
    heaps: Vec<&'a str>,
}

#[derive(Default)]
struct Histogram<'a> {
    totals: Vec<Total<'a>>,
    active: Vec<u32>,
    local_of: Vec<u32>,
    global_groups: Vec<usize>,
    heap_totals: BTreeMap<&'a str, (u64, u64)>,
    count: u64,
    shallow: u64,
}

impl<'a> Histogram<'a> {
    fn add_node(&mut self, engine: &'a Engine, node: u32, group: usize) -> Result<(), String> {
        let local = if self.local_of[group] == NONE {
            let local = self.totals.len() as u32;
            self.local_of[group] = local;
            self.totals.push(Total::default());
            self.active.push(0);
            self.global_groups.push(group);
            local
        } else {
            self.local_of[group]
        } as usize;
        let size = engine.shallow(node);
        let reachable = engine.analysis.traversal.depth[node as usize] != NONE;
        let total = &mut self.totals[local];
        total.count += 1;
        add(&mut total.shallow, size)?;
        if reachable {
            if engine.shallow_known(node) {
                add(&mut total.reachable_shallow, size)?;
            } else {
                total.reachable_shallow_unknown = true;
            }
        }
        if let Some(native) = &engine.native_size {
            add(&mut total.native, native[node as usize])?;
            if reachable {
                add(&mut total.reachable_native, native[node as usize])?;
            }
        }
        self.count += 1;
        add(&mut self.shallow, size)?;
        if let Some(name) = engine.heap_name(node) {
            if !total.heaps.contains(&name) {
                total.heaps.push(name);
            }
            let (count, shallow) = self.heap_totals.entry(name).or_default();
            *count += 1;
            add(shallow, size)?;
        }
        Ok(())
    }

    fn enter(&mut self, engine: &Engine, node: u32, group: usize) -> Result<(), String> {
        let local = self.local_of[group] as usize;
        if self.active[local] == 0 {
            add(
                &mut self.totals[local].retained,
                engine.analysis.retained[node as usize],
            )?;
            if let Some(native) = &engine.retained_native_size {
                add(
                    &mut self.totals[local].retained_native,
                    native[node as usize],
                )?;
            }
        }
        self.active[local] += 1;
        Ok(())
    }

    fn exit(&mut self, group: usize) {
        self.active[self.local_of[group] as usize] -= 1;
    }

    fn finish(mut self, engine: &Engine, names: &[&str], raw: bool) -> Result<Value, String> {
        let mut order: Vec<_> = (0..self.totals.len())
            .filter(|&group| self.totals[group].count != 0)
            .collect();
        order.sort_by(|&left, &right| {
            self.totals[right]
                .count
                .cmp(&self.totals[left].count)
                .then_with(|| names[self.global_groups[left]].cmp(names[self.global_groups[right]]))
        });
        let mut classes = Vec::with_capacity(order.len());
        for group in order {
            let total = &self.totals[group];
            let name = names[self.global_groups[group]];
            classes.push(Value::map([
                ("className", name.into()),
                ("instanceCount", number(total.count)?),
                ("shallowSize", number(total.shallow)?),
                (
                    "reachableShallowSize",
                    if total.reachable_shallow_unknown {
                        Value::Null
                    } else {
                        number(total.reachable_shallow)?
                    },
                ),
                ("retainedSize", number(total.retained)?),
                (
                    "obfuscatedClassName",
                    if raw {
                        Value::Null
                    } else {
                        engine
                            .reverse_mapping
                            .get(name)
                            .and_then(|name| name.as_deref())
                            .filter(|raw| *raw != name)
                            .into()
                    },
                ),
                (
                    "hierarchyDepth",
                    if raw {
                        engine.raw_hierarchy.get(name)
                    } else {
                        engine.hierarchy.get(name)
                    }
                    .copied()
                    .into(),
                ),
                (
                    "nativeSize",
                    if engine.native_size.is_some() {
                        number(total.native)?
                    } else {
                        Value::Null
                    },
                ),
                (
                    "retainedNativeSize",
                    if engine.retained_native_size.is_some() {
                        number(total.retained_native)?
                    } else {
                        Value::Null
                    },
                ),
                (
                    "reachableNativeSize",
                    if engine.native_size.is_some() {
                        number(total.reachable_native)?
                    } else {
                        Value::Null
                    },
                ),
                ("totalCount", number(total.count)?),
                (
                    "heapNames",
                    Value::List(total.heaps.iter().map(|name| (*name).into()).collect()),
                ),
            ]));
        }
        let mut segments = Vec::new();
        for heap in &engine.heap.heaps {
            if let Some((count, shallow)) = self.heap_totals.remove(heap.as_str()) {
                segments.push(Value::map([
                    ("name", heap.as_str().into()),
                    ("sizeBytes", number(shallow)?),
                    ("objectCount", number(count)?),
                ]));
            }
        }
        Ok(Value::map([
            (
                "summary",
                Value::map([
                    ("objectCount", number(self.count)?),
                    ("shallowSize", number(self.shallow)?),
                    ("classCount", number(classes.len() as u64)?),
                    ("heapSegments", Value::List(segments)),
                ]),
            ),
            ("classes", Value::List(classes)),
        ]))
    }
}

impl Engine {
    /// Computes all requested scopes in one node scan and one dominator walk.
    pub fn histogram_bundle(&self, heaps: &[String]) -> Result<Value, String> {
        if heaps.len() > 64 || heaps.iter().any(|name| name.len() > 4096) {
            return Err("HPROF histogram heap list exceeds bounds".into());
        }
        let mut names: Vec<&str> = heaps.iter().map(String::as_str).collect();
        names.sort_unstable();
        names.dedup();
        let scopes: Vec<_> = std::iter::once(None)
            .chain(names.iter().map(|name| Some(*name)))
            .collect();
        let mut results = histograms(self, &scopes, false)?.into_iter();
        let all = results.next().ok_or("missing all-heap histogram")?;
        let per_heap = names
            .into_iter()
            .zip(results)
            .map(|(name, histogram)| (name.to_owned(), histogram))
            .collect();
        Ok(Value::map([
            ("all", all),
            ("perHeap", Value::Map(per_heap)),
        ]))
    }

    /// Matches the visible root rows, without constructing their paginated Values.
    pub fn retained_summary(&self, heap: Option<&str>) -> Result<Value, String> {
        let mut sum = None;
        for &node in &self.visible_dom_roots {
            if heap.is_some() && self.heap_name(node) != heap {
                continue;
            }
            let mut retained = sum.unwrap_or(0);
            add(&mut retained, self.analysis.retained[node as usize])?;
            sum = Some(retained);
        }
        Ok(Value::map([(
            "retainedSize",
            sum.map(number).transpose()?.unwrap_or(Value::Null),
        )]))
    }
}

pub(super) fn histograms(
    engine: &Engine,
    scopes: &[Option<&str>],
    raw: bool,
) -> Result<Vec<Value>, String> {
    let n = engine.analysis.retained.len();
    let mut histograms: Vec<Histogram<'_>> = scopes.iter().map(|_| Histogram::default()).collect();
    // The last bucket is for classes without a recorded heap, not a guessed heap.
    let unknown_heap = engine.heap.heaps.len();
    let selected: Vec<Vec<usize>> = (0..=unknown_heap)
        .map(|heap| {
            let name = engine.heap.heaps.get(heap).map(String::as_str);
            scopes
                .iter()
                .enumerate()
                .filter_map(|(scope, expected)| {
                    (expected.is_none() || *expected == name).then_some(scope)
                })
                .collect()
        })
        .collect();
    let heap_index = |node: u32| {
        let index = node as usize;
        let heap = if let Some(object) = engine.heap.objects.get(index) {
            object.heap
        } else {
            engine.heap.classes[index - engine.heap.objects.len()].heap
        };
        heap.min(unknown_heap)
    };
    let mut groups = HashMap::new();
    let mut names = Vec::new();
    let mut group_of = analysis::filled(n, NONE)?;
    for node in 0..n as u32 {
        let scopes = &selected[heap_index(node)];
        if scopes.is_empty() {
            continue;
        }
        let name = engine.group_name_for(node, raw);
        let group = *groups.entry(name).or_insert_with(|| {
            names.push(name);
            for histogram in &mut histograms {
                histogram.local_of.push(NONE);
            }
            (names.len() - 1) as u32
        });
        group_of[node as usize] = group;
        for &scope in scopes {
            histograms[scope].add_node(engine, node, group as usize)?;
        }
    }
    let mut stack = Vec::new();
    stack
        .try_reserve(n)
        .map_err(|_| "cannot allocate retained aggregation traversal")?;
    for &root in engine.children(NONE).iter().rev() {
        stack.push((root, false));
    }
    while let Some((node, exit)) = stack.pop() {
        let group = group_of[node as usize];
        if group != NONE {
            // Each heap has its own ancestor counts, including across intervening heaps.
            for &scope in &selected[heap_index(node)] {
                if exit {
                    histograms[scope].exit(group as usize);
                } else {
                    histograms[scope].enter(engine, node, group as usize)?;
                }
            }
        }
        if !exit {
            stack
                .try_reserve(engine.children(node).len() + 1)
                .map_err(|_| "cannot grow retained aggregation traversal")?;
            stack.push((node, true));
            for &child in engine.children(node).iter().rev() {
                stack.push((child, false));
            }
        }
    }
    histograms
        .into_iter()
        .map(|histogram| histogram.finish(engine, &names, raw))
        .collect()
}
