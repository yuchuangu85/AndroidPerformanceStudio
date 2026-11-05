use crate::analysis::{self, id_of, node_of, Analysis, Traversal, NONE, READ_CHUNK};
use crate::edges::GraphStorage;
use crate::model::{Heap, ObjectKind, Value as HeapValue};
use crate::wire::{number, Value};
use std::collections::{BTreeMap, HashMap, HashSet};

pub struct Engine {
    pub heap: Heap,
    pub analysis: Analysis,
    sorted: Vec<u32>,
    dom_depth: Vec<u32>,
    dom_offsets: Vec<u32>,
    dom_children: Vec<u32>,
    visible_dom_roots: Vec<u32>,
    class_shallow: Option<u64>,
    reverse_mapping: HashMap<String, Option<String>>,
    hierarchy: HashMap<String, u32>,
    raw_hierarchy: HashMap<String, u32>,
    pub class_names: Vec<String>,
}

pub fn original_name(raw: &str, mapping: &BTreeMap<String, String>) -> String {
    let mut base = raw;
    let mut suffix_dimensions = 0;
    while let Some(component) = base.strip_suffix("[]") {
        base = component;
        suffix_dimensions += 1;
    }
    if suffix_dimensions > 0 {
        return format!(
            "{}{}",
            mapping.get(base).map(String::as_str).unwrap_or(base),
            "[]".repeat(suffix_dimensions)
        );
    }
    let dimensions = raw.bytes().take_while(|b| *b == b'[').count();
    if dimensions > 0 && raw.as_bytes().get(dimensions) == Some(&b'L') && raw.ends_with(';') {
        let name = &raw[dimensions + 1..raw.len() - 1];
        let dotted = name.replace('/', ".");
        let mapped = mapping.get(&dotted).cloned().unwrap_or(dotted);
        return format!(
            "{}L{};",
            "[".repeat(dimensions),
            if name.contains('/') {
                mapped.replace('.', "/")
            } else {
                mapped
            }
        );
    }
    mapping.get(raw).cloned().unwrap_or_else(|| raw.to_owned())
}

