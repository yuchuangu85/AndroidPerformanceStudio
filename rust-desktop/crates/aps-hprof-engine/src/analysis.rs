//! Compact strong graph, shortest-root traversal and reachable-first Lengauer-Tarjan.
use crate::edges::{EdgeStore, EdgeWriter, GraphStorage};
use crate::model::{Field, Heap, Object, ObjectKind};
use std::collections::{HashMap, HashSet, VecDeque};

pub const NONE: u32 = u32::MAX;
pub const READ_CHUNK: u32 = 4096;

pub fn observed_fields(heap: &Heap, object: &Object, layout: &[Field]) -> usize {
    let mut bytes = 0u64;
    layout
        .iter()
        .take_while(|field| {
            bytes += match field.ty {
                2 => heap.id_size as u64,
                4 | 8 => 1,
                5 | 9 => 2,
                6 | 10 => 4,
                7 | 11 => 8,
                _ => u32::MAX as u64,
            };
            bytes <= object.count as u64
        })
        .count()
}

pub(crate) fn filled<T: Clone>(n: usize, value: T) -> Result<Vec<T>, String> {
    let mut result = Vec::new();
    result
        .try_reserve_exact(n)
        .map_err(|_| "cannot allocate compact analysis array")?;
    result.resize(n, value);
    Ok(result)
}

pub fn node_of(heap: &Heap, id: u64) -> Option<u32> {
    heap.class_by_id
        .get(&id)
        .map(|i| (heap.objects.len() + i) as u32)
        .or_else(|| heap.object_by_id.get(&id).map(|i| *i as u32))
}
pub fn id_of(heap: &Heap, node: u32) -> u64 {
    let i = node as usize;
    if i < heap.objects.len() {
        heap.objects[i].id
    } else {
        heap.classes[i - heap.objects.len()].id
    }
}

pub fn assignable(heap: &Heap, base: &str) -> Vec<bool> {
    assignable_named(heap, &[], base)
}

pub fn assignable_named(heap: &Heap, names: &[String], base: &str) -> Vec<bool> {
    // A class is visited at most once per hierarchy walk, including malformed cycles.
    let mut result = vec![false; heap.classes.len()];
    for (i, class) in heap.classes.iter().enumerate() {
        let mut id = class.id;
        let mut visited = HashSet::new();
        while id != 0 && visited.insert(id) {
            let Some(&index) = heap.class_by_id.get(&id) else {
                break;
            };
            let current = &heap.classes[index];
            if names
                .get(index)
                .map(String::as_str)
                .unwrap_or(&current.name)
                == base
            {
                result[i] = true;
                break;
            }
            id = current.super_id;
        }
    }
    result
}

pub struct Graph {
    pub offsets: Vec<u32>,
    pub edges: EdgeStore,
    pub layouts: Vec<Vec<Field>>,
}

