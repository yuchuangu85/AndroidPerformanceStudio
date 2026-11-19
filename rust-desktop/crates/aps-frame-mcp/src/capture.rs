//! An operator-selected APS producer receipt binds two independent files. No
//! paths are taken from JSON and this reader never launches Trace Processor.
use aps_contracts::SourceDigest;
use aps_frame::{FrameSample, FrameSource};
use aps_json::{Limits, Value};
use std::path::{Path, PathBuf};

const MAX_RECEIPT_BYTES: u64 = 16 * 1024;
// Matches the FrameTimeline recording service limit (not the generic trace viewer).
const MAX_TRACE_BYTES: u64 = 64 * 1024 * 1024;

pub(crate) struct CaptureMeta {
    pub capture_id: String,
    pub report_digest: SourceDigest,
    pub trace_digest: SourceDigest,
    pub device_digest: SourceDigest,
    pub android_api: u32,
    pub package_name: String,
    pub process_name: String,
    pub pid: i32,
    pub refresh_rates_hz: Vec<f64>,
    pub frame_count: usize,
    pub probe_query_complete: bool,
}

pub(crate) struct Capture {
    pub meta: CaptureMeta,
    pub receipt_digest: SourceDigest,
    receipt: PathBuf,
    trace: PathBuf,
}

impl Capture {
    pub fn open(receipt: &Path, trace: &Path) -> Result<Self, String> {
        let bytes = crate::input::read_bounded(receipt, MAX_RECEIPT_BYTES)?;
        let receipt_digest = SourceDigest::hash_reader(bytes.as_slice(), MAX_RECEIPT_BYTES)?;
        let doc = aps_json::parse_slice(
            &bytes,
            Limits {
                max_bytes: MAX_RECEIPT_BYTES,
                max_depth: 3,
                max_nodes: 128,
                max_string_bytes: 512,
                max_collection_items: 64,
            },
        )
        .map_err(|_| "Invalid or unbounded APS frame capture receipt")?;
        let meta = CaptureMeta::parse(&doc).ok_or("Invalid APS frame capture receipt")?;
        Ok(Self {
            meta,
            receipt_digest,
            receipt: receipt.to_owned(),
            trace: trace.to_owned(),
        })
    }

    pub fn verify(&self) -> Result<(), String> {
        crate::input::check_digest(&self.receipt, MAX_RECEIPT_BYTES, self.receipt_digest)?;
        crate::input::check_digest(&self.trace, MAX_TRACE_BYTES, self.meta.trace_digest)
    }
}

impl CaptureMeta {
    fn parse(doc: &Value) -> Option<Self> {
        let fields = doc.as_object()?;
        if fields.len() != 12 || doc["kind"].as_str()? != "aps.frame.capture-receipt.v1" {
            return None;
        }
        let label = |key: &str| {
            doc.get(key)?
                .as_str()
                .filter(|s| {
                    !s.is_empty()
                        && s.len() <= 255
                        && !s.contains(['/', '\\'])
                        && !s.chars().any(char::is_control)
                })
                .map(str::to_owned)
        };
        let digest = |key: &str| {
            let hex = doc.get(key)?.as_str()?;
            if hex.bytes().any(|b| b.is_ascii_uppercase()) {
                return None;
            }
            SourceDigest::from_hex(hex).ok()
        };
        let report_digest = digest("reportDigest")?;
        let trace_digest = digest("traceDigest")?;
        let capture_id = label("captureId")?;
        let prefix = format!("perfetto-{}-", &trace_digest.to_hex()[..32]);
        let context = capture_id.strip_prefix(&prefix)?;
        if report_digest == trace_digest
            || context.len() != 16
            || !context
                .bytes()
                .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
        {
            return None;
        }
        let android_api = u32::try_from(doc["androidApi"].as_u64()?).ok()?;
        let pid = i32::try_from(doc["pid"].as_u64()?).ok()?;
        let frame_count = usize::try_from(doc["frameCount"].as_u64()?).ok()?;
        if android_api < 31 || pid == 0 || !(1..=crate::input::MAX_FRAMES).contains(&frame_count) {
            return None;
        }
        let refresh_rates_hz = doc["refreshRatesHz"]
            .as_array()?
            .iter()
            .map(|rate| rate.as_f64().filter(|hz| (1.0..=1000.0).contains(hz)))
            .collect::<Option<Vec<_>>>()?;
        Some(Self {
            capture_id,
            report_digest,
            trace_digest,
            device_digest: digest("deviceDigest")?,
            android_api,
            package_name: label("packageName")?,
            process_name: label("processName")?,
            pid,
            refresh_rates_hz,
            frame_count,
            probe_query_complete: doc["probeQueryComplete"].as_bool()?,
        })
    }

    pub fn check_samples(&self, frames: &[FrameSample]) -> Result<(), String> {
        if self.frame_count != frames.len()
            || frames.iter().any(|frame| {
                frame.source != FrameSource::Perfetto
                    || frame.process_id != Some(self.pid)
                    || frame.package_name.as_ref() != Some(&self.package_name)
                    || frame.states.get("aps.target.process") != Some(&self.process_name)
            })
        {
            return Err("Frame report rows do not match the pinned capture scope".into());
        }
        Ok(())
    }
}