impl Engine {
    pub fn new(heap: Heap, mapping: BTreeMap<String, String>) -> Result<Self, String> {
        Self::new_with_storage(heap, mapping, GraphStorage::Memory)
    }
    pub fn new_with_storage(
        heap: Heap,
        mapping: BTreeMap<String, String>,
        storage: GraphStorage<'_>,
    ) -> Result<Self, String> {
        for object in &heap.objects {
            if object.id == 0 || heap.class_by_id.contains_key(&object.id) {
                return Err("null or duplicate cross-kind HPROF object identity".into());
            }
        }
        if heap.class_by_id.contains_key(&0) {
            return Err("null HPROF class identity".into());
        }
        let mut reverse_mapping: HashMap<String, Option<String>> = HashMap::new();
        for (raw, original) in &mapping {
            reverse_mapping
                .entry(original.clone())
                .and_modify(|v| *v = None)
                .or_insert_with(|| Some(raw.clone()));
        }
        let class_names: Vec<_> = heap
            .classes
            .iter()
            .map(|c| original_name(&c.name, &mapping))
            .collect();
        let class_shallow = heap
            .classes
            .iter()
            .enumerate()
            .find(|(i, _)| class_names[*i] == "java.lang.Class")
            .map(|(_, c)| c.instance_size);
        let mut hierarchy = HashMap::new();
        let mut raw_hierarchy = HashMap::new();
        for (index, class) in heap.classes.iter().enumerate() {
            let mut current = class.id;
            let mut visited = HashSet::new();
            let mut depth = 0u32;
            while current != 0 && visited.insert(current) {
                let Some(&i) = heap.class_by_id.get(&current) else {
                    break;
                };
                depth = depth.checked_add(1).ok_or("class hierarchy too deep")?;
                current = heap.classes[i].super_id;
            }
            hierarchy
                .entry(class_names[index].clone())
                .and_modify(|d: &mut u32| *d = (*d).max(depth))
                .or_insert(depth);
            raw_hierarchy
                .entry(class.name.clone())
                .and_modify(|d: &mut u32| *d = (*d).max(depth))
                .or_insert(depth);
        }
        let analysis = Analysis::build_named_with_storage(&heap, &class_names, storage)?;
        let n = analysis.retained.len();
        let mut sorted: Vec<_> = (0..n as u32).collect();
        sorted.sort_unstable_by_key(|node| id_of(&heap, *node) as i64);
        let mut dom_depth = analysis::filled(n, NONE)?;
        let mut path = Vec::new();
        path.try_reserve(n)
            .map_err(|_| "cannot allocate dominator depths")?;
        for i in 0..n {
            if dom_depth[i] != NONE {
                continue;
            }
            let mut current = i as u32;
            path.clear();
            while current != NONE && dom_depth[current as usize] == NONE {
                if path.len() >= n {
                    return Err("cycle in immediate dominators".into());
                }
                path.push(current);
                current = analysis.immediate[current as usize];
            }
            let mut depth = if current == NONE {
                0
            } else {
                dom_depth[current as usize]
                    .checked_add(1)
                    .ok_or("dominator depth overflow")?
            };
            while let Some(node) = path.pop() {
                dom_depth[node as usize] = depth;
                depth = depth.checked_add(1).ok_or("dominator depth overflow")?;
            }
        }
        let mut dom_offsets = analysis::filled(n + 2, 0u32)?;
        for &parent in &analysis.immediate {
            let p = if parent == NONE { n } else { parent as usize };
            dom_offsets[p + 1] += 1;
        }
        for i in 1..dom_offsets.len() {
            dom_offsets[i] = dom_offsets[i]
                .checked_add(dom_offsets[i - 1])
                .ok_or("dominator count overflow")?;
        }
        let mut cursor = dom_offsets.clone();
        let mut dom_children = analysis::filled(n, 0u32)?;
        for (node, &parent) in analysis.immediate.iter().enumerate() {
            let p = if parent == NONE { n } else { parent as usize };
            dom_children[cursor[p] as usize] = node as u32;
            cursor[p] += 1;
        }
        let mut result = Self {
            heap,
            analysis,
            sorted,
            dom_depth,
            dom_offsets,
            dom_children,
            visible_dom_roots: Vec::new(),
            class_shallow,
            reverse_mapping,
            hierarchy,
            raw_hierarchy,
            class_names,
        };
        // Siblings share global depth; sorting once also makes repeat tree expansion cheap.
        for p in 0..=n {
            let begin = result.dom_offsets[p] as usize;
            let end = result.dom_offsets[p + 1] as usize;
            let mut children = result.dom_children[begin..end].to_vec();
            children.sort_by(|a, b| {
                result.analysis.retained[*b as usize]
                    .cmp(&result.analysis.retained[*a as usize])
                    .then_with(|| result.group_name(*a).cmp(result.group_name(*b)))
                    .then_with(|| {
                        (id_of(&result.heap, *a) as i64).cmp(&(id_of(&result.heap, *b) as i64))
                    })
            });
            result.dom_children[begin..end].copy_from_slice(&children);
        }
        // Legacy exposes only objects; a hidden class parent makes an object a visible root.
        let mut visible_roots: Vec<u32> = result
            .analysis
            .immediate
            .iter()
            .take(result.heap.objects.len())
            .enumerate()
            .filter(|(_, parent)| {
                **parent == NONE || **parent as usize >= result.heap.objects.len()
            })
            .map(|(node, _)| node as u32)
            .collect();
        visible_roots.sort_by(|a, b| {
            result.dom_depth[*a as usize]
                .cmp(&result.dom_depth[*b as usize])
                .then_with(|| {
                    result.analysis.retained[*b as usize]
                        .cmp(&result.analysis.retained[*a as usize])
                })
                .then_with(|| result.group_name(*a).cmp(result.group_name(*b)))
                .then_with(|| {
                    (id_of(&result.heap, *a) as i64).cmp(&(id_of(&result.heap, *b) as i64))
                })
        });
        result.visible_dom_roots = visible_roots;
        result.heap.check_source()?;
        Ok(result)
    }