impl Graph {
    pub fn build(heap: &Heap) -> Result<Self, String> {
        Self::build_named(heap, &[])
    }
    pub fn build_named(heap: &Heap, names: &[String]) -> Result<Self, String> {
        Self::build_named_with_storage(heap, names, GraphStorage::Memory)
    }
    pub fn build_named_with_storage(
        heap: &Heap,
        names: &[String],
        storage: GraphStorage<'_>,
    ) -> Result<Self, String> {
        let count = heap
            .objects
            .len()
            .checked_add(heap.classes.len())
            .ok_or("node count overflow")?;
        if count >= (u32::MAX - 1) as usize {
            return Err("heap exceeds compact node index capacity".into());
        }
        let reference_classes = assignable_named(heap, names, "java.lang.ref.Reference");
        let layouts: Vec<_> = heap.classes.iter().map(|c| heap.fields_for(c.id)).collect();
        let mut edges = EdgeWriter::new(storage)?;
        let mut offsets = Vec::new();
        offsets
            .try_reserve_exact(count + 1)
            .map_err(|_| "cannot allocate graph offsets")?;
        offsets.push(0);
        for object in &heap.objects {
            let class_index = heap.class_by_id.get(&object.class_id).copied();
            let is_instance = matches!(object.kind, ObjectKind::Instance);
            let weak_holder = is_instance
                && class_index.is_some_and(|i| {
                    reference_classes[i]
                        || matches!(
                            names
                                .get(i)
                                .map(String::as_str)
                                .unwrap_or(&heap.classes[i].name),
                            "java.lang.ref.WeakReference"
                                | "java.lang.ref.SoftReference"
                                | "java.lang.ref.PhantomReference"
                        )
                });
            if !matches!(object.kind, ObjectKind::PrimitiveArray { .. }) {
                let count = if is_instance {
                    class_index
                        .map(|i| observed_fields(heap, object, &layouts[i]))
                        .unwrap_or(0)
                } else {
                    object.count as usize
                };
                // Only instances need field-name sorting; large arrays stream directly.
                let mut instance_edges = Vec::new();
                let mut start = 0;
                while start < count {
                    let limit = (count - start).min(READ_CHUNK as usize) as u32;
                    let values = heap.values(object, start as u32, limit)?;
                    if values.len() != limit as usize {
                        return Err(format!("incomplete object values for 0x{:x}", object.id));
                    }
                    for (local, value) in values.iter().enumerate() {
                        if value.ty != 2
                            || value.bits == 0
                            || (weak_holder && value.name == "referent")
                        {
                            continue;
                        }
                        if let Some(target) = node_of(heap, value.bits) {
                            let slot = (start + local) as u32;
                            if is_instance {
                                instance_edges
                                    .try_reserve(1)
                                    .map_err(|_| "cannot allocate instance edges")?;
                                instance_edges.push((target, slot));
                            } else {
                                edges.push(target, slot)?;
                            }
                        }
                    }
                    start += limit as usize;
                }
                if is_instance {
                    if let Some(ci) = class_index {
                        instance_edges.sort_by(|a, b| {
                            layouts[ci][a.1 as usize]
                                .name
                                .cmp(&layouts[ci][b.1 as usize].name)
                                .then_with(|| {
                                    (id_of(heap, a.0) as i64).cmp(&(id_of(heap, b.0) as i64))
                                })
                        });
                        for (target, slot) in instance_edges {
                            edges.push(target, slot)?;
                        }
                    }
                }
            }
            offsets.push(edges.len() as u32);
        }
        for class in &heap.classes {
            let mut slots: Vec<_> = class
                .static_fields
                .iter()
                .enumerate()
                .filter(|(_, v)| v.ty == 2 && v.bits != 0)
                .collect();
            slots.sort_by(|(ai, a), (bi, b)| {
                a.name
                    .cmp(&b.name)
                    .then_with(|| (a.bits as i64).cmp(&(b.bits as i64)))
                    .then(ai.cmp(bi))
            });
            for (slot, value) in slots {
                if let Some(target) = node_of(heap, value.bits) {
                    edges.push(
                        target,
                        u32::try_from(slot).map_err(|_| "too many static fields")?,
                    )?;
                }
            }
            offsets.push(edges.len() as u32);
        }
        heap.check_source()?;
        Ok(Self {
            offsets,
            edges: edges.finish()?,
            layouts,
        })
    }
    pub fn edge(&self, edge: usize) -> Result<(u32, u32), String> {
        let (target, slot) = self.edges.get(edge)?;
        if target as usize >= self.offsets.len().saturating_sub(1) {
            return Err("HPROF graph edge target out of bounds".into());
        }
        Ok((target, slot))
    }
    pub fn check_storage(&self) -> Result<(), String> {
        self.edges.check()
    }
    pub fn range(&self, node: u32) -> std::ops::Range<usize> {
        self.offsets[node as usize] as usize..self.offsets[node as usize + 1] as usize
    }
    pub fn field_name(&self, heap: &Heap, source: u32, slot: u32) -> Result<String, String> {
        if let Some(object) = heap.objects.get(source as usize) {
            if matches!(object.kind, ObjectKind::ObjectArray) {
                if slot >= object.count {
                    return Err("HPROF graph array slot out of bounds".into());
                }
                return Ok(format!("[{slot}]"));
            }
            return heap
                .class_by_id
                .get(&object.class_id)
                .and_then(|i| self.layouts.get(*i)?.get(slot as usize))
                .map(|f| f.name.clone())
                .ok_or_else(|| "HPROF graph field slot out of bounds".into());
        }
        heap.classes
            .get(source as usize - heap.objects.len())
            .and_then(|class| class.static_fields.get(slot as usize))
            .map(|field| field.name.clone())
            .ok_or_else(|| "HPROF graph static slot out of bounds".into())
    }
}

