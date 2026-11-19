//! Host-only, bounded CPU recording. The stdio MCP does not expose this module.
use aps_adb::{AdbClient, AdbLocator};
use aps_capture::{
    CaptureController, CaptureDuration, CaptureOutput, CapturedArtifact, CpuCaptureRequest,
    EventScope, PlannedCapture, SamplingConfiguration,
};
use aps_contracts::SourceDigest;
use aps_platform::CancellationToken;
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::io::{self, BufRead, IsTerminal, Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

pub struct CpuCapture {
    device: String,
    pid: u32,
    seconds: u64,
    adb: PathBuf,
    converter: PathBuf,
    root: PathBuf,
    pub preview: bool,
}

impl CpuCapture {
    pub fn parse(mut args: impl Iterator<Item = String>) -> Result<(Self, Vec<String>), String> {
        let mut values = BTreeMap::new();
        let mut publication = Vec::new();
        let mut preview = false;
        while let Some(k) = args.next() {
            if k == "--plan" && !preview {
                preview = true;
                continue;
            }
            let host = matches!(
                k.as_str(),
                "--device" | "--pid" | "--seconds" | "--adb" | "--converter" | "--artifact-dir"
            );
            if !host
                && !matches!(
                    k.as_str(),
                    "--format" | "--evidence-out" | "--ai" | "--question" | "--out"
                )
            {
                return Err("E_CAPTURE_OPTION: unknown or duplicate option".into());
            }
            let v = args
                .next()
                .filter(|s| !s.is_empty() && !s.starts_with("--"))
                .ok_or("E_CAPTURE_OPTION: missing value")?;
            if host {
                if values.insert(k, v).is_some() {
                    return Err("E_CAPTURE_OPTION: duplicate field".into());
                }
            } else {
                publication.extend([k, v]);
            }
        }
        if preview && !publication.is_empty() {
            return Err("E_CAPTURE_OPTION: --plan cannot publish evidence or request AI".into());
        }
        let take = |k: &str| {
            values
                .get(k)
                .map(String::as_str)
                .ok_or_else(|| format!("E_CAPTURE_OPTION: missing {k}"))
        };
        let device = take("--device")?.to_string();
        aps_adb::validate_serial(&device).map_err(|_| "E_CAPTURE_DEVICE: invalid serial")?;
        let pid = take("--pid")?
            .parse::<u32>()
            .ok()
            .filter(|n| *n > 0)
            .ok_or("E_CAPTURE_PID: expected positive PID")?;
        let seconds = take("--seconds")?
            .parse::<u64>()
            .ok()
            .filter(|s| (1..=aps_capture::MAX_TIMED_CAPTURE.as_secs()).contains(s))
            .ok_or("E_CAPTURE_SECONDS: expected integer duration from 1 to 300 seconds")?;
        if !cfg!(unix) {
            return Err("E_CAPTURE_UNAVAILABLE: bounded Simpleperf conversion currently requires a Unix host".into());
        }
        let adb = executable(take("--adb")?)?;
        let converter = executable(take("--converter")?)?;
        let root = Path::new(take("--artifact-dir")?)
            .canonicalize()
            .map_err(|_| "E_CAPTURE_DIR: select an existing artifact directory")?;
        if !root.is_dir() {
            return Err("E_CAPTURE_DIR: expected existing directory".into());
        }
        Ok((
            Self {
                device,
                pid,
                seconds,
                adb,
                converter,
                root,
                preview,
            },
            publication,
        ))
    }

    fn plan(&self) -> Result<(PlannedCapture, SourceDigest, Value), String> {
        let adb_hash = SourceDigest::hash_file(&self.adb, 256 * 1024 * 1024)?;
        let location = AdbLocator::new()
            .locate(Some(&self.adb))
            .map_err(|_| "E_CAPTURE_ADB: invalid selected executable")?;
        let capture = CaptureController::new(AdbClient::new(location), self.root.clone())?;
        // Only record the explicitly selected PID, user-space scope and a finite duration.
        let run_id = format!("cpu-{}", aps_ai::platform::new_opaque_id("cpu-capture"));
        let request = CpuCaptureRequest {
            run_id,
            serial: self.device.clone(),
            pid: self.pid,
            duration: CaptureDuration::Timed(Duration::from_secs(self.seconds)),
            sampling: SamplingConfiguration {
                scope: EventScope::User,
                ..Default::default()
            },
            converter: Some(self.converter.clone()),
        };
        let plan = capture
            .plan_cpu(request, &CancellationToken::default())
            .map_err(|e| format!("E_CAPTURE_PLAN: {e}"))?;
        let target = plan.target();
        let details = json!({"schemaVersion":"aps-cpu-capture-plan-v1","executed":false,
            "runId":plan.request().run_id,"device":target.serial,"pid":target.pid,"process":target.process_name,
            "bootId":target.boot_id,"startTicks":target.start_ticks,"seconds":self.seconds,
            "samplingArgs":plan.request().sampling.record_arguments()?,"adb":self.adb,"adbDigest":adb_hash.to_hex(),
            "converter":self.converter,"localDirectory":plan.local_directory(),"remoteDirectory":plan.remote_directory(),
            "validForSeconds":aps_capture::MAX_PLAN_AGE.as_secs(),
            "sideEffects":"Record with simpleperf on this PID; create owned remote files and retain local perf.data and converted profile. Does not send evidence to an AI."});
        Ok((plan, adb_hash, details))
    }

    pub fn preview(self) -> Result<Value, String> {
        Ok(self.plan()?.2)
    }

    pub fn execute(self) -> Result<Value, String> {
        if !io::stdin().is_terminal() || !io::stderr().is_terminal() {
            return Err("E_CAPTURE_APPROVAL_REQUIRED: capture requires interactive stdin/stderr (use --plan to inspect)".into());
        }
        let (plan, adb_hash, details) = self.plan()?;
        let phrase = format!("RECORD {}", plan.request().run_id);
        let mut tty = io::stderr().lock();
        writeln!(
            tty,
            "{}\nType {phrase} to record only this process:",
            serde_json::to_string_pretty(&details).map_err(|_| "E_CAPTURE_PLAN")?
        )
        .and_then(|_| tty.flush())
        .map_err(|_| "E_CAPTURE_REVIEW")?;
        drop(tty);
        let mut answer = String::new();
        io::stdin()
            .lock()
            .take(128)
            .read_line(&mut answer)
            .map_err(|_| "E_CAPTURE_APPROVAL_REQUIRED")?;
        if answer.trim_end_matches(['\n', '\r']) != phrase {
            return Err("E_CAPTURE_NOT_APPROVED".into());
        }
        if SourceDigest::hash_file(&self.adb, 256 * 1024 * 1024)? != adb_hash {
            return Err("E_CAPTURE_ADB_CHANGED: inspect the target again".into());
        }
        let target = plan.target().clone();
        let directory = plan.local_directory();
        let confirmed = plan.confirm(
            &target.serial,
            target.pid,
            &target.boot_id,
            target.start_ticks,
        )?;
        // ConfirmedCapture::run itself re-observes the target and converter;
        // its failures include stop-unverified and are never published as evidence.
        let output = confirmed
            .run()
            .map_err(|e| format!("E_CAPTURE_FAILED: {e}"))?;
        eprintln!("Captured files: {:?}", directory);
        project(&output)
    }
}

fn executable(value: &str) -> Result<PathBuf, String> {
    let p = Path::new(value);
    if !p.is_absolute() {
        return Err("E_CAPTURE_TOOL: expected a trusted absolute executable path".into());
    }
    let canonical = p
        .canonicalize()
        .map_err(|_| "E_CAPTURE_TOOL: missing selected executable")?;
    let meta = canonical
        .metadata()
        .map_err(|_| "E_CAPTURE_TOOL: cannot inspect executable")?;
    if !meta.is_file() {
        return Err("E_CAPTURE_TOOL: expected regular executable".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if meta.permissions().mode() & 0o111 == 0 {
            return Err("E_CAPTURE_TOOL: selected tool is not executable".into());
        }
    }
    Ok(canonical)
}

fn matches_receipt(a: &CapturedArtifact) -> bool {
    (1..=aps_capture::MAX_ARTIFACT).contains(&a.size_bytes)
        && a.path
            .symlink_metadata()
            .is_ok_and(|m| m.is_file() && m.len() == a.size_bytes)
        && SourceDigest::hash_file(&a.path, a.size_bytes).ok() == Some(a.digest)
}

fn project(output: &CaptureOutput) -> Result<Value, String> {
    let pb = output
        .protobuf
        .as_ref()
        .ok_or("E_CAPTURE_CONVERSION: no converted profile")?;
    if !matches_receipt(&output.raw)
        || output.raw.input_digest.is_some()
        || pb.input_digest != Some(output.raw.digest)
        || !matches_receipt(pb)
    {
        return Err("E_CAPTURE_RECEIPT: raw or converted artifact identity mismatch".into());
    }
    let mut evidence = crate::analyze_path(&pb.path, &Default::default())?;
    if evidence["input"]["sourceDigest"] != pb.digest.to_hex()
        || evidence["input"]["byteLength"] != pb.size_bytes
        || evidence["evidence"]["kind"] != "cpu"
        || evidence["evidence"]["inputFormat"] != "simpleperf_protobuf"
    {
        return Err(
            "E_CAPTURE_RECEIPT: parsed artifact does not match the verified conversion".into(),
        );
    }
    let items = evidence["evidence"]["items"]
        .as_array_mut()
        .ok_or("E_CAPTURE_EVIDENCE")?;
    if !items.iter().any(|r| {
        r["id"] == "cpu-summary" && r["value"]["sampleCount"].as_u64().is_some_and(|n| n > 0)
    }) {
        return Err(
            "E_CAPTURE_NO_SAMPLES: captured artifact contained no observed CPU samples".into(),
        );
    }
    items.push(json!({"id":"cpu-capture","label":"CPU capture provenance","value":{
        "rawSha256":output.raw.digest.to_hex(),"rawBytes":output.raw.size_bytes,"profileSha256":pb.digest.to_hex(),
        "profileBytes":pb.size_bytes,"inputDigestVerified":true
    }}));
    Ok(evidence)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_capture::{CaptureCompletion, TargetObservation};
    use std::fs;

    fn source(dir: &Path, name: &str, bytes: &[u8]) -> CapturedArtifact {
        let path = dir.join(name);
        fs::write(&path, bytes).unwrap();
        CapturedArtifact {
            path,
            digest: SourceDigest::hash_reader(bytes, aps_capture::MAX_ARTIFACT).unwrap(),
            size_bytes: bytes.len() as u64,
            provenance: "test",
            input_digest: None,
        }
    }

    #[test]
    fn only_matching_raw_and_decoded_nonempty_sample_source_is_published() {
        let d = tempfile::tempdir().unwrap();
        let raw = source(d.path(), "raw", b"raw");
        let mut pb = b"SIMPLEPERF\x01\x00".to_vec();
        // A framed SIMPLEPERF v1 protobuf with one observed sample.
        pb.extend_from_slice(&[4, 0, 0, 0, 0x0a, 2, 0x10, 7, 0, 0, 0, 0]);
        let mut converted = source(d.path(), "pb", &pb);
        converted.input_digest = Some(raw.digest);
        let mut output = CaptureOutput {
            raw,
            protobuf: Some(converted),
            target: TargetObservation {
                serial: "private-device".into(),
                pid: 7,
                process_name: "local-app".into(),
                boot_id: "local-boot".into(),
                start_ticks: 123,
            },
            retained_remote_path: None,
            completion: CaptureCompletion::RecorderExited,
        };
        let result = project(&output).unwrap();
        assert!(!result.to_string().contains("private-device"));
        assert_eq!(
            result["evidence"]["items"]
                .as_array()
                .unwrap()
                .last()
                .unwrap()["value"]["rawSha256"],
            output.raw.digest.to_hex()
        );
        output.protobuf.as_mut().unwrap().input_digest = None;
        assert!(project(&output).unwrap_err().contains("E_CAPTURE_RECEIPT"));
        let mut empty = source(d.path(), "pb", b"SIMPLEPERF\x01\x00\0\0\0\0");
        empty.input_digest = Some(output.raw.digest);
        output.protobuf = Some(empty);
        assert!(
            project(&output)
                .unwrap_err()
                .contains("E_CAPTURE_NO_SAMPLES")
        );
        fs::write(&output.raw.path, b"bad").unwrap();
        assert!(project(&output).unwrap_err().contains("E_CAPTURE_RECEIPT"));
    }
}