    pub fn group_name(&self, node: u32) -> &str {
        self.group_name_for(node, false)
    }
    pub fn raw_group_name(&self, node: u32) -> &str {
        self.group_name_for(node, true)
    }
    fn group_name_for(&self, node: u32, raw: bool) -> &str {
        let i = node as usize;
        if i >= self.heap.objects.len() {
            let ci = i - self.heap.objects.len();
            return if raw {
                &self.heap.classes[ci].name
            } else {
                &self.class_names[ci]
            };
        }
        let object = &self.heap.objects[i];
        if let ObjectKind::PrimitiveArray { ty, .. } = object.kind {
            return primitive_array_name(ty);
        }
        self.heap
            .class_by_id
            .get(&object.class_id)
            .map(|i| {
                if raw {
                    self.heap.classes[*i].name.as_str()
                } else {
                    self.class_names[*i].as_str()
                }
            })
            .unwrap_or("<unknown>")
    }
    pub fn runtime_name(&self, node: u32) -> &str {
        if node as usize >= self.heap.objects.len() {
            "java.lang.Class"
        } else {
            self.group_name(node)
        }
    }
    pub fn heap_name(&self, node: u32) -> Option<&str> {
        let i = node as usize;
        let index = if i < self.heap.objects.len() {
            self.heap.objects[i].heap
        } else {
            self.heap.classes[i - self.heap.objects.len()].heap
        };
        self.heap.heaps.get(index).map(String::as_str)
    }
    pub fn shallow(&self, node: u32) -> u64 {
        self.heap
            .objects
            .get(node as usize)
            .map(|o| o.shallow_size)
            .unwrap_or(self.class_shallow.unwrap_or(0))
    }
    pub fn shallow_known(&self, node: u32) -> bool {
        let Some(object) = self.heap.objects.get(node as usize) else {
            return self.class_shallow.is_some();
        };
        !matches!(object.kind, ObjectKind::Instance)
            || self.heap.class_by_id.contains_key(&object.class_id)
    }
    fn depth(&self, node: u32) -> Value {
        optional_index(self.analysis.traversal.depth[node as usize])
    }
    pub fn metadata(&self) -> Result<Value, String> {
        let mut instances = 0;
        let mut objects = 0;
        let mut primitives = 0;
        for object in &self.heap.objects {
            match object.kind {
                ObjectKind::Instance => instances += 1u64,
                ObjectKind::ObjectArray => objects += 1u64,
                ObjectKind::PrimitiveArray { .. } => primitives += 1u64,
            }
        }
        let warnings = self
            .heap
            .warnings
            .iter()
            .map(|w| {
                Ok(Value::map([
                    ("message", w.message.clone().into()),
                    (
                        "offset",
                        w.offset.map(number).transpose()?.unwrap_or(Value::Null),
                    ),
                ]))
            })
            .collect::<Result<Vec<_>, String>>()?;
        Ok(Value::map([
            ("format", self.heap.format.clone().into()),
            ("idSize", Value::Int(self.heap.id_size as i64)),
            ("timestampMillis", number(self.heap.timestamp)?),
            ("classCount", number(self.heap.classes.len() as u64)?),
            ("instanceCount", number(instances)?),
            ("objectArrayCount", number(objects)?),
            ("primitiveArrayCount", number(primitives)?),
            ("rootCount", number(self.heap.roots.len() as u64)?),
            ("warnings", Value::List(warnings)),
            (
                "heaps",
                Value::List(self.heap.heaps.iter().map(|h| h.as_str().into()).collect()),
            ),
        ]))
    }

    pub fn histogram(&self, heap_name: Option<&str>) -> Result<Value, String> {
        self.histogram_named(heap_name, false)
    }
    pub fn raw_histogram(&self, heap_name: Option<&str>) -> Result<Value, String> {
        self.histogram_named(heap_name, true)
    }
    fn histogram_named(&self, heap_name: Option<&str>, raw: bool) -> Result<Value, String> {
        #[derive(Default)]
        struct Total {
            count: u64,
            shallow: u64,
            retained: u64,
            heaps: Vec<String>,
        }
        let n = self.analysis.retained.len();
        let mut groups: HashMap<&str, u32> = HashMap::new();
        let mut names = Vec::new();
        let mut totals: Vec<Total> = Vec::new();
        let mut group_of = analysis::filled(n, NONE)?;
        let mut heap_totals: BTreeMap<&str, (u64, u64)> = BTreeMap::new();
        let mut count = 0u64;
        let mut shallow = 0u64;
        for node in 0..n as u32 {
            let name = self.heap_name(node);
            if heap_name.is_some() && name != heap_name {
                continue;
            }
            let class_name = self.group_name_for(node, raw);
            let group = *groups.entry(class_name).or_insert_with(|| {
                names.push(class_name);
                totals.push(Total::default());
                (totals.len() - 1) as u32
            });
            group_of[node as usize] = group;
            let size = self.shallow(node);
            let total = &mut totals[group as usize];
            total.count += 1;
            add(&mut total.shallow, size)?;
            count += 1;
            add(&mut shallow, size)?;
            if let Some(name) = name {
                if !total.heaps.iter().any(|h| h == name) {
                    total.heaps.push(name.to_owned());
                }
                let (heap_count, heap_size) = heap_totals.entry(name).or_default();
                *heap_count += 1;
                add(heap_size, size)?;
            }
        }
        // Only selected same-class ancestors suppress retained totals (also under heap filters).
        let mut active = vec![0u32; totals.len()];
        let mut stack = Vec::new();
        stack
            .try_reserve(n)
            .map_err(|_| "cannot allocate retained aggregation traversal")?;
        for &root in self.children(NONE).iter().rev() {
            stack.push((root, false));
        }
        while let Some((node, exit)) = stack.pop() {
            let group = group_of[node as usize];
            if exit {
                if group != NONE {
                    active[group as usize] -= 1;
                }
                continue;
            }
            if group != NONE {
                if active[group as usize] == 0 {
                    add(
                        &mut totals[group as usize].retained,
                        self.analysis.retained[node as usize],
                    )?;
                }
                active[group as usize] += 1;
            }
            stack
                .try_reserve(self.children(node).len() + 1)
                .map_err(|_| "cannot grow retained aggregation traversal")?;
            stack.push((node, true));
            for &child in self.children(node).iter().rev() {
                stack.push((child, false));
            }
        }
        let mut order: Vec<_> = (0..totals.len()).collect();
        order.sort_by(|a, b| {
            totals[*b]
                .count
                .cmp(&totals[*a].count)
                .then_with(|| names[*a].cmp(names[*b]))
        });
        let classes = order
            .into_iter()
            .map(|i| {
                let total = &totals[i];
                let name = names[i];
                Ok(Value::map([
                    ("className", name.into()),
                    ("instanceCount", number(total.count)?),
                    ("shallowSize", number(total.shallow)?),
                    ("retainedSize", number(total.retained)?),
                    (
                        "obfuscatedClassName",
                        if raw {
                            Value::Null
                        } else {
                            self.reverse_mapping
                                .get(name)
                                .and_then(|v| v.as_deref())
                                .filter(|v| *v != name)
                                .into()
                        },
                    ),
                    (
                        "hierarchyDepth",
                        if raw {
                            self.raw_hierarchy.get(name)
                        } else {
                            self.hierarchy.get(name)
                        }
                        .copied()
                        .into(),
                    ),
                    ("nativeSize", Value::Null),
                    ("totalCount", number(total.count)?),
                    (
                        "heapNames",
                        Value::List(total.heaps.iter().map(|h| h.as_str().into()).collect()),
                    ),
                ]))
            })
            .collect::<Result<Vec<_>, String>>()?;
        let mut segments = Vec::new();
        for heap in &self.heap.heaps {
            if let Some((count, size)) = heap_totals.remove(heap.as_str()) {
                segments.push(Value::map([
                    ("name", heap.as_str().into()),
                    ("sizeBytes", number(size)?),
                    ("objectCount", number(count)?),
                ]));
            }
        }
        Ok(Value::map([
            (
                "summary",
                Value::map([
                    ("objectCount", number(count)?),
                    ("shallowSize", number(shallow)?),
                    ("classCount", number(classes.len() as u64)?),
                    ("heapSegments", Value::List(segments)),
                ]),
            ),
            ("classes", Value::List(classes)),
        ]))
    }