pub struct Traversal {
    pub depth: Vec<u32>,
    pub predecessor: Vec<u32>,
    pub slot: Vec<u32>,
    pub roots: HashMap<u32, usize>,
}
impl Traversal {
    pub fn build(heap: &Heap, graph: &Graph, lexical_arrays: bool) -> Result<Self, String> {
        graph.check_storage()?;
        let n = graph.offsets.len() - 1;
        let mut result = Self {
            depth: filled(n, NONE)?,
            predecessor: filled(n, NONE)?,
            slot: filled(n, NONE)?,
            roots: HashMap::new(),
        };
        let mut roots: Vec<_> = heap
            .roots
            .iter()
            .enumerate()
            .filter_map(|(i, r)| node_of(heap, r.id).map(|node| (node, i)))
            .collect();
        roots.sort_by(|(a, ai), (b, bi)| {
            (id_of(heap, *a) as i64)
                .cmp(&(id_of(heap, *b) as i64))
                .then_with(|| heap.roots[*ai].kind.cmp(&heap.roots[*bi].kind))
        });
        let mut queue = VecDeque::new();
        queue
            .try_reserve(n.min(roots.len() + 4096))
            .map_err(|_| "cannot allocate root traversal")?;
        for (node, index) in roots {
            if result.depth[node as usize] == NONE {
                result.depth[node as usize] = 0;
                result.roots.insert(node, index);
                queue.push_back(node);
            }
        }
        while let Some(source) = queue.pop_front() {
            let visit =
                |edge: usize, result: &mut Self, queue: &mut VecDeque<u32>| -> Result<(), String> {
                    let (target, slot) = graph.edge(edge)?;
                    let target = target as usize;
                    if result.depth[target] == NONE {
                        result.depth[target] = result.depth[source as usize]
                            .checked_add(1)
                            .filter(|d| *d != NONE)
                            .ok_or("root depth overflow")?;
                        result.predecessor[target] = source;
                        result.slot[target] = slot;
                        queue
                            .try_reserve(1)
                            .map_err(|_| "cannot grow root traversal")?;
                        queue.push_back(target as u32);
                    }
                    Ok(())
                };
            if lexical_arrays
                && heap
                    .objects
                    .get(source as usize)
                    .is_some_and(|o| matches!(o.kind, ObjectKind::ObjectArray))
            {
                let mut order = Vec::new();
                order
                    .try_reserve_exact(graph.range(source).len())
                    .map_err(|_| "cannot allocate array edge order")?;
                for edge in graph.range(source) {
                    order.push((graph.edge(edge)?.1, edge as u32));
                }
                order.sort_unstable_by_key(|(slot, _)| array_lex_key(*slot));
                for (_, edge) in order {
                    visit(edge as usize, &mut result, &mut queue)?;
                }
            } else {
                for edge in graph.range(source) {
                    visit(edge, &mut result, &mut queue)?;
                }
            }
        }
        graph.check_storage()?;
        Ok(result)
    }
}

