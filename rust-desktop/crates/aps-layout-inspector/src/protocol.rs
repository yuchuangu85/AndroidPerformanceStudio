use crate::{
    Bounds, Capabilities, DisplayInfo, FrameIdentity, InspectorArchive, LayoutNode, LayoutWindow,
    NodeKind,
};
use aps_contracts::{ApsError, ErrorCode, ProfilerSessionId, SourceDigest};
use aps_platform::CancellationToken;
use aps_protocol::{FrameLimits, Request, Response, Value};
use std::collections::BTreeMap;

const OP_OPEN: &str = "inspector.frame.open";
const MAX_WINDOWS: usize = 64;
const MAX_NODES: usize = 100_000;
const MAX_PAGE: usize = 200;

/// Host-side bounded Inspector frame accepted from an Android Agent transport.
/// The transport owns framing and identity; this type owns the sanitized layout
/// projection and deliberately drops runtime values, semantics and raw payloads.
#[derive(Clone, Debug)]
pub struct InspectorProtocolFrame {
    pub archive: InspectorArchive,
    pub generation: u64,
}

pub struct InspectorProtocolSession {
    session_id: ProfilerSessionId,
    source_digest: SourceDigest,
    generation: u64,
    frame: Option<InspectorProtocolFrame>,
}

impl InspectorProtocolSession {
    pub fn new(session_id: ProfilerSessionId, source_digest: SourceDigest) -> Self {
        Self {
            session_id,
            source_digest,
            generation: 0,
            frame: None,
        }
    }

    pub fn frame(&self) -> Option<&InspectorProtocolFrame> {
        self.frame.as_ref()
    }

    pub fn handle(
        &mut self,
        request: Request,
        cancel: &CancellationToken,
    ) -> Result<Response, String> {
        let limits = FrameLimits {
            max_frame_bytes: 4 * 1024 * 1024,
            max_string_bytes: 64 * 1024,
            max_values: 100_000,
            max_depth: 32,
        };
        request.validate(limits)?;
        if cancel.is_cancelled() {
            return Response::cancelled(&request);
        }
        if request.session_id != self.session_id || request.source_digest != self.source_digest {
            return Response::error(
                &request,
                ApsError::new(
                    ErrorCode::IdentityMismatch,
                    "Inspector session identity mismatch",
                    false,
                )?,
            );
        }
        if request.operation != OP_OPEN {
            return Response::error(
                &request,
                ApsError::new(
                    ErrorCode::Unsupported,
                    "Unsupported Inspector host operation",
                    false,
                )?,
            );
        }
        if request.generation <= self.generation {
            return Response::error(
                &request,
                ApsError::new(
                    ErrorCode::StaleGeneration,
                    "Inspector frame generation is stale",
                    false,
                )?,
            );
        }
        let archive = parse_frame(&request.args, self.source_digest, cancel)?;
        let default_window_id = archive.default_window_id.clone();
        let page = archive
            .hierarchy(&default_window_id, 0, MAX_PAGE)
            .map_err(|error| error.to_string())?
            .to_vec();
        self.generation = request.generation;
        self.frame = Some(InspectorProtocolFrame {
            archive,
            generation: self.generation,
        });
        Response::ok(
            &request,
            Value::map([
                ("archiveVersion", 1i64.into()),
                ("defaultWindowId", default_window_id.into()),
                (
                    "generation",
                    i64::try_from(self.generation)
                        .map_err(|_| "generation exceeds wire range")?
                        .into(),
                ),
                (
                    "hierarchyPage",
                    Value::List(
                        page.into_iter()
                            .map(|node| {
                                Value::map([
                                    ("id", node.id.clone().into()),
                                    ("className", node.class_name.clone().into()),
                                    ("depth", (node.depth as i64).into()),
                                    ("visible", node.visible.into()),
                                ])
                            })
                            .collect(),
                    ),
                ),
            ]),
        )
    }
}