    pub fn instances(
        &self,
        names: &[String],
        heap: Option<&str>,
        offset: usize,
        limit: usize,
        ordinary: bool,
        anchor: Option<u64>,
    ) -> Result<Value, String> {
        check_page(offset, limit, 4096)?;
        let names: HashSet<&str> = names.iter().map(String::as_str).collect();
        let selected = |node: u32| {
            names.contains(self.group_name(node))
                && (heap.is_none() || self.heap_name(node) == heap)
                && (!ordinary
                    || self
                        .heap
                        .objects
                        .get(node as usize)
                        .is_some_and(|o| matches!(o.kind, ObjectKind::Instance)))
        };
        let mut total = 0usize;
        let mut anchor_position = None;
        for &node in &self.sorted {
            if selected(node) {
                if anchor == Some(id_of(&self.heap, node)) {
                    anchor_position = Some(total);
                }
                total += 1;
            }
        }
        let offset = anchor_position.map(|i| i / limit * limit).unwrap_or(offset);
        let mut rows = Vec::new();
        let mut index = 0usize;
        let mut class_ordinals: HashMap<&str, usize> = HashMap::new();
        for &node in &self.sorted {
            if !selected(node) {
                continue;
            }
            let ordinal = class_ordinals.entry(self.group_name(node)).or_default();
            *ordinal += 1;
            if index >= offset && rows.len() < limit {
                rows.push(self.instance_row(node, *ordinal)?);
            }
            index += 1;
            if index >= offset.saturating_add(limit) {
                break;
            }
        }
        Ok(Value::map([
            ("total", number(total as u64)?),
            ("offset", number(offset as u64)?),
            ("rows", Value::List(rows)),
        ]))
    }
    fn instance_row(&self, node: u32, index: usize) -> Result<Value, String> {
        Ok(Value::map([
            ("objectId", Value::Int(id_of(&self.heap, node) as i64)),
            ("index", number(index as u64)?),
            ("shallowSize", number(self.shallow(node))?),
            (
                "retainedSize",
                number(self.analysis.retained[node as usize])?,
            ),
            ("depth", self.depth(node)),
            (
                "reachable",
                (self.analysis.traversal.depth[node as usize] != NONE).into(),
            ),
            ("nativeSize", Value::Null),
            ("shallowSizeKnown", self.shallow_known(node).into()),
            ("className", self.runtime_name(node).into()),
            ("representedClassName", self.represented(node)),
        ]))
    }
    fn represented(&self, node: u32) -> Value {
        if node as usize >= self.heap.objects.len() {
            self.group_name(node).into()
        } else {
            Value::Null
        }
    }
    fn children(&self, node: u32) -> &[u32] {
        let p = if node == NONE {
            self.analysis.retained.len()
        } else {
            node as usize
        };
        &self.dom_children[self.dom_offsets[p] as usize..self.dom_offsets[p + 1] as usize]
    }
    pub fn dominators(
        &self,
        parent: Option<u64>,
        offset: usize,
        limit: usize,
    ) -> Result<Value, String> {
        check_page(offset, limit, 4096)?;
        let children = match parent {
            None => self.visible_dom_roots.as_slice(),
            Some(id) => match node_of(&self.heap, id) {
                Some(node) => self.children(node),
                None => &[],
            },
        };
        let visible = children
            .iter()
            .copied()
            .filter(|node| (*node as usize) < self.heap.objects.len());
        let total = visible.clone().count();
        let rows = visible
            .skip(offset)
            .take(limit)
            .map(|node| {
                let parent = self.analysis.immediate[node as usize];
                Ok(Value::map([
                    ("objectId", Value::Int(id_of(&self.heap, node) as i64)),
                    ("className", self.group_name(node).into()),
                    ("shallowSize", number(self.shallow(node))?),
                    (
                        "retainedSize",
                        number(self.analysis.retained[node as usize])?,
                    ),
                    ("depth", self.dom_depth[node as usize].into()),
                    (
                        "parentObjectId",
                        if parent == NONE {
                            Value::Null
                        } else {
                            Value::Int(id_of(&self.heap, parent) as i64)
                        },
                    ),
                ]))
            })
            .collect::<Result<Vec<_>, String>>()?;
        Ok(Value::map([
            ("total", number(total as u64)?),
            ("rows", Value::List(rows)),
        ]))
    }
    pub fn identity(&self, id: u64) -> Result<Value, String> {
        let Some(node) = node_of(&self.heap, id) else {
            return Ok(Value::Null);
        };
        let kind = match self.heap.objects.get(node as usize).map(|o| &o.kind) {
            None => "CLASS",
            Some(ObjectKind::Instance) => "INSTANCE",
            Some(ObjectKind::ObjectArray) => "OBJECT_ARRAY",
            Some(ObjectKind::PrimitiveArray { .. }) => "PRIMITIVE_ARRAY",
        };
        Ok(Value::map([
            ("className", self.runtime_name(node).into()),
            ("groupingClassName", self.group_name(node).into()),
            ("heapName", self.heap_name(node).into()),
            ("shallowSize", number(self.shallow(node))?),
            ("kind", kind.into()),
        ]))
    }

