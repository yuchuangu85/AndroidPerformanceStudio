//! Read-only, complete-array fingerprints for before/after analysis verification.
use aps_hprof_engine::{
    Engine, cache,
    model::{Heap, ObjectKind},
};
use serde_json::json;
use std::{
    collections::BTreeMap,
    io::{self, Read},
    path::Path,
};

struct Words<I> {
    words: I,
    pending: [u8; 8],
    offset: usize,
}

impl<I: Iterator<Item = u64>> Read for Words<I> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        let mut written = 0;
        while written < output.len() {
            if self.offset == 8 {
                let Some(word) = self.words.next() else {
                    break;
                };
                self.pending = word.to_be_bytes();
                self.offset = 0;
            }
            let count = (8 - self.offset).min(output.len() - written);
            output[written..written + count]
                .copy_from_slice(&self.pending[self.offset..self.offset + count]);
            self.offset += count;
            written += count;
        }
        Ok(written)
    }
}

fn fingerprint(words: impl Iterator<Item = u64>) -> Result<String, String> {
    cache::sha256_reader(
        Words {
            words,
            pending: [0; 8],
            offset: 8,
        },
        u64::MAX / 8,
    )
}

fn text(value: &str) -> impl Iterator<Item = u64> + '_ {
    std::iter::once(value.len() as u64).chain(value.bytes().map(u64::from))
}

fn main() -> Result<(), String> {
    let args: Vec<_> = std::env::args().collect();
    let path = Path::new(args.get(1).ok_or("usage: analysis_fingerprint <hprof>")?);
    let source_digest = cache::sha256_file(path)?;
    let engine = Engine::new(Heap::open(path)?, BTreeMap::new())?;
    let heap = &engine.heap;
    let analysis = &engine.analysis;
    let graph = &analysis.graph;
    let edge_count = *graph.offsets.last().ok_or("missing graph offsets")? as usize;
    let native = aps_hprof_engine::native::map_native_sizes(heap)?;
    graph.check_storage()?;
    let digests = json!({
        "objects": fingerprint(heap.objects.iter().flat_map(|object| {
            let kind = match object.kind {
                ObjectKind::Instance => 0,
                ObjectKind::ObjectArray => 1,
                ObjectKind::PrimitiveArray { ty, has_data } => 2 | (u64::from(ty) << 8) | (u64::from(has_data) << 16),
            };
            [object.id, object.class_id, object.payload_offset, u64::from(object.count), object.heap as u64, object.shallow_size, kind]
        }))?,
        "classes": fingerprint(heap.classes.iter().flat_map(|class| {
            [class.id, class.super_id, class.instance_size, class.loader_id, class.heap as u64, class.fields.len() as u64, class.static_fields.len() as u64].into_iter()
                .chain(text(&class.name))
                .chain(class.fields.iter().flat_map(|field| std::iter::once(u64::from(field.ty)).chain(text(&field.name))))
                .chain(class.static_fields.iter().flat_map(|field| [u64::from(field.ty), field.bits].into_iter().chain(text(&field.name))))
        }))?,
        "root_records": fingerprint(heap.roots.iter().flat_map(|root| std::iter::once(root.id).chain(text(&root.kind))))?,
        "heaps": fingerprint(heap.heaps.iter().flat_map(|heap| text(heap)))?,
        "graph_offsets": fingerprint(graph.offsets.iter().copied().map(u64::from))?,
        "graph_edges_and_slots": fingerprint((0..edge_count).flat_map(|edge| {
            let (target, slot) = graph.edge(edge).expect("validated memory graph edge");
            [u64::from(target), u64::from(slot)]
        }))?,
        "depth": fingerprint(analysis.traversal.depth.iter().copied().map(u64::from))?,
        "predecessor": fingerprint(analysis.traversal.predecessor.iter().copied().map(u64::from))?,
        "root_path_slots": fingerprint(analysis.traversal.slot.iter().copied().map(u64::from))?,
        "retained": fingerprint(analysis.retained.iter().copied())?,
        "immediate_dominators": fingerprint(analysis.immediate.iter().copied().map(u64::from))?,
        "native_sizes": native.as_ref().map(|values| fingerprint(values.iter().copied())).transpose()?,
        "shallow_by_node": fingerprint((0..analysis.retained.len() as u32).map(|node| engine.shallow(node)))?,
    });
    let native_sum = native
        .as_ref()
        .map(|values| {
            values.iter().try_fold(0u64, |sum, value| {
                sum.checked_add(*value).ok_or("native sum overflow")
            })
        })
        .transpose()?;
    heap.check_source()?;
    graph.check_storage()?;
    if cache::sha256_file(path)? != source_digest {
        return Err("source bytes changed while checking analysis".into());
    }
    println!(
        "{}",
        json!({
            "source_digest":source_digest, "objects":heap.objects.len(), "classes":heap.classes.len(),
            "nodes":analysis.retained.len(), "edges":edge_count, "root_records":heap.roots.len(),
            "native_owners":native.as_ref().map(|values| values.iter().filter(|&&value| value != 0).count()),
            "native_total":native_sum, "fingerprints":digests,
            "encoding":"length-prefixed text and unsigned 64-bit big-endian words; all elements, no sampling"
        })
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn word_stream_keeps_every_byte_with_unaligned_read_buffers() {
        let input = [0, 1, 0x0102_0304_0506_0708, u64::MAX];
        let expected: Vec<_> = input
            .iter()
            .flat_map(|word| u64::to_be_bytes(*word))
            .collect();
        for size in [1, 3, 7, 8, 9, 32, 64] {
            let mut reader = Words {
                words: input.into_iter(),
                pending: [0; 8],
                offset: 8,
            };
            assert_eq!(reader.read(&mut []).unwrap(), 0);
            let mut actual = Vec::new();
            let mut buffer = vec![0; size];
            loop {
                let count = reader.read(&mut buffer).unwrap();
                if count == 0 {
                    break;
                }
                actual.extend_from_slice(&buffer[..count]);
            }
            assert_eq!(actual, expected);
        }
        assert_eq!(
            fingerprint(input.into_iter()).unwrap(),
            cache::sha256_reader(io::Cursor::new(expected), 1024).unwrap()
        );
    }
}