// ']' sorts after every decimal digit, so a prefix follows its longer spelling.
fn array_lex_key(mut value: u32) -> u64 {
    let mut digits = [0u8; 10];
    let mut n = 0;
    loop {
        digits[n] = (value % 10) as u8;
        n += 1;
        value /= 10;
        if value == 0 {
            break;
        }
    }
    let mut key = 0u64;
    for i in (0..n).rev() {
        key = (key << 4) | digits[i] as u64;
    }
    for _ in n..11 {
        key = (key << 4) | 15;
    }
    key
}

pub struct Analysis {
    pub graph: Graph,
    pub traversal: Traversal,
    pub retained: Vec<u64>,
    pub immediate: Vec<u32>,
}
impl Analysis {
    pub fn build(heap: &Heap) -> Result<Self, String> {
        Self::build_named(heap, &[])
    }
    pub fn build_named(heap: &Heap, names: &[String]) -> Result<Self, String> {
        Self::build_named_with_storage(heap, names, GraphStorage::Memory)
    }
    pub fn build_named_with_storage(
        heap: &Heap,
        names: &[String],
        storage: GraphStorage<'_>,
    ) -> Result<Self, String> {
        let graph = Graph::build_named_with_storage(heap, names, storage)?;
        let traversal = Traversal::build(heap, &graph, false)?;
        let shallow: Vec<_> = heap
            .objects
            .iter()
            .map(|o| o.shallow_size)
            .chain(heap.classes.iter().map(|_| 0))
            .collect();
        let (retained, immediate) = dominators(&graph, &traversal, &shallow)?;
        Ok(Self {
            graph,
            traversal,
            retained,
            immediate,
        })
    }
}

fn eval(
    node: u32,
    ancestor: &mut [u32],
    label: &mut [u32],
    semi: &[u32],
    path: &mut Vec<u32>,
) -> u32 {
    let mut current = node;
    path.clear();
    while ancestor[current as usize] != NONE
        && ancestor[ancestor[current as usize] as usize] != NONE
    {
        path.push(current);
        current = ancestor[current as usize];
    }
    while let Some(target) = path.pop() {
        let parent = ancestor[target as usize];
        if semi[label[parent as usize] as usize] < semi[label[target as usize] as usize] {
            label[target as usize] = label[parent as usize];
        }
        ancestor[target as usize] = ancestor[parent as usize];
    }
    label[node as usize]
}

