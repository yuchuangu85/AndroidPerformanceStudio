use crate::MAX_METADATA_BYTES;
use aps_contracts::*;
use aps_json::{Map, Value};
pub(crate) fn limits() -> aps_json::Limits {
    aps_json::Limits {
        max_bytes: MAX_METADATA_BYTES as u64,
        max_depth: 16,
        max_nodes: 10_000,
        max_string_bytes: 4096,
        max_collection_items: 1000,
    }
}
pub(crate) fn encode(value: &Value) -> Result<String, String> {
    String::from_utf8(
        aps_json::to_vec(value, limits())
            .map_err(|_| "metadata document exceeds bounded JSON policy")?,
    )
    .map_err(|_| "metadata UTF8 failure".into())
}
pub(crate) fn decode(text: &str) -> Result<Value, String> {
    aps_json::parse_slice(text.as_bytes(), limits())
        .map_err(|_| "stored metadata JSON is invalid or exceeds budget".into())
}
fn object<const N: usize>(fields: [(&str, Value); N]) -> Value {
    Value::Object(
        fields
            .into_iter()
            .map(|(key, value)| (key.into(), value))
            .collect::<Map<_, _>>(),
    )
}
fn number(value: u64) -> Value {
    Value::Number(value.into())
}
fn capabilities(value: &Capabilities) -> Value {
    Value::Array(
        value
            .iter()
            .map(|id| Value::String(id.as_str().into()))
            .collect(),
    )
}
pub(crate) fn process(value: &ProcessIdentity) -> Value {
    object([
        ("device", value.device.as_str().into()),
        ("pid", number(value.pid.into())),
        ("processName", value.process_name.clone().into()),
        (
            "packageName",
            value
                .package_name
                .clone()
                .map(Value::String)
                .unwrap_or(Value::Null),
        ),
        (
            "start",
            value
                .start
                .as_ref()
                .map(|start| {
                    object([
                        ("bootId", start.boot_id.clone().into()),
                        ("startTicks", number(start.start_ticks)),
                    ])
                })
                .unwrap_or(Value::Null),
        ),
    ])
}
pub(crate) fn artifact(value: &CaptureArtifact) -> Value {
    let completeness = match &value.completeness {
        ArtifactCompleteness::Unknown { reason } => object([
            ("state", "unknown".into()),
            ("reason", reason.clone().into()),
        ]),
        ArtifactCompleteness::Complete { requested } => object([
            ("state", "complete".into()),
            ("requested", capabilities(requested)),
        ]),
        ArtifactCompleteness::Partial {
            requested,
            retained,
            missing,
        } => object([
            ("state", "partial".into()),
            ("requested", capabilities(requested)),
            ("retained", capabilities(retained)),
            (
                "missing",
                Value::Array(
                    missing
                        .iter()
                        .map(|item| {
                            object([
                                ("capability", item.capability.as_str().into()),
                                ("reason", item.reason.clone().into()),
                            ])
                        })
                        .collect(),
                ),
            ),
        ]),
    };
    let provenance = object([
        ("producer", value.provenance.producer.clone().into()),
        (
            "steps",
            Value::Array(
                value
                    .provenance
                    .steps
                    .iter()
                    .map(|step| {
                        object([
                            (
                                "action",
                                match step.action {
                                    ProvenanceAction::Capture => "capture",
                                    ProvenanceAction::Import => "import",
                                    ProvenanceAction::Convert => "convert",
                                }
                                .into(),
                            ),
                            ("tool", step.tool.clone().into()),
                            (
                                "inputDigest",
                                step.input_digest
                                    .map(|value| Value::String(value.to_hex()))
                                    .unwrap_or(Value::Null),
                            ),
                            ("outputDigest", step.output_digest.to_hex().into()),
                        ])
                    })
                    .collect(),
            ),
        ),
    ]);
    object([
        ("schemaVersion", number(1)),
        ("id", value.id.as_str().into()),
        ("sourceDigest", value.source_digest.to_hex().into()),
        ("byteLength", number(value.byte_length)),
        ("mediaType", value.media_type.clone().into()),
        ("provenance", provenance),
        ("capabilities", capabilities(&value.capabilities)),
        ("completeness", completeness),
        (
            "clock",
            object([
                ("id", value.clock.id.as_str().into()),
                (
                    "kind",
                    match value.clock.kind {
                        ClockKind::Monotonic => "monotonic",
                        ClockKind::BootTime => "bootTime",
                        ClockKind::Realtime => "realtime",
                        ClockKind::Trace => "trace",
                        ClockKind::Unknown => "unknown",
                    }
                    .into(),
                ),
                ("originIdentity", value.clock.origin_identity.clone().into()),
            ]),
        ),
        (
            "device",
            value
                .device
                .as_ref()
                .map(|id| Value::String(id.as_str().into()))
                .unwrap_or(Value::Null),
        ),
        (
            "process",
            value.process.as_ref().map(process).unwrap_or(Value::Null),
        ),
    ])
}