    pub fn all_values(&self, node: u32) -> Result<Vec<HeapValue>, String> {
        if let Some(object) = self.heap.objects.get(node as usize) {
            let count = match object.kind {
                ObjectKind::Instance => self
                    .heap
                    .class_by_id
                    .get(&object.class_id)
                    .map(|i| {
                        analysis::observed_fields(
                            &self.heap,
                            object,
                            &self.analysis.graph.layouts[*i],
                        )
                    })
                    .unwrap_or(0),
                _ => object.count as usize,
            };
            if count > crate::wire::MAX_VALUES {
                return Err("object fields exceed response value budget".into());
            }
            let mut values = Vec::new();
            values
                .try_reserve(count)
                .map_err(|_| "cannot allocate field page")?;
            for start in (0..count).step_by(READ_CHUNK as usize) {
                let limit = (count - start).min(READ_CHUNK as usize) as u32;
                let page = self.heap.values(object, start as u32, limit)?;
                if page.len() != limit as usize {
                    return Err("incomplete field page".into());
                }
                values.extend(page);
            }
            Ok(values)
        } else {
            Ok(self.heap.classes[node as usize - self.heap.objects.len()]
                .static_fields
                .clone())
        }
    }
    pub fn detail(&self, id: u64, start: usize, limit: usize) -> Result<Value, String> {
        check_page(start, limit, 200)?;
        let Some(node) = node_of(&self.heap, id) else {
            return Ok(Value::Null);
        };
        let object = self.heap.objects.get(node as usize);
        let is_array = object.is_some_and(|o| !matches!(o.kind, ObjectKind::Instance));
        let values = if is_array {
            let object = object.ok_or("array identity unavailable")?;
            if start >= object.count as usize
                || matches!(
                    object.kind,
                    ObjectKind::PrimitiveArray {
                        has_data: false,
                        ..
                    }
                )
            {
                Vec::new()
            } else {
                self.heap.values(object, start as u32, limit as u32)?
            }
        } else {
            self.all_values(node)?
        };
        let mut last_primitive = HashMap::new();
        for (i, value) in values.iter().enumerate() {
            if value.ty != 2 {
                last_primitive.insert(value.name.as_str(), i);
            }
        }
        let mut fields = Vec::new();
        let mut field_budget = crate::wire::MAX_FRAME - 2048;
        for (i, value) in values.iter().enumerate() {
            if value.ty != 2 && last_primitive.get(value.name.as_str()) != Some(&i) {
                continue;
            }
            if fields.len() >= crate::wire::MAX_VALUES / 15 {
                return Err(
                    "field result exceeds protocol value budget; fields are not truncated".into(),
                );
            }
            let row = self.field(value, node)?;
            field_budget = field_budget
                .checked_sub(crate::wire::encode(&row)?.len())
                .ok_or("field result exceeds 32 MiB; fields are not truncated")?;
            fields.push(row);
        }
        Ok(Value::map([
            ("objectId", Value::Int(id as i64)),
            ("className", self.runtime_name(node).into()),
            ("shallowSize", number(self.shallow(node))?),
            (
                "retainedSize",
                number(self.analysis.retained[node as usize])?,
            ),
            ("depth", self.depth(node)),
            ("isArray", is_array.into()),
            (
                "elementCount",
                if is_array {
                    object.map(|o| o.count).into()
                } else {
                    Value::Null
                },
            ),
            ("arrayPageSize", number(limit as u64)?),
            ("fields", Value::List(fields)),
            (
                "referenceChain",
                self.chain(node, &self.analysis.traversal)?,
            ),
            ("references", self.inbound(node)?),
            ("shallowSizeKnown", self.shallow_known(node).into()),
            ("nativeSize", Value::Null),
            ("valueKind", self.kind(id).into()),
            ("representedClassName", self.represented(node)),
            ("stringPreview", self.string_preview(node)?.into()),
            ("retainedNativeSize", Value::Null),
        ]))
    }
    fn field(&self, value: &HeapValue, owner: u32) -> Result<Value, String> {
        if value.ty == 2 {
            let fallback = if self
                .heap
                .objects
                .get(owner as usize)
                .is_some_and(|o| matches!(o.kind, ObjectKind::ObjectArray))
            {
                None
            } else {
                Some("<unknown>")
            };
            return self.object_field_with_fallback(&value.name, value.bits, fallback);
        }
        let size = primitive_width(value.ty);
        Ok(Value::map([
            ("name", value.name.as_str().into()),
            (
                "displayValue",
                render_primitive(value.ty, value.bits).into(),
            ),
            ("targetObjectId", Value::Null),
            ("targetClassName", Value::Null),
            ("depth", self.depth(owner)),
            ("valueKind", "PRIMITIVE".into()),
            ("shallowSize", size.into()),
            ("retainedSize", size.into()),
            ("nativeSize", Value::Int(0)),
            ("retainedNativeSize", Value::Null),
            ("representedClassName", Value::Null),
            ("stringPreview", Value::Null),
            ("childCount", Value::Int(0)),
        ]))
    }
    fn kind(&self, id: u64) -> &'static str {
        if id == 0 {
            return "NULL";
        }
        let Some(node) = node_of(&self.heap, id) else {
            return "UNKNOWN";
        };
        match self.heap.objects.get(node as usize).map(|o| &o.kind) {
            None => "CLASS",
            Some(ObjectKind::Instance) => {
                if self.group_name(node) == "java.lang.String" {
                    "STRING"
                } else {
                    "OBJECT"
                }
            }
            _ => "ARRAY",
        }
    }
    pub fn object_field(&self, name: &str, id: u64) -> Result<Value, String> {
        self.object_field_with_fallback(name, id, None)
    }
    fn object_field_with_fallback(
        &self,
        name: &str,
        id: u64,
        fallback: Option<&str>,
    ) -> Result<Value, String> {
        let node = node_of(&self.heap, id);
        let display = if id == 0 {
            "null".to_owned()
        } else {
            let hex = signed_hex(id);
            node.map(|n| format!("0x{hex} \u{00b7} {}", self.group_name(n)))
                .unwrap_or_else(|| format!("0x{hex}"))
        };
        let child_count = match node {
            Some(n) => match self.heap.objects.get(n as usize) {
                Some(o) => match o.kind {
                    ObjectKind::Instance => self
                        .heap
                        .class_by_id
                        .get(&o.class_id)
                        .map(|i| self.analysis.graph.layouts[*i].len() as u64),
                    _ => Some(o.count as u64),
                },
                None => None,
            },
            None => {
                if id == 0 {
                    Some(0)
                } else {
                    None
                }
            }
        };
        let shallow = if id == 0 {
            Value::Int(0)
        } else {
            node.filter(|n| self.shallow_known(*n))
                .map(|n| number(self.shallow(n)))
                .transpose()?
                .unwrap_or(Value::Null)
        };
        Ok(Value::map([
            ("name", name.into()),
            ("displayValue", display.into()),
            ("targetObjectId", Value::Int(id as i64)),
            (
                "targetClassName",
                node.map(|n| self.runtime_name(n)).or(fallback).into(),
            ),
            (
                "depth",
                node.filter(|_| id != 0)
                    .map(|n| self.depth(n))
                    .unwrap_or(Value::Null),
            ),
            ("valueKind", self.kind(id).into()),
            ("shallowSize", shallow),
            (
                "retainedSize",
                if id == 0 {
                    Value::Int(0)
                } else {
                    node.map(|n| number(self.analysis.retained[n as usize]))
                        .transpose()?
                        .unwrap_or(Value::Null)
                },
            ),
            (
                "nativeSize",
                if id == 0 { Value::Int(0) } else { Value::Null },
            ),
            ("retainedNativeSize", Value::Null),
            (
                "representedClassName",
                node.map(|n| self.represented(n)).unwrap_or(Value::Null),
            ),
            (
                "stringPreview",
                node.map(|n| self.string_preview(n))
                    .transpose()?
                    .flatten()
                    .into(),
            ),
            (
                "childCount",
                child_count.map(number).transpose()?.unwrap_or(Value::Null),
            ),
        ]))
    }
    pub fn chain(&self, node: u32, traversal: &Traversal) -> Result<Value, String> {
        if traversal.depth[node as usize] == NONE {
            return Ok(Value::List(Vec::new()));
        }
        if traversal.depth[node as usize] as usize > crate::wire::MAX_VALUES / 4 - 1 {
            return Err("reference chain exceeds protocol value budget".into());
        }
        let mut remaining = crate::wire::MAX_FRAME - 1024;
        let mut reversed = Vec::new();
        let mut current = node;
        while traversal.predecessor[current as usize] != NONE {
            let source = traversal.predecessor[current as usize];
            let name = self.analysis.graph.field_name(
                &self.heap,
                source,
                traversal.slot[current as usize],
            )?;
            let row = self.reference(&name, current);
            remaining = remaining
                .checked_sub(crate::wire::encode(&row)?.len())
                .ok_or("reference chain exceeds 32 MiB; chain is not truncated")?;
            reversed.push(row);
            current = source;
        }
        if let Some(&root) = traversal.roots.get(&current) {
            reversed.push(self.reference(
                &format!("GC Root ({})", self.heap.roots[root].kind),
                current,
            ));
        }
        reversed.reverse();
        Ok(Value::List(reversed))
    }
    fn reference(&self, name: &str, target: u32) -> Value {
        Value::map([
            ("fieldName", name.into()),
            (
                "targetObjectId",
                Value::Int(id_of(&self.heap, target) as i64),
            ),
            ("targetClassName", self.group_name(target).into()),
        ])
    }
    fn inbound(&self, target: u32) -> Result<Value, String> {
        self.analysis.graph.check_storage()?;
        let mut sources = Vec::new();
        for source in 0..self.analysis.retained.len() as u32 {
            for edge in self.analysis.graph.range(source) {
                if self.analysis.graph.edge(edge)?.0 == target {
                    sources
                        .try_reserve(1)
                        .map_err(|_| "cannot allocate inbound sources")?;
                    sources.push(source);
                    break;
                }
            }
        }
        sources.sort_by_key(|n| {
            (
                self.analysis.traversal.depth[*n as usize],
                id_of(&self.heap, *n) as i64,
            )
        });
        let mut rows = Vec::new();
        let mut remaining = crate::wire::MAX_FRAME - 2048;
        for source in sources {
            if rows.len() >= crate::wire::MAX_VALUES / 15 {
                return Err(
                    "inbound references exceed protocol value budget; references are not truncated"
                        .into(),
                );
            }
            let is_array = self
                .heap
                .objects
                .get(source as usize)
                .is_some_and(|o| matches!(o.kind, ObjectKind::ObjectArray));
            let mut slots = Vec::new();
            for edge in self.analysis.graph.range(source) {
                let (edge_target, slot) = self.analysis.graph.edge(edge)?;
                if edge_target == target {
                    slots
                        .try_reserve(1)
                        .map_err(|_| "cannot allocate inbound slots")?;
                    slots.push(slot);
                }
            }
            slots.sort_unstable(); // Kotlin inbound names preserve field/element declaration order.
            let mut name = if is_array {
                "Index ".to_owned()
            } else {
                String::new()
            };
            for (i, slot) in slots.into_iter().enumerate() {
                let field_name = if is_array {
                    slot.to_string()
                } else {
                    self.analysis.graph.field_name(&self.heap, source, slot)?
                };
                if name
                    .len()
                    .saturating_add(field_name.len())
                    .saturating_add(2)
                    > crate::wire::MAX_STRING
                {
                    return Err("inbound field names exceed protocol string budget; references are not truncated".into());
                }
                if i > 0 {
                    name.push_str(", ");
                }
                name.push_str(&field_name);
            }
            let mut row = self.object_field(&name, id_of(&self.heap, source))?;
            if let Value::Map(fields) = &mut row {
                fields.insert(
                    "displayValue".into(),
                    format!(
                        "{} @0x{}",
                        self.group_name(source),
                        signed_hex(id_of(&self.heap, source))
                    )
                    .into(),
                );
                fields.insert("childCount".into(), Value::Null);
            }
            remaining = remaining
                .checked_sub(crate::wire::encode(&row)?.len())
                .ok_or("inbound references exceed 32 MiB; references are not truncated")?;
            rows.push(row);
        }
        Ok(Value::List(rows))
    }
    fn string_preview(&self, node: u32) -> Result<Option<String>, String> {
        if self.group_name(node) != "java.lang.String" || node as usize >= self.heap.objects.len() {
            return Ok(None);
        }
        let values = self.all_values(node)?;
        let Some(array_id) = values
            .iter()
            .find(|v| v.ty == 2 && v.name == "value")
            .map(|v| v.bits)
        else {
            return Ok(None);
        };
        let Some(&index) = self.heap.object_by_id.get(&array_id) else {
            return Ok(None);
        };
        let backing = &self.heap.objects[index];
        let ObjectKind::PrimitiveArray { ty, has_data: true } = backing.kind else {
            return Ok(None);
        };
        if ty != 5 && ty != 8 {
            return Ok(None);
        }
        let offset = values
            .iter()
            .rev()
            .find(|v| v.ty != 2 && v.name == "offset")
            .map(|v| v.bits as i32)
            .unwrap_or(0);
        if offset < 0 || offset as u32 > backing.count {
            return Ok(None);
        }
        let count = values
            .iter()
            .rev()
            .find(|v| v.ty != 2 && v.name == "count")
            .map(|v| v.bits as i32 as i64)
            .unwrap_or((backing.count - offset as u32) as i64);
        if count < 0 || count as u64 > (backing.count - offset as u32) as u64 {
            return Ok(None);
        }
        let count = (count as u32).min(1024);
        let data = self.heap.values(backing, offset as u32, count)?;
        if data.len() != count as usize {
            return Err("incomplete string preview".into());
        }
        let text = if ty == 5 {
            String::from_utf16_lossy(&data.iter().map(|v| v.bits as u16).collect::<Vec<_>>())
        } else {
            String::from_utf8_lossy(&data.iter().map(|v| v.bits as u8).collect::<Vec<_>>())
                .into_owned()
        };
        let mut units: Vec<u16> = text.encode_utf16().collect();
        let text = if units.len() == 1024 {
            units.pop();
            format!("{}...", String::from_utf16_lossy(&units))
        } else {
            text
        };
        Ok(Some(format!("\"{text}\"")))
    }
}