fn parse_frame(
    value: &Value,
    source_digest: SourceDigest,
    cancel: &CancellationToken,
) -> Result<InspectorArchive, String> {
    let map = exact(
        value,
        &[
            "packageName",
            "capturedAtEpochMillis",
            "protocolMajor",
            "protocolMinor",
            "windows",
        ],
    )?;
    let package_name = text(map, "packageName", 512)?;
    let captured_at_epoch_millis = integer(map, "capturedAtEpochMillis")?;
    let protocol_major = positive_u32(map, "protocolMajor")?;
    let protocol_minor = u32_value(map, "protocolMinor")?;
    if protocol_major != 1 {
        return Err("unsupported Inspector protocol major version".into());
    }
    let values = field(map, "windows")?.as_list()?;
    if values.is_empty() || values.len() > MAX_WINDOWS {
        return Err("Inspector frame window count is outside bounds".into());
    }
    let mut windows = Vec::with_capacity(values.len());
    let mut node_count = 0usize;
    for value in values {
        if cancel.is_cancelled() {
            return Err("Inspector frame request cancelled".into());
        }
        let window = parse_window(value, &mut node_count)?;
        if node_count > MAX_NODES {
            return Err("Inspector frame node count exceeds bound".into());
        }
        windows.push(window);
    }
    let default_window_id = windows[0].id.clone();
    let frame = FrameIdentity {
        source_digest,
        package_name,
        captured_at_epoch_millis,
        protocol_major,
        protocol_minor,
    };
    Ok(InspectorArchive {
        archive_version: 1,
        frame,
        display: DisplayInfo {
            width_px: windows[0].bounds.right.max(1) as u32,
            height_px: windows[0].bounds.bottom.max(1) as u32,
            density: 1.0,
        },
        capabilities: Capabilities {
            view_hierarchy: true,
            compose_semantics: windows.iter().any(|window| {
                window
                    .nodes
                    .iter()
                    .any(|node| node.kind == NodeKind::Compose)
            }),
            screenshots: false,
            timeline: false,
        },
        windows,
        default_window_id,
        screenshot: None,
        screenshot_png: None,
        compose: None,
    })
}

fn parse_window(value: &Value, node_count: &mut usize) -> Result<LayoutWindow, String> {
    let map = exact(value, &["id", "kind", "bounds", "nodes"])?;
    let id = text(map, "id", 256)?;
    let kind = text(map, "kind", 256)?;
    let bounds = parse_bounds(field(map, "bounds")?)?;
    let values = field(map, "nodes")?.as_list()?;
    if values.len() > MAX_NODES.saturating_sub(*node_count) {
        return Err("Inspector window node count exceeds bound".into());
    }
    let mut nodes = Vec::with_capacity(values.len());
    for value in values {
        nodes.push(parse_node(value)?);
    }
    *node_count += nodes.len();
    validate_ancestry(&nodes)?;
    Ok(LayoutWindow {
        id,
        kind,
        bounds,
        nodes,
    })
}

fn parse_node(value: &Value) -> Result<LayoutNode, String> {
    let map = exact(
        value,
        &[
            "id",
            "kind",
            "className",
            "parentIndex",
            "depth",
            "bounds",
            "visible",
            "alphaPermille",
            "resourceName",
        ],
    )?;
    let kind = match text(map, "kind", 32)?.as_str() {
        "view" => NodeKind::View,
        "compose" => NodeKind::Compose,
        _ => return Err("unsupported Inspector node kind".into()),
    };
    let alpha = (u32_value(map, "alphaPermille")? as f64 / 1000.0).clamp(0.0, 1.0);
    Ok(LayoutNode {
        id: text(map, "id", 256)?,
        kind,
        class_name: text(map, "className", 512)?,
        parent_index: nullable_u32(map, "parentIndex")?.map(|value| value as usize),
        depth: u32_value(map, "depth")? as usize,
        bounds: parse_bounds(field(map, "bounds")?)?,
        visible: field(map, "visible")?.as_bool()?,
        alpha,
        resource_name: field(map, "resourceName")?
            .nullable_str()?
            .map(str::to_owned),
        text: None,
        semantics_role: None,
        attributes: Default::default(),
    })
}

fn validate_ancestry(nodes: &[LayoutNode]) -> Result<(), String> {
    for (index, node) in nodes.iter().enumerate() {
        if node.parent_index.is_some_and(|parent| parent >= index) {
            return Err("Inspector node parent must precede child".into());
        }
        if node.parent_index.is_none() && node.depth != 0 {
            return Err("Inspector root node depth must be zero".into());
        }
        if node
            .parent_index
            .is_some_and(|parent| nodes[parent].depth + 1 != node.depth)
        {
            return Err("Inspector node depth does not match parent".into());
        }
    }
    Ok(())
}

fn parse_bounds(value: &Value) -> Result<Bounds, String> {
    let map = exact(value, &["left", "top", "right", "bottom"])?;
    let bounds = Bounds {
        left: integer(map, "left")? as i32,
        top: integer(map, "top")? as i32,
        right: integer(map, "right")? as i32,
        bottom: integer(map, "bottom")? as i32,
    };
    if bounds.right < bounds.left || bounds.bottom < bounds.top {
        return Err("Inspector bounds are inverted".into());
    }
    Ok(bounds)
}

