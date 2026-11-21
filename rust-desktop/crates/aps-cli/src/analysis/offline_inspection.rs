//! Privacy-minimized derived evidence for two native APS import formats.
use aps_battery::BatteryReport;
use aps_layout_inspector::{InspectorArchive, NodeKind};
use aps_platform::CancellationToken;
use serde_json::{Value, json};
use std::path::Path;

pub(crate) fn is_battery(doc: &Value) -> bool {
    doc["schemaVersion"].as_u64() == Some(1)
        && doc.get("sessionId").is_some()
        && doc.get("runs").is_some()
}

pub(crate) fn battery(bytes: &[u8]) -> Result<Value, String> {
    let report = BatteryReport::parse_json(bytes)
        .map_err(|_| "E_BATTERY_INPUT: invalid APS battery report")?;
    let runs = report.runs();
    // Do not combine components, estimates and device/UID scopes. Energy rows
    // are only counted as supplied observations, not summed as consumption.
    let rows: Vec<_> = runs.iter().take(12).map(|r| json!({
        "iteration":r.iteration,"durationMs":r.duration_ms,"networkBytes":r.network_bytes,
        "radioActiveMs":r.radio_active_ms,"peakTemperatureCelsius":r.peak_temperature_celsius,
        "maxThermalStatus":r.max_thermal_status,"reportedEnergyRows":r.energy.len(),
        "reportedTimerRows":r.timers.len(),"warningCount":r.warnings.len()
    })).collect();
    Ok(
        json!({"kind":"battery","inputFormat":"aps-battery-report-v1",
        "summary":{"runCount":runs.len(),"omittedRuns":runs.len()-rows.len(),
            "runsWithNetworkBytes":runs.iter().filter(|r|r.network_bytes.is_some()).count(),
            "runsWithPeakTemperature":runs.iter().filter(|r|r.peak_temperature_celsius.is_some()).count(),
            "maximumReportedPeakCelsius":runs.iter().filter_map(|r|r.peak_temperature_celsius).reduce(f64::max),
            "warningCount":report.warnings.len(), "liveCaptureVerified":false, "runs":rows}}),
    )
}