fn check_page(offset: usize, limit: usize, max: usize) -> Result<(), String> {
    if limit == 0 || limit > max {
        return Err(format!("limit must be 1..{max}"));
    }
    if offset > i64::MAX as usize {
        return Err("offset exceeds signed i64".into());
    }
    Ok(())
}
fn add(total: &mut u64, value: u64) -> Result<(), String> {
    *total = total.checked_add(value).ok_or("heap metric overflow")?;
    Ok(())
}
fn optional_index(value: u32) -> Value {
    if value == NONE {
        Value::Null
    } else {
        value.into()
    }
}
fn signed_hex(id: u64) -> String {
    let id = id as i64;
    if id < 0 {
        format!("-{:x}", id.unsigned_abs())
    } else {
        format!("{id:x}")
    }
}
fn primitive_array_name(ty: u8) -> &'static str {
    match ty {
        4 => "boolean[]",
        5 => "char[]",
        6 => "float[]",
        7 => "double[]",
        8 => "byte[]",
        9 => "short[]",
        10 => "int[]",
        11 => "long[]",
        _ => "<unknown-primitive-array>",
    }
}
fn primitive_width(ty: u8) -> Option<u32> {
    match ty {
        4 | 8 => Some(1),
        5 | 9 => Some(2),
        6 | 10 => Some(4),
        7 | 11 => Some(8),
        _ => None,
    }
}
fn float_text(decimal: String, scientific: String, value: f64) -> String {
    if value.is_nan() {
        return "NaN".into();
    }
    if value.is_infinite() {
        return if value.is_sign_negative() {
            "-Infinity"
        } else {
            "Infinity"
        }
        .into();
    }
    if value != 0.0 && (value.abs() >= 1e7 || value.abs() < 1e-3) {
        if let Some((mantissa, exponent)) = scientific.split_once('e') {
            return format!(
                "{}{}E{}",
                mantissa,
                if mantissa.contains('.') { "" } else { ".0" },
                exponent
            );
        }
    }
    if decimal.contains('.') {
        decimal
    } else {
        format!("{decimal}.0")
    }
}
fn render_primitive(ty: u8, bits: u64) -> String {
    match ty {
        4 => (bits != 0).to_string(),
        5 => char::from_u32(bits as u16 as u32)
            .unwrap_or('\u{fffd}')
            .to_string(),
        6 => {
            let f = f32::from_bits(bits as u32);
            float_text(f.to_string(), format!("{f:e}"), f as f64)
        }
        7 => {
            let f = f64::from_bits(bits);
            float_text(f.to_string(), format!("{f:e}"), f)
        }
        8 => (bits as i8).to_string(),
        9 => (bits as i16).to_string(),
        10 => (bits as i32).to_string(),
        _ => (bits as i64).to_string(),
    }
}
