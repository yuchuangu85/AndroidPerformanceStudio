//! Fixed Perfetto projections. The processor, catalog and SQL are host-owned:
//! bytes/URLs/tool calls can neither nominate an executable nor choose SQL.
use aps_contracts::SourceDigest;
use aps_perfetto::{QueryPreset, TraceProcessor, TraceSession, diagnostic_query};
use aps_platform::CancellationToken;
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

// Field 36 (TracePacket.synchronization_marker), length 16, fixed upstream UUID.
// The tracing service puts this field at the end of its synchronization packet;
// see third_party/perfetto/protos/perfetto/trace/trace_packet.proto.
const MARKER: &[u8] = &[
    0xa2, 0x02, 0x10, 0x82, 0x47, 0x7a, 0x76, 0xb2, 0x8d, 0x42, 0xba, 0x81, 0xdc, 0x33, 0x32, 0x6d,
    0x57, 0xa0, 0x79,
];

/// Decode only the outer Trace.packet length (at most five bytes). No parser
/// decisions or performance metrics are inferred from packet payload bytes.
fn packet(bytes: &[u8]) -> Option<(&[u8], &[u8])> {
    if bytes.first() != Some(&0x0a) {
        return None;
    }
    let mut size = 0u32;
    for i in 0..5 {
        let n = *bytes.get(i + 1)?;
        if i == 4 && n > 15 {
            return None;
        }
        size |= u32::from(n & 127) << (7 * i);
        if n & 128 == 0 {
            let tail = bytes.get(i + 2..)?;
            return tail.split_at_checked(size as usize);
        }
    }
    None
}

pub(crate) fn has_trace_extension(path: &Path) -> bool {
    path.extension()
        .and_then(|s| s.to_str())
        .is_some_and(|ext| {
            ["pftrace", "perfetto-trace"]
                .iter()
                .any(|known| ext.eq_ignore_ascii_case(known))
        })
}

pub(crate) fn looks_like_trace(path: &Path, bytes: &[u8]) -> bool {
    if has_trace_extension(path) {
        return true; // only selects parser; framing + pinned tool must still validate.
    }
    // Protobuf has no global magic: a single 0x0a is NOT a trace signature.
    // Scan at most 64 KiB/128 packets for the distinctive resync marker. A
    // marker-less, extensionless trace needs the explicit --kind trace hint.
    let mut window = &bytes[..bytes.len().min(65536)];
    for _ in 0..128 {
        let Some((body, rest)) = packet(window) else {
            return false;
        };
        if body.len() <= 32 && body.ends_with(MARKER) {
            return true;
        }
        window = rest;
    }
    false
}

pub(crate) fn has_packet(bytes: &[u8]) -> bool {
    packet(bytes).is_some()
}

fn processor_path() -> Result<PathBuf, String> {
    if let Some(value) = std::env::var_os("APS_TRACE_PROCESSOR") {
        let path = PathBuf::from(value);
        if !path.is_absolute() {
            return Err(
                "E_TRACE_TOOL: APS_TRACE_PROCESSOR must be an absolute path to the pinned binary"
                    .into(),
            );
        }
        return Ok(path);
    }
    Ok(std::env::current_exe()
        .map_err(|_| "E_TRACE_TOOL")?
        .with_file_name(format!(
            "trace_processor_shell{}",
            std::env::consts::EXE_SUFFIX
        )))
}

fn numeric<T: std::str::FromStr>(cell: Option<&String>) -> Result<Option<T>, String> {
    cell.map(|s| {
        s.parse()
            .map_err(|_| "E_TRACE_QUERY_SCHEMA: invalid numeric observation".to_string())
    })
    .transpose()
}