pub(crate) fn layout(path: &Path, digest: &str) -> Result<Value, String> {
    // ZIP signature is only a candidate. The domain validates the format,
    // required snapshot, protocol, package identity and every declared entry.
    let archive = InspectorArchive::open(path, &CancellationToken::new()).map_err(
        |_| "E_LAYOUT_ARCHIVE: expected a validated APS Layout Inspector capture archive",
    )?;
    if archive.frame.source_digest.to_hex() != digest {
        return Err("E_SOURCE_CHANGED: selected Layout Inspector archive changed".into());
    }
    let mut views = 0usize;
    let mut compose = 0usize;
    let mut max_depth: Option<usize> = None;
    for window in &archive.windows {
        for node in &window.nodes {
            match node.kind {
                NodeKind::View => views += 1,
                NodeKind::Compose => compose += 1,
            }
            max_depth = Some(max_depth.map_or(node.depth, |n| n.max(node.depth)));
        }
    }
    Ok(
        json!({"kind":"layout-inspector","inputFormat":"aps-layout-inspector-archive","summary":{
        "archiveVersion":archive.archive_version,"windowCount":archive.windows.len(),
        "viewNodeCount":views,"composeNodeCount":compose,"maximumNodeDepth":max_depth,
        "composeSidecarNodeCount":archive.compose.as_ref().map(|c| c.nodes.len()),
        "composeSidecarTruncationCount":archive.compose.as_ref().map(|c| c.truncation_count),
        "screenshotPresent":archive.screenshot.is_some()}}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{AnalyzeOptions, analyze_path};
    use aps_contracts::SourceDigest;
    use std::io::Write;
    use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

    #[test]
    fn battery_report_keeps_missing_measurements_and_ignores_source_claims() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("arbitrary.extension");
        let mut doc = json!({"schemaVersion":1, "sessionId":"PRIVATE-SESSION", "liveCapture":true,
            "packageName":"PRIVATE-PACKAGE", "runs":[{"runId":"PRIVATE-RUN","iteration":1,"durationMs":47},
                {"runId":"r2","iteration":2,"durationMs":51,"networkBytes":0,"peakTemperatureCelsius":37.0}]});
        std::fs::write(&src, serde_json::to_vec(&doc).unwrap()).unwrap();
        let value = analyze_path(&src, &AnalyzeOptions::default()).unwrap();
        assert_eq!(value["evidence"]["kind"], "battery");
        let e = &value["evidence"]["items"][0]["value"];
        assert_eq!(e["runCount"], 2);
        assert_eq!(e["liveCaptureVerified"], false);
        assert_eq!(e["runsWithNetworkBytes"], 1);
        assert!(e["runs"][0]["networkBytes"].is_null());
        assert_eq!(e["runs"][1]["networkBytes"], 0);
        assert_eq!(e["maximumReportedPeakCelsius"], 37.0);
        assert!(!value.to_string().contains("PRIVATE-"));

        doc["benchmarks"] = json!([]);
        std::fs::write(&src, serde_json::to_vec(&doc).unwrap()).unwrap();
        assert!(
            analyze_path(&src, &AnalyzeOptions::default())
                .unwrap_err()
                .contains("ambiguous")
        );
        doc.as_object_mut().unwrap().remove("benchmarks");
        doc["runs"] = json!([]);
        std::fs::write(&src, serde_json::to_vec(&doc).unwrap()).unwrap();
        assert!(
            analyze_path(&src, &AnalyzeOptions::default())
                .unwrap_err()
                .contains("E_BATTERY_INPUT")
        );
    }

    #[test]
    fn inspector_summary_is_source_bound_and_never_returns_node_content() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("arbitrary.data");
        let bounds = json!({"left":0,"top":0,"right":160,"bottom":240});
        let root = json!({"id":"PRIVATE-NODE","type":"view","className":"PRIVATE-CLASS", "bounds":bounds,
            "text":"PRIVATE-CONTENT","children":[{"id":"child","type":"compose","className":"PRIVATE-CHILD","bounds":bounds,"children":[]}]});
        let snapshot=serde_json::to_vec(&json!({"protocolVersion":{"major":1,"minor":0},"packageName":"PRIVATE-PKG",
            "capturedAtEpochMillis":42,"display":{"widthPx":160,"heightPx":240,"density":1.0},
            "capabilities":{"viewHierarchy":true,"composeSemantics":false,"screenshots":false,"timeline":false},"root":root})).unwrap();
        let digest = SourceDigest::hash_reader(&snapshot[..], snapshot.len() as u64)
            .unwrap()
            .to_hex();
        let manifest = json!({"format":"agentperf-inspector-capture","archiveVersion":1,"producerVersion":"test",
            "packageName":"PRIVATE-PKG","capturedAtEpochMillis":42,"protocolMajor":1,"protocolMinor":0,
            "entries":[{"path":"capture/layout-snapshot.json","size":snapshot.len(),"sha256":digest,"required":true}]});
        let zip = |include_manifest: bool| {
            let mut z = ZipWriter::new(std::fs::File::create(&path).unwrap());
            let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
            if include_manifest {
                z.start_file("manifest.json", opts).unwrap();
                z.write_all(manifest.to_string().as_bytes()).unwrap();
            }
            z.start_file("capture/layout-snapshot.json", opts).unwrap();
            z.write_all(&snapshot).unwrap();
            z.finish().unwrap();
        };
        zip(true);
        let value = analyze_path(&path, &AnalyzeOptions::default()).unwrap();
        assert_eq!(value["evidence"]["kind"], "layout-inspector");
        let e = &value["evidence"]["items"][0]["value"];
        assert_eq!(e["viewNodeCount"], 1);
        assert_eq!(e["composeNodeCount"], 1);
        assert_eq!(e["maximumNodeDepth"], 1);
        assert_eq!(e["windowCount"], 1);
        assert!(e["composeSidecarNodeCount"].is_null());
        assert!(!value.to_string().contains("PRIVATE-"));
        assert!(
            layout(&path, &"0".repeat(64))
                .unwrap_err()
                .contains("E_SOURCE_CHANGED")
        );

        zip(false);
        assert!(
            analyze_path(&path, &AnalyzeOptions::default())
                .unwrap_err()
                .contains("E_LAYOUT_ARCHIVE")
        );
    }
}
