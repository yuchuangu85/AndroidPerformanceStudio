use aps_hprof_engine::{analysis, model::Heap, Engine};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::Path;

fn main() -> Result<(), String> {
    let args: Vec<String> = std::env::args().collect();
    let engine = Engine::new(Heap::open(Path::new(args.get(1).ok_or("missing HPROF")?))?, BTreeMap::new())?;
    let heap = &engine.heap;
    let mut groups: BTreeMap<(u64, usize), (u64, u64, u64)> = BTreeMap::new();
    let mut array_classes = BTreeMap::new();
    for c in &heap.classes {
        let name = c.name.replace('/', ".");
        array_classes.entry(name).and_modify(|id| *id = 0).or_insert(c.id);
    }
    let class_id = |n: usize| {
        let o = &heap.objects[n];
        if o.class_id != 0 { return o.class_id; }
        // Missing or ambiguous array identity stays unmatched, never guessed.
        array_classes.get(engine.runtime_name(n as u32)).copied().unwrap_or(0)
    };
    for (n, o) in heap.objects.iter().enumerate() {
        let g = groups.entry((class_id(n), o.heap)).or_default();
        g.0 += 1; g.1 += o.shallow_size; g.2 += engine.analysis.retained[n];
    }
    let classes: Vec<Value> = groups.into_iter().map(|((class_id, h), (count, shallow, retained))| {
        json!({"classId":class_id.to_string(), "className":heap.class_by_id.get(&class_id).map(|&i| &heap.classes[i].name),
            "heap":heap.heaps[h], "count":count, "shallow":shallow, "retainedSum":retained})
    }).collect();
    let roots: Vec<Value> = heap.roots.iter().map(|r| json!({"id":r.id.to_string(),"kind":r.kind,"strength":null})).collect();
    let mut objects = Vec::new();
    for arg in &args[2..] {
        let id: u64 = arg.parse().map_err(|_| "invalid object ID")?;
        let node = analysis::node_of(heap, id).ok_or("missing selected object")?;
        let mut current = node;
        let mut chain = Vec::new();
        let traversal = &engine.analysis.traversal;
        let mut complete = false;
        while current != analysis::NONE && traversal.depth[current as usize] != analysis::NONE && chain.len() <= heap.objects.len() + heap.classes.len() {
            chain.push(analysis::id_of(heap, current).to_string());
            if traversal.depth[current as usize] == 0 { complete = true; break; }
            current = traversal.predecessor[current as usize];
        }
        let depth = traversal.depth[node as usize];
        let o = heap.object_by_id.get(&id).map(|&n| &heap.objects[n]);
        objects.push(json!({"id":id.to_string(), "classId":heap.object_by_id.get(&id).map(|&n|class_id(n).to_string()),
            "heap":o.map(|v|&heap.heaps[v.heap]), "shallow":o.map(|v|v.shallow_size),
            "retained":engine.analysis.retained[node as usize], "native":null,
            "depth":if depth == analysis::NONE {None} else {Some(depth)},
            "rootChain":if chain.is_empty() {None} else {Some(chain)},
            "rootChainComplete":complete}));
    }
    println!("{}", json!({"analyzer":"aps-hprof-engine", "classes":classes,"roots":roots,"objects":objects,
        "warnings":heap.warnings.iter().map(|w|&w.message).collect::<Vec<_>>() }));
    Ok(())
}
