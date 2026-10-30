use aps_hprof_engine::analysis::{dominators, Graph, Traversal, NONE};
use std::collections::{HashMap, VecDeque};

fn graph(edges: &[Vec<u32>]) -> Graph {
    let mut offsets = vec![0];
    let mut targets = Vec::new();
    let mut slots = Vec::new();
    for list in edges {
        for (slot, &target) in list.iter().enumerate() {
            targets.push(target);
            slots.push(slot as u32);
        }
        offsets.push(targets.len() as u32);
    }
    Graph {
        offsets,
        targets,
        slots,
        layouts: vec![],
    }
}
fn traversal(edges: &[Vec<u32>], roots: &[u32]) -> Traversal {
    let n = edges.len();
    let mut depth = vec![NONE; n];
    let mut queue = VecDeque::new();
    for &root in roots {
        depth[root as usize] = 0;
        queue.push_back(root);
    }
    while let Some(source) = queue.pop_front() {
        for &target in &edges[source as usize] {
            if depth[target as usize] == NONE {
                depth[target as usize] = depth[source as usize] + 1;
                queue.push_back(target);
            }
        }
    }
    Traversal {
        depth,
        predecessor: vec![NONE; n],
        slot: vec![NONE; n],
        roots: roots.iter().map(|r| (*r, 0)).collect::<HashMap<_, _>>(),
    }
}
fn reachable_without(edges: &[Vec<u32>], roots: &[u32], removed: Option<usize>) -> Vec<bool> {
    let mut found = vec![false; edges.len()];
    let mut queue = VecDeque::new();
    for &root in roots {
        if Some(root as usize) != removed {
            found[root as usize] = true;
            queue.push_back(root);
        }
    }
    while let Some(source) = queue.pop_front() {
        for &target in &edges[source as usize] {
            if Some(target as usize) != removed && !found[target as usize] {
                found[target as usize] = true;
                queue.push_back(target);
            }
        }
    }
    found
}
fn oracle(edges: &[Vec<u32>], roots: &[u32], sizes: &[u64]) -> (Vec<u64>, Vec<u32>) {
    if roots.is_empty() {
        return (sizes.to_vec(), vec![NONE; edges.len()]);
    }
    let reachable = reachable_without(edges, roots, None);
    let dominates: Vec<_> = (0..edges.len())
        .map(|candidate| {
            let without = reachable_without(edges, roots, Some(candidate));
            (0..edges.len())
                .map(|node| reachable[node] && !without[node])
                .collect::<Vec<_>>()
        })
        .collect();
    let mut retained = sizes.to_vec();
    let mut parents = vec![NONE; edges.len()];
    for node in 0..edges.len() {
        if !reachable[node] {
            continue;
        }
        retained[node] = sizes
            .iter()
            .enumerate()
            .filter(|(i, _)| dominates[node][*i])
            .map(|(_, size)| size)
            .sum();
        parents[node] = (0..edges.len())
            .filter(|p| *p != node && dominates[*p][node])
            .max_by_key(|p| {
                (0..edges.len())
                    .filter(|ancestor| dominates[*ancestor][*p])
                    .count()
            })
            .map(|p| p as u32)
            .unwrap_or(NONE);
    }
    (retained, parents)
}

#[test]
fn diamond_cycles_duplicate_edges_multiple_roots_and_garbage() {
    for roots in [vec![0], vec![0, 2], vec![]] {
        let edges = vec![
            vec![1, 2, 2],
            vec![3],
            vec![3],
            vec![4],
            vec![3],
            vec![6],
            vec![5],
        ];
        let sizes = vec![1, 2, 4, 8, 16, 32, 64];
        assert_eq!(
            dominators(&graph(&edges), &traversal(&edges, &roots), &sizes).unwrap(),
            oracle(&edges, &roots, &sizes)
        );
    }
}

#[test]
fn lt_matches_independent_vertex_removal_oracle_on_3000_graphs() {
    let mut seed = 0x9e3779b97f4a7c15u64;
    let mut random = || {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        seed
    };
    for case in 0..3000 {
        let n = (random() % 9 + 1) as usize;
        let mut edges = vec![vec![]; n];
        for list in &mut edges {
            for target in 0..n {
                if random() % 5 == 0 {
                    list.push(target as u32);
                }
            }
        }
        let roots: Vec<_> = (0..n)
            .filter(|_| random() % 4 == 0)
            .map(|i| i as u32)
            .collect();
        let sizes: Vec<_> = (0..n).map(|_| random() % 100).collect();
        assert_eq!(
            dominators(&graph(&edges), &traversal(&edges, &roots), &sizes).unwrap(),
            oracle(&edges, &roots, &sizes),
            "case {case}, {edges:?}, roots {roots:?}"
        );
    }
}

#[test]
fn deep_graph_is_iterative_and_retained_overflow_is_an_error() {
    let n = 100_000;
    let edges: Vec<_> = (0..n)
        .map(|i| {
            if i + 1 < n {
                vec![(i + 1) as u32]
            } else {
                vec![]
            }
        })
        .collect();
    let (retained, parents) =
        dominators(&graph(&edges), &traversal(&edges, &[0]), &vec![1; n]).unwrap();
    assert_eq!(retained[0], n as u64);
    assert_eq!(parents[n - 1], (n - 2) as u32);
    let edges = vec![vec![1], vec![]];
    assert!(
        dominators(&graph(&edges), &traversal(&edges, &[0]), &[u64::MAX, 1])
            .unwrap_err()
            .contains("overflow")
    );
}