fn exact<'a>(value: &'a Value, fields: &[&str]) -> Result<&'a BTreeMap<String, Value>, String> {
    let map = value.as_map()?;
    if map.len() != fields.len() || map.keys().any(|key| !fields.contains(&key.as_str())) {
        return Err("Inspector protocol fields do not match exact schema".into());
    }
    Ok(map)
}
fn field<'a>(map: &'a BTreeMap<String, Value>, name: &str) -> Result<&'a Value, String> {
    map.get(name)
        .ok_or_else(|| format!("missing Inspector field {name}"))
}
fn text(map: &BTreeMap<String, Value>, name: &str, limit: usize) -> Result<String, String> {
    let value = field(map, name)?.as_str()?;
    if value.is_empty() || value.len() > limit || value.chars().any(char::is_control) {
        return Err(format!("Inspector field {name} exceeds text bounds"));
    }
    Ok(value.to_owned())
}
fn integer(map: &BTreeMap<String, Value>, name: &str) -> Result<i64, String> {
    field(map, name)?.as_int()
}
fn u32_value(map: &BTreeMap<String, Value>, name: &str) -> Result<u32, String> {
    u32::try_from(integer(map, name)?)
        .map_err(|_| format!("Inspector field {name} is out of range"))
}
fn positive_u32(map: &BTreeMap<String, Value>, name: &str) -> Result<u32, String> {
    let value = u32_value(map, name)?;
    if value == 0 {
        return Err(format!("Inspector field {name} must be positive"));
    }
    Ok(value)
}
fn nullable_u32(map: &BTreeMap<String, Value>, name: &str) -> Result<Option<u32>, String> {
    match field(map, name)? {
        Value::Null => Ok(None),
        _ => u32_value(map, name).map(Some),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_contracts::RequestId;
    use aps_protocol::{Request, ResponseStatus};

    fn frame_args() -> Value {
        Value::map([
            ("packageName", "example.test".into()),
            ("capturedAtEpochMillis", 42i64.into()),
            ("protocolMajor", 1u32.into()),
            ("protocolMinor", 0u32.into()),
            (
                "windows",
                Value::List(vec![Value::map([
                    ("id", "window:main".into()),
                    ("kind", "activity".into()),
                    (
                        "bounds",
                        Value::map([
                            ("left", 0i64.into()),
                            ("top", 0i64.into()),
                            ("right", 100i64.into()),
                            ("bottom", 100i64.into()),
                        ]),
                    ),
                    (
                        "nodes",
                        Value::List(vec![Value::map([
                            ("id", "root".into()),
                            ("kind", "view".into()),
                            ("className", "android.view.View".into()),
                            ("parentIndex", Value::Null),
                            ("depth", 0u32.into()),
                            (
                                "bounds",
                                Value::map([
                                    ("left", 0i64.into()),
                                    ("top", 0i64.into()),
                                    ("right", 100i64.into()),
                                    ("bottom", 100i64.into()),
                                ]),
                            ),
                            ("visible", true.into()),
                            ("alphaPermille", 1000u32.into()),
                            ("resourceName", Value::Null),
                        ])]),
                    ),
                ])]),
            ),
        ])
    }

    fn request(session: &ProfilerSessionId, digest: SourceDigest, generation: u64) -> Request {
        Request::new(
            RequestId::new(format!("request-{generation}")).unwrap(),
            digest,
            session.clone(),
            generation,
            OP_OPEN,
            frame_args(),
        )
        .unwrap()
    }

    #[test]
    fn accepts_bounded_frame_and_returns_first_hierarchy_page() {
        let digest = SourceDigest::from_bytes([7; 32]);
        let session_id = ProfilerSessionId::new("inspector-session").unwrap();
        let mut session = InspectorProtocolSession::new(session_id.clone(), digest);
        let response = session
            .handle(request(&session_id, digest, 1), &CancellationToken::new())
            .unwrap();
        assert_eq!(response.status, ResponseStatus::Ok);
        assert_eq!(session.frame().unwrap().archive.windows.len(), 1);
        assert_eq!(session.frame().unwrap().archive.windows[0].nodes.len(), 1);
    }

    #[test]
    fn rejects_stale_generation_and_cancellation() {
        let digest = SourceDigest::from_bytes([8; 32]);
        let session_id = ProfilerSessionId::new("inspector-session").unwrap();
        let mut session = InspectorProtocolSession::new(session_id.clone(), digest);
        session
            .handle(request(&session_id, digest, 1), &CancellationToken::new())
            .unwrap();
        let stale = session
            .handle(request(&session_id, digest, 1), &CancellationToken::new())
            .unwrap();
        assert_eq!(stale.status, ResponseStatus::Error);
        let cancel = CancellationToken::new();
        cancel.cancel();
        let cancelled = session
            .handle(request(&session_id, digest, 2), &cancel)
            .unwrap();
        assert_eq!(cancelled.status, ResponseStatus::Cancelled);
    }
}
