use std::collections::BTreeSet;

const MAGIC_LE: &[u8; 8] = b"PERFILE2";
const MAGIC_BE: &[u8; 8] = b"2ELIFREP";
const HEADER_SIZE: usize = 104;
const SECTION_SIZE: usize = 16;
const FEATURE_BITMAP_WORDS: usize = 4;
const RECORD_HEADER_SIZE: usize = 8;
const PERF_RECORD_SPLIT: u32 = 32_772;
const PERF_RECORD_SPLIT_END: u32 = 32_773;
const MAX_RECORDS: usize = 1_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ByteOrder {
    Little,
    Big,
}

impl ByteOrder {
    fn u16(self, bytes: &[u8]) -> u16 {
        let bytes = [bytes[0], bytes[1]];
        match self {
            Self::Little => u16::from_le_bytes(bytes),
            Self::Big => u16::from_be_bytes(bytes),
        }
    }

    fn u32(self, bytes: &[u8]) -> u32 {
        let bytes = [bytes[0], bytes[1], bytes[2], bytes[3]];
        match self {
            Self::Little => u32::from_le_bytes(bytes),
            Self::Big => u32::from_be_bytes(bytes),
        }
    }

    fn u64(self, bytes: &[u8]) -> u64 {
        let bytes = [
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
        ];
        match self {
            Self::Little => u64::from_le_bytes(bytes),
            Self::Big => u64::from_be_bytes(bytes),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct PerfDataSummary {
    pub records: usize,
    pub data_bytes: usize,
    pub feature_count: usize,
}

#[derive(Debug, Clone, Copy)]
struct Section {
    offset: usize,
    size: usize,
}

/// Validates the complete bounded perf.data container used by simpleperf.
/// This intentionally validates framing and section boundaries only; record
/// payload semantics remain owned by the device's simpleperf converter.
pub(crate) fn validate(bytes: &[u8]) -> Result<PerfDataSummary, String> {
    if bytes.len() < HEADER_SIZE {
        return Err("perf.data is truncated before its file header".into());
    }
    let order = match &bytes[..8] {
        magic if magic == MAGIC_LE => ByteOrder::Little,
        magic if magic == MAGIC_BE => ByteOrder::Big,
        _ => return Err("perf.data has an invalid magic".into()),
    };
    let header_size = usize_from_u64(order.u64(&bytes[8..16]), "header size")?;
    if !(HEADER_SIZE..=bytes.len()).contains(&header_size) || header_size % 8 != 0 {
        return Err("perf.data has an invalid header size".into());
    }
    let attr_size = usize_from_u64(order.u64(&bytes[16..24]), "attribute size")?;
    if attr_size < SECTION_SIZE || attr_size % 8 != 0 {
        return Err("perf.data has an invalid attribute size".into());
    }

    let attrs = section(order, &bytes[24..40], "attribute section")?;
    let data = section(order, &bytes[40..56], "data section")?;
    let event_types = section(order, &bytes[56..72], "event type section")?;
    check_section(attrs, header_size, bytes.len(), "attribute section")?;
    check_section(data, header_size, bytes.len(), "data section")?;
    check_section(event_types, header_size, bytes.len(), "event type section")?;
    if attrs.size == 0 || attrs.size % attr_size != 0 {
        return Err("perf.data attribute section is not record-aligned".into());
    }
    if data.size % 8 != 0 {
        return Err("perf.data data section is not 8-byte aligned".into());
    }
    validate_attribute_ids(bytes, attrs, attr_size, order)?;

    let feature_words = (0..FEATURE_BITMAP_WORDS)
        .map(|index| order.u64(&bytes[72 + index * 8..80 + index * 8]))
        .collect::<Vec<_>>();
    let feature_count = feature_words
        .iter()
        .map(|word| word.count_ones() as usize)
        .sum::<usize>();
    let feature_descriptors = validate_features(bytes, data, &feature_words, order)?;
    if feature_descriptors == 0 && data.size == 0 {
        let declared_end = [attrs, data, event_types]
            .into_iter()
            .filter_map(section_end)
            .max()
            .unwrap_or(header_size);
        if bytes.len() != declared_end {
            return Err("perf.data has trailing bytes without feature descriptors".into());
        }
    }
    if feature_descriptors != feature_count {
        return Err("perf.data feature descriptor count does not match its bitmap".into());
    }
    let records = validate_records(&bytes[data.offset..data.offset + data.size], order)?;
    Ok(PerfDataSummary {
        records,
        data_bytes: data.size,
        feature_count,
    })
}

fn usize_from_u64(value: u64, label: &str) -> Result<usize, String> {
    usize::try_from(value).map_err(|_| format!("perf.data {label} exceeds host usize"))
}

fn section(order: ByteOrder, bytes: &[u8], label: &str) -> Result<Section, String> {
    if bytes.len() != SECTION_SIZE {
        return Err(format!("perf.data {label} header is truncated"));
    }
    Ok(Section {
        offset: usize_from_u64(order.u64(&bytes[..8]), &format!("{label} offset"))?,
        size: usize_from_u64(order.u64(&bytes[8..]), &format!("{label} size"))?,
    })
}

fn check_section(
    section: Section,
    minimum: usize,
    file_size: usize,
    label: &str,
) -> Result<(), String> {
    if section.size == 0 && section.offset == 0 {
        return Ok(());
    }
    if section.offset < minimum || section.offset % 8 != 0 {
        return Err(format!("perf.data {label} offset is invalid"));
    }
    let end = section
        .offset
        .checked_add(section.size)
        .ok_or_else(|| format!("perf.data {label} overflows"))?;
    if end > file_size {
        return Err(format!("perf.data {label} exceeds file size"));
    }
    Ok(())
}

fn section_end(section: Section) -> Option<usize> {
    if section.size == 0 && section.offset == 0 {
        None
    } else {
        section.offset.checked_add(section.size)
    }
}

fn validate_attribute_ids(
    bytes: &[u8],
    attrs: Section,
    attr_size: usize,
    order: ByteOrder,
) -> Result<(), String> {
    for index in 0..attrs.size / attr_size {
        let record = attrs.offset + index * attr_size;
        let id = section(
            order,
            &bytes[record + attr_size - SECTION_SIZE..record + attr_size],
            "attribute id section",
        )?;
        check_section(id, 0, bytes.len(), "attribute id section")?;
    }
    Ok(())
}

fn validate_features(
    bytes: &[u8],
    data: Section,
    feature_words: &[u64],
    order: ByteOrder,
) -> Result<usize, String> {
    let feature_count = feature_words
        .iter()
        .map(|word| word.count_ones() as usize)
        .sum::<usize>();
    let descriptor_bytes = feature_count
        .checked_mul(SECTION_SIZE)
        .ok_or_else(|| "perf.data feature descriptors overflow".to_owned())?;
    let descriptor_start = data
        .offset
        .checked_add(data.size)
        .ok_or_else(|| "perf.data feature descriptor offset overflows".to_owned())?;
    let feature_data_start = descriptor_start
        .checked_add(descriptor_bytes)
        .ok_or_else(|| "perf.data feature data offset overflows".to_owned())?;
    if feature_data_start > bytes.len() {
        return Err("perf.data feature descriptors are truncated".into());
    }
    let mut ranges = BTreeSet::new();
    for index in 0..feature_count {
        let start = descriptor_start + index * SECTION_SIZE;
        let descriptor = section(
            order,
            &bytes[start..start + SECTION_SIZE],
            "feature section",
        )?;
        if descriptor.size == 0 {
            continue;
        }
        if descriptor.offset < feature_data_start || descriptor.offset % 8 != 0 {
            return Err("perf.data feature section offset is invalid".into());
        }
        let end = descriptor
            .offset
            .checked_add(descriptor.size)
            .ok_or_else(|| "perf.data feature section overflows".to_owned())?;
        if end > bytes.len()
            || ranges
                .range(..=(descriptor.offset, usize::MAX))
                .next_back()
                .is_some_and(|(_, previous_end)| *previous_end > descriptor.offset)
            || !ranges.insert((descriptor.offset, end))
        {
            return Err("perf.data feature sections overlap or exceed file size".into());
        }
    }
    Ok(feature_count)
}

fn validate_records(bytes: &[u8], order: ByteOrder) -> Result<usize, String> {
    let mut cursor = 0;
    let mut records = 0;
    let mut split_pending = false;
    while cursor < bytes.len() {
        if bytes.len() - cursor < RECORD_HEADER_SIZE {
            return Err("perf.data ends in a partial record header".into());
        }
        let record_type = order.u32(&bytes[cursor..cursor + 4]);
        let size = order.u16(&bytes[cursor + 6..cursor + 8]) as usize;
        if size < RECORD_HEADER_SIZE || size > bytes.len() - cursor {
            return Err(format!("perf.data record {records} has an invalid size"));
        }
        if records >= MAX_RECORDS {
            return Err(format!("perf.data record count exceeds {MAX_RECORDS}"));
        }
        if split_pending {
            if record_type == PERF_RECORD_SPLIT_END {
                split_pending = false;
            } else if record_type != PERF_RECORD_SPLIT {
                return Err("perf.data split record sequence is malformed".into());
            }
        } else if record_type == PERF_RECORD_SPLIT {
            split_pending = true;
        } else if record_type == PERF_RECORD_SPLIT_END {
            return Err("perf.data split end has no matching split record".into());
        }
        cursor += size;
        records += 1;
    }
    if split_pending {
        return Err("perf.data ends in an incomplete split record sequence".into());
    }
    Ok(records)
}

#[cfg(test)]
mod tests {
    use super::validate;

    fn section(bytes: &mut [u8], offset: usize, section_offset: u64, size: u64) {
        bytes[offset..offset + 8].copy_from_slice(&section_offset.to_le_bytes());
        bytes[offset + 8..offset + 16].copy_from_slice(&size.to_le_bytes());
    }

    fn minimal() -> Vec<u8> {
        let mut bytes = vec![0u8; 264];
        bytes[..8].copy_from_slice(b"PERFILE2");
        bytes[8..16].copy_from_slice(&104u64.to_le_bytes());
        bytes[16..24].copy_from_slice(&160u64.to_le_bytes());
        section(&mut bytes, 24, 104, 160);
        section(&mut bytes, 40, 0, 0);
        section(&mut bytes, 56, 0, 0);
        bytes
    }

    fn with_record(size: u16) -> Vec<u8> {
        let mut bytes = vec![0u8; 272];
        bytes[..8].copy_from_slice(b"PERFILE2");
        bytes[8..16].copy_from_slice(&104u64.to_le_bytes());
        bytes[16..24].copy_from_slice(&160u64.to_le_bytes());
        section(&mut bytes, 24, 104, 160);
        section(&mut bytes, 40, 264, 8);
        section(&mut bytes, 56, 0, 0);
        bytes[270..272].copy_from_slice(&size.to_le_bytes());
        bytes
    }

    #[test]
    fn validates_complete_framing_and_rejects_truncation() {
        let bytes = minimal();
        let summary = validate(&bytes).unwrap();
        assert_eq!(summary.records, 0);
        assert_eq!(summary.data_bytes, 0);
        for length in [0, 103, 263] {
            assert!(validate(&bytes[..length]).is_err());
        }
        let mut trailing = bytes.clone();
        trailing.push(1);
        assert!(validate(&trailing).is_err());
    }

    #[test]
    fn rejects_invalid_sections_and_record_sizes() {
        let mut bytes = with_record(8);
        section(&mut bytes, 40, 264, 16);
        assert!(validate(&bytes).is_err());
        assert!(validate(&with_record(4)).is_err());
        let mut bytes = with_record(8);
        bytes[264..268].copy_from_slice(&32_773u32.to_le_bytes());
        assert!(validate(&bytes).is_err());
    }

    #[test]
    fn validates_real_perf_data_when_requested_by_the_harness() {
        let Some(path) = std::env::var_os("APS_CAPTURE_PERF_DATA_PATH") else {
            return;
        };
        let bytes = std::fs::read(path).unwrap();
        let summary = validate(&bytes).unwrap();
        assert!(summary.records > 0);
        assert!(summary.data_bytes > 0);
    }
}