pub fn analyze(path: &Path, digest: &str) -> Result<Value, String> {
    let cancel = CancellationToken::new();
    let tool = TraceProcessor::from_verified_path(&processor_path()?, &cancel)
        .map_err(|e| format!("E_TRACE_TOOL: {e}"))?;
    let version = tool.version();
    let checksum = tool.sha256().to_hex();
    let session =
        TraceSession::open(tool, path, &cancel).map_err(|e| format!("E_TRACE_OPEN: {e}"))?;
    if session.source_digest().to_hex() != digest {
        return Err("E_SOURCE_CHANGED: trace changed before analysis".into());
    }
    let bounds = session
        .query(QueryPreset::TraceBounds, 0, 1, &cancel)
        .map_err(|e| format!("E_TRACE_QUERY: {e}"))?;
    let (start, end) = match bounds.rows.as_slice() {
        [] => (None, None),
        [r] if r.len() == 2 && !bounds.has_more => (
            numeric::<i64>(r[0].as_ref())?,
            numeric::<i64>(r[1].as_ref())?,
        ),
        _ => return Err("E_TRACE_QUERY_SCHEMA: expected one trace bounds row".into()),
    };
    // Only emit numeric fields of catalog-owned ranked rows. Names, slices,
    // commands and packets are not sent; the SQL LIMIT is not a global total.
    let cpu = session
        .diagnostic(
            diagnostic_query("cpu_hotspots").ok_or("E_TRACE_CATALOG")?,
            &cancel,
        )
        .map_err(|e| format!("E_TRACE_QUERY: {e}"))?;
    let mut threads = Vec::new();
    for row in &cpu.rows {
        if row.len() != 4 {
            return Err("E_TRACE_QUERY_SCHEMA".into());
        }
        threads.push(json!({"schedulingSliceCount":numeric::<u64>(row[2].as_ref())?, "scheduledMs":numeric::<u64>(row[3].as_ref())?}));
    }
    let frames = session
        .diagnostic(
            diagnostic_query("frame_jank").ok_or("E_TRACE_CATALOG")?,
            &cancel,
        )
        .map_err(|e| format!("E_TRACE_QUERY: {e}"))?;
    let mut groups = Vec::new();
    for row in &frames.rows {
        if row.len() != 6 {
            return Err("E_TRACE_QUERY_SCHEMA".into());
        }
        groups.push(json!({"frameCount":numeric::<u64>(row[1].as_ref())?,
            "jankyFrames":numeric::<u64>(row[2].as_ref())?,"avgDurationMs":numeric::<f64>(row[3].as_ref())?,
            "maxDurationMs":numeric::<f64>(row[4].as_ref())?,"maxOverrunMs":numeric::<f64>(row[5].as_ref())?}));
    }
    if !SourceDigest::hash_file(path, crate::MAX_INPUT_BYTES).is_ok_and(|d| d.to_hex() == digest) {
        return Err("E_SOURCE_CHANGED: trace changed during analysis".into());
    }
    Ok(json!({"kind":"trace","inputFormat":"perfetto-protobuf",
        "bounds":{"startNs":start,"endNs":end},
        "scheduling":{"query":"cpu_hotspots","rows":threads,"queryLimit":20,"resultTruncated":cpu.truncated,"scope":"Ranked observed thread scheduling groups; SQL truncates milliseconds to integers. Not method elapsed time."},
        "frames":{"query":"frame_jank","rows":groups,"queryLimit":20,"resultTruncated":frames.truncated,"scope":"Observed Android FrameTimeline groups. Empty rows do not establish zero jank."},
        "processor":{"version":version,"sha256":checksum}}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unlabelled_inputs_require_a_marker_at_a_packet_boundary() {
        let path = Path::new("temporary");
        let good = [&[0x0a, MARKER.len() as u8][..], MARKER].concat();
        assert!(looks_like_trace(path, &good));
        assert!(!looks_like_trace(path, &good[1..]));
        assert!(!looks_like_trace(path, &good[..good.len() - 1]));
        assert!(!looks_like_trace(path, b"\x0a\x01\x00"));
        assert!(!has_packet(b"\x0a\xff\xff\xff\xff\x10"));
        assert!(looks_like_trace(Path::new("run.PFTRACE"), b"x"));
    }
}