pub fn dominators(
    graph: &Graph,
    traversal: &Traversal,
    shallow: &[u64],
) -> Result<(Vec<u64>, Vec<u32>), String> {
    graph.check_storage()?;
    let n = shallow.len();
    // A direct synthetic-root edge to every node leaves no nontrivial dominators.
    if traversal.roots.is_empty() {
        return Ok((shallow.to_vec(), filled(n, NONE)?));
    }
    let mut global_to_local = filled(n, NONE)?;
    let mut local_to_global = vec![NONE]; // local zero is the synthetic root
    local_to_global
        .try_reserve(traversal.depth.iter().filter(|d| **d != NONE).count())
        .map_err(|_| "cannot allocate reachable index")?;
    for (i, local) in global_to_local.iter_mut().enumerate() {
        if traversal.depth[i] != NONE {
            *local = local_to_global.len() as u32;
            local_to_global.push(i as u32);
        }
    }
    let m = local_to_global.len();
    let mut roots: Vec<u32> = traversal
        .roots
        .keys()
        .map(|r| global_to_local[*r as usize])
        .collect();
    roots.sort_unstable();
    let mut dfs = filled(m, 0u32)?;
    let mut vertex = filled(m + 1, 0u32)?;
    let mut parent = filled(m, NONE)?;
    dfs[0] = 1;
    vertex[1] = 0;
    let mut visited = 1usize;
    let mut stack = Vec::new();
    stack
        .try_reserve(m)
        .map_err(|_| "cannot allocate dominator DFS")?;
    stack.push((0u32, 0usize));
    while let Some((node, next)) = stack.last_mut() {
        let source = *node;
        let target = if source == 0 {
            let target = roots.get(*next).copied();
            *next += 1;
            target
        } else {
            let range = graph.range(local_to_global[source as usize]);
            let edge = range.start + *next;
            *next += 1;
            if edge < range.end {
                Some(global_to_local[graph.edge(edge)?.0 as usize])
            } else {
                None
            }
        };
        let Some(target) = target else {
            stack.pop();
            continue;
        };
        if target == NONE || dfs[target as usize] != 0 {
            continue;
        }
        visited += 1;
        dfs[target as usize] = visited as u32;
        vertex[visited] = target;
        parent[target as usize] = source;
        stack.push((target, 0));
    }
    let mut offsets = filled(m + 1, 0usize)?;
    for &root in &roots {
        offsets[root as usize + 1] += 1;
    }
    for &global in local_to_global.iter().skip(1) {
        for edge in graph.range(global) {
            let target = global_to_local[graph.edge(edge)?.0 as usize];
            if target != NONE {
                offsets[target as usize + 1] += 1;
            }
        }
    }
    for i in 1..=m {
        offsets[i] = offsets[i]
            .checked_add(offsets[i - 1])
            .ok_or("predecessor count overflow")?;
    }
    let mut predecessors = filled(offsets[m], 0u32)?;
    let mut cursors = offsets.clone();
    for &root in &roots {
        let i = root as usize;
        predecessors[cursors[i]] = 0;
        cursors[i] += 1;
    }
    for (local, &global) in local_to_global.iter().enumerate().skip(1) {
        for edge in graph.range(global) {
            let target = global_to_local[graph.edge(edge)?.0 as usize];
            if target != NONE {
                let i = target as usize;
                predecessors[cursors[i]] = local as u32;
                cursors[i] += 1;
            }
        }
    }
    drop(cursors);
    drop(stack);
    let mut semi = dfs;
    let mut label: Vec<u32> = (0..m as u32).collect();
    let mut ancestor = filled(m, NONE)?;
    let mut immediate = filled(m, NONE)?;
    let mut bucket_head = filled(m, NONE)?;
    let mut bucket_next = filled(m, NONE)?;
    let mut path = Vec::new();
    path.try_reserve(m)
        .map_err(|_| "cannot allocate dominator compression path")?;
    for order in (2..=visited).rev() {
        let node = vertex[order];
        let i = node as usize;
        for &pred in &predecessors[offsets[i]..offsets[i + 1]] {
            let candidate = eval(pred, &mut ancestor, &mut label, &semi, &mut path);
            semi[i] = semi[i].min(semi[candidate as usize]);
        }
        let bucket = vertex[semi[i] as usize] as usize;
        bucket_next[i] = bucket_head[bucket];
        bucket_head[bucket] = node;
        let p = parent[i] as usize;
        ancestor[i] = p as u32;
        let mut candidate = bucket_head[p];
        bucket_head[p] = NONE;
        while candidate != NONE {
            let evaluated = eval(candidate, &mut ancestor, &mut label, &semi, &mut path);
            immediate[candidate as usize] = if semi[evaluated as usize] < semi[candidate as usize] {
                evaluated
            } else {
                p as u32
            };
            candidate = bucket_next[candidate as usize];
        }
    }
    immediate[0] = 0;
    for order in 2..=visited {
        let node = vertex[order] as usize;
        if immediate[node] != vertex[semi[node] as usize] {
            immediate[node] = immediate[immediate[node] as usize];
        }
    }
    let mut retained = shallow.to_vec();
    let mut global_immediate = filled(n, NONE)?;
    for order in (2..=visited).rev() {
        let node = vertex[order] as usize;
        let p = immediate[node];
        let global = local_to_global[node] as usize;
        if p > 0 && p != NONE {
            let parent = local_to_global[p as usize] as usize;
            global_immediate[global] = parent as u32;
            retained[parent] = retained[parent]
                .checked_add(retained[global])
                .ok_or("retained size overflow")?;
        }
    }
    graph.check_storage()?;
    Ok((retained, global_immediate))
}
