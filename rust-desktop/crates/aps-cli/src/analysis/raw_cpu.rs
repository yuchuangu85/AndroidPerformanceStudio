//! Explicitly opted-in offline conversion. No converter path is inferred from
//! the profile, query, PATH, GUI preferences or device.
#[cfg(not(unix))]
pub fn analyze(_: &[u8], _: &str) -> Result<serde_json::Value, String> {
    Err("E_RAW_CPU_HOST: bounded offline simpleperf conversion requires Unix".into())
}

#[cfg(unix)]
pub fn analyze(raw: &[u8], raw_sha: &str) -> Result<serde_json::Value, String> {
    use aps_contracts::SourceDigest;
    use std::{io::Write, os::unix::fs::PermissionsExt, path::PathBuf};
    let path = std::env::var_os("APS_SIMPLEPERF").map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .ok_or("E_RAW_CPU_TOOL: explicitly configure the trusted absolute APS_SIMPLEPERF path or supply converted SIMPLEPERF protobuf")?;
    let tool = path
        .canonicalize()
        .map_err(|_| "E_RAW_CPU_TOOL: selected host converter is unavailable")?;
    if !tool
        .metadata()
        .is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    {
        return Err("E_RAW_CPU_TOOL: host converter must be an executable regular file".into());
    }
    aps_capture::validate_offline_perf_data(raw)
        .map_err(|_| "E_RAW_CPU_INPUT: malformed or unsupported perf.data container")?;
    let scratch = tempfile::Builder::new()
        .permissions(std::fs::Permissions::from_mode(0o700))
        .prefix("aps-cpu-import-")
        .tempdir()
        .map_err(|_| "E_RAW_CPU_SNAPSHOT")?;
    let mut snapshot =
        tempfile::NamedTempFile::new_in(scratch.path()).map_err(|_| "E_RAW_CPU_SNAPSHOT")?;
    snapshot
        .write_all(raw)
        .and_then(|_| snapshot.flush())
        .map_err(|_| "E_RAW_CPU_SNAPSHOT")?;
    let verify_snapshot = || {
        SourceDigest::hash_file(snapshot.path(), raw.len() as u64)
            .is_ok_and(|current| current.to_hex() == raw_sha)
    };
    if !verify_snapshot() {
        return Err("E_RAW_CPU_SNAPSHOT".into());
    }
    let tool_sha =
        SourceDigest::hash_file(&tool, crate::MAX_INPUT_BYTES).map_err(|_| "E_RAW_CPU_TOOL")?;
    let protobuf = aps_capture::convert_offline_perf_data(&tool,snapshot.path(),&aps_platform::CancellationToken::new())
        .map_err(|_| "E_RAW_CPU_CONVERSION: selected converter failed or produced unsupported/over-budget output")?;
    if !SourceDigest::hash_file(&tool, crate::MAX_INPUT_BYTES).is_ok_and(|d| d == tool_sha)
        || !verify_snapshot()
    {
        return Err("E_RAW_CPU_CHANGED: converter or input snapshot changed".into());
    }
    let pb_sha = SourceDigest::hash_reader(&protobuf[..], aps_capture::MAX_ARTIFACT)?.to_hex();
    let mut projection = super::cpu_evidence(&protobuf, "perf-data-converted")?;
    projection["conversion"] = serde_json::json!({
        "rawSha256":raw_sha,"protobufSha256":pb_sha,"converterSha256":tool_sha.to_hex(),
        "rawBytes":raw.len(),"protobufBytes":protobuf.len(),"conversion":"host-simpleperf-report-sample-v1",
        "samplingEvidence":if projection["summary"]["sampleCount"].as_u64().unwrap_or(0)>0 {"observed-samples"} else {"no-observed-samples"}
    });
    Ok(projection)
}
