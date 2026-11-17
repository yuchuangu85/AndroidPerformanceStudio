//! Android Studio/perflib-compatible NativeAllocationRegistry attribution.
use crate::analysis::{self, NONE};
use crate::model::{Heap, Object, ObjectKind, Value};

fn values(heap: &Heap, object: &Object) -> Result<Vec<Value>, String> {
    let fields = heap.fields_for(object.class_id);
    let count = analysis::observed_fields(heap, object, &fields);
    heap.values(
        object,
        0,
        u32::try_from(count).map_err(|_| "too many fields")?,
    )
}

fn reference(values: &[Value], name: &str) -> Option<u64> {
    values
        .iter()
        .find(|value| value.name == name && value.ty == 2)
        .map(|value| value.bits)
}

fn exact_class(heap: &Heap, object: &Object, name: &str) -> bool {
    heap.class_by_id
        .get(&object.class_id)
        .is_some_and(|&index| crate::model::class_name_matches(&heap.classes[index].name, name))
}

/// Maps registered native bytes to the Cleaner referent, matching perflib's
/// NativeRegistryPostProcessor. Missing/incomplete registry records are not
/// converted into zero evidence.
pub fn map_native_sizes(heap: &Heap) -> Result<Option<Vec<u64>>, String> {
    let mut sizes = vec![0u64; heap.objects.len() + heap.classes.len()];
    let mut found = false;
    for cleaner in &heap.objects {
        if cleaner.kind != ObjectKind::Instance || !exact_class(heap, cleaner, "sun.misc.Cleaner") {
            continue;
        }
        let cleaner_values = values(heap, cleaner)?;
        let Some(referent_id) = reference(&cleaner_values, "referent").filter(|id| *id != 0) else {
            continue;
        };
        let Some(thunk_id) = reference(&cleaner_values, "thunk").filter(|id| *id != 0) else {
            continue;
        };
        let Some(&thunk_index) = heap.object_by_id.get(&thunk_id) else {
            continue;
        };
        let thunk = &heap.objects[thunk_index];
        if !exact_class(
            heap,
            thunk,
            "libcore.util.NativeAllocationRegistry$CleanerThunk",
        ) {
            continue;
        }
        let registry_id = values(heap, thunk)?
            .into_iter()
            .find(|value| value.ty == 2 && value.bits != 0)
            .map(|value| value.bits);
        let Some(registry_id) = registry_id else {
            continue;
        };
        let Some(&registry_index) = heap.object_by_id.get(&registry_id) else {
            continue;
        };
        let registry = &heap.objects[registry_index];
        if !exact_class(heap, registry, "libcore.util.NativeAllocationRegistry") {
            continue;
        }
        let Some(size) = values(heap, registry)?
            .into_iter()
            .find(|value| value.name == "size" && value.ty == 11)
            .map(|value| value.bits)
        else {
            continue;
        };
        let Some(&owner_index) = heap.object_by_id.get(&referent_id) else {
            continue;
        };
        // Perflib assigns the registry size to the owner; it does not sum
        // multiple Cleaner records for the same owner.
        sizes[owner_index] = size;
        found = true;
    }
    heap.check_source()?;
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
