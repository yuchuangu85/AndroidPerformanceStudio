//! Android Studio/perflib-compatible NativeAllocationRegistry attribution.
use crate::analysis::{self, NONE};
use crate::model::{Heap, ObjectKind};
use scanner::{NativeScanner, Role};
use std::collections::HashMap;

mod scanner;

/// Maps registered native bytes to the Cleaner referent, matching perflib's
/// NativeRegistryPostProcessor. Missing/incomplete registry records are not
/// converted into zero evidence.
pub fn map_native_sizes(heap: &Heap) -> Result<Option<Vec<u64>>, String> {
    let mut scanner = NativeScanner::new(heap)?;
    let result = scan_native_sizes(heap, &mut scanner);
    // Check even on a read/callback failure; never publish a partially read map.
    scanner.finish()?;
    result
}

fn sort_records(heap: &Heap, records: &mut Vec<usize>) {
    records.sort_unstable_by_key(|&index| (heap.objects[index].payload_offset, index));
    records.dedup();
}

fn scan_native_sizes(
    heap: &Heap,
    scanner: &mut NativeScanner<'_>,
) -> Result<Option<Vec<u64>>, String> {
    let mut cleaners = Vec::new();
    for (index, object) in heap.objects.iter().enumerate() {
        // Skipped primitive/unrelated records still contribute to identity batches.
        scanner.observe(index)?;
        if object.kind == ObjectKind::Instance && scanner.role(index) == Role::Cleaner {
            cleaners.push(index);
        }
    }
    sort_records(heap, &mut cleaners);
    let mut chains = Vec::with_capacity(cleaners.len());
    let mut thunks = Vec::new();
    for index in cleaners {
        scanner.scan(index, |scanner, fields| {
            if let (Some(owner), Some(thunk)) = (fields.referent, fields.thunk)
                && owner != 0
                && thunk != 0
                && let Some(&thunk) = heap.object_by_id.get(&thunk)
                && scanner.role(thunk) == Role::Thunk
            {
                chains.push((index, owner, thunk));
                thunks.push(thunk);
            }
            Ok(())
        })?;
    }
    sort_records(heap, &mut thunks);
    let mut thunk_registries = HashMap::with_capacity(thunks.len());
    let mut registries = Vec::new();
    for index in thunks {
        scanner.scan(index, |scanner, fields| {
            if let Some(registry) = fields.first_reference
                && let Some(&registry) = heap.object_by_id.get(&registry)
                && scanner.role(registry) == Role::Registry
            {
                thunk_registries.insert(index, registry);
                registries.push(registry);
            }
            Ok(())
        })?;
    }
    sort_records(heap, &mut registries);
    let mut registry_sizes = HashMap::with_capacity(registries.len());
    for index in registries {
        scanner.scan(index, |_, fields| {
            if let Some(size) = fields.size {
                registry_sizes.insert(index, size);
            }
            Ok(())
        })?;
    }

    let count = heap
        .objects
        .len()
        .checked_add(heap.classes.len())
        .ok_or("native graph length overflow")?;
    let mut sizes = analysis::filled(count, 0u64)?;
    let mut found = false;
    // Physical read order is independent of perflib's last-Cleaner-wins order.
    chains.sort_unstable_by_key(|&(index, _, _)| index);
    for (_, owner, thunk) in chains {
        if let Some(registry) = thunk_registries.get(&thunk)
            && let Some(&size) = registry_sizes.get(registry)
            && let Some(&owner) = heap.object_by_id.get(&owner)
        {
            sizes[owner] = size;
            found = true;
        }
    }
    Ok(found.then_some(sizes))
}

pub fn retained_native_sizes(
    native: &[u64],
    immediate: &[u32],
    depth: &[u32],
) -> Result<Vec<u64>, String> {
    if native.len() != immediate.len() || native.len() != depth.len() {
        return Err("native graph length mismatch".into());
    }
    let mut retained = native.to_vec();
    let mut order: Vec<usize> = (0..native.len()).collect();
    order.sort_unstable_by_key(|&node| std::cmp::Reverse(depth[node]));
    for node in order {
        let parent = immediate[node];
        if parent != NONE {
            retained[parent as usize] = retained[parent as usize]
                .checked_add(retained[node])
                .ok_or("native retained overflow")?;
        }
    }
    Ok(retained)
}

#[cfg(test)]
mod tests {
    use super::retained_native_sizes;
    use crate::analysis::NONE;
    #[test]
    fn native_retention_follows_dominator_tree() {
        assert_eq!(
            retained_native_sizes(&[5, 7, 11], &[NONE, 0, 1], &[0, 1, 2]).unwrap(),
            [23, 18, 11]
        );
    }
}
