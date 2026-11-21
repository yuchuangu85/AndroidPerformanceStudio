use crate::{
    ArtifactKind, GpuArtifact, GpuArtifactIndex, GpuDeviceContext, GraphicsApi, IndexedArtifact,
    OpenRoute,
};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Value};
use aps_platform::atomic_write;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const STORE_VERSION: i64 = 2;
/// 1 is still read so an index written before capture metadata existed does not
/// become an unreadable catalog.
const LEGACY_STORE_VERSION: i64 = 1;
const MAX_STORE_BYTES: u64 = 16 * 1024 * 1024;

fn limits() -> Limits {
    Limits {
        max_bytes: MAX_STORE_BYTES,
        ..Limits::default()
    }
}

#[derive(Debug)]
pub enum GpuStoreError {
    Io,
    InvalidDocument,
    InvalidArtifact,
    Json,
}

impl std::fmt::Display for GpuStoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Io => "GPU artifact index cannot be read or written",
            Self::InvalidDocument => "GPU artifact index document is invalid",
            Self::InvalidArtifact => "GPU artifact index contains an invalid artifact",
            Self::Json => "GPU artifact index exceeds its JSON budget",
        })
    }
}
impl std::error::Error for GpuStoreError {}

#[derive(Clone)]
pub struct GpuArtifactStore {
    path: PathBuf,
}

impl GpuArtifactStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn load(&self) -> Result<GpuArtifactIndex, GpuStoreError> {
        let file = match File::open(&self.path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(GpuArtifactIndex::new());
            }
            Err(_) => return Err(GpuStoreError::Io),
        };
        let metadata = file.metadata().map_err(|_| GpuStoreError::Io)?;
        if !metadata.is_file() {
            return Err(GpuStoreError::Io);
        }
        if metadata.len() > MAX_STORE_BYTES {
            return Err(GpuStoreError::Json);
        }
        let mut bytes = Vec::new();
        file.take(MAX_STORE_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| GpuStoreError::Io)?;
        let value = aps_json::parse_slice(&bytes, limits()).map_err(|_| GpuStoreError::Json)?;
        let map = value.as_object().ok_or(GpuStoreError::InvalidDocument)?;
        let version = map
            .get("version")
            .and_then(|value| value.as_i64())
            .ok_or(GpuStoreError::InvalidDocument)?;
        if version != STORE_VERSION && version != LEGACY_STORE_VERSION {
            return Err(GpuStoreError::InvalidDocument);
        }
        let list = map
            .get("artifacts")
            .and_then(|value| value.as_array())
            .ok_or(GpuStoreError::InvalidDocument)?;
        if list.len() > crate::MAX_ARTIFACTS {
            return Err(GpuStoreError::InvalidArtifact);
        }
        let mut artifacts = Vec::with_capacity(list.len());
        for item in list {
            artifacts.push(decode_artifact(item, version)?);
        }
        GpuArtifactIndex::from_artifacts(artifacts).map_err(|_| GpuStoreError::InvalidArtifact)
    }

    pub fn save(&self, index: &GpuArtifactIndex) -> Result<(), GpuStoreError> {
        let mut map = Map::new();
        map.insert("version".into(), STORE_VERSION.into());
        map.insert(
            "artifacts".into(),
            Value::Array(
                index
                    .artifacts()
                    .iter()
                    .map(encode_artifact)
                    .collect::<Result<_, _>>()?,
            ),
        );
        let value = Value::Object(map);
        let bytes = aps_json::to_vec(&value, limits()).map_err(|_| GpuStoreError::Json)?;
        atomic_write(&self.path, &bytes).map_err(|_| GpuStoreError::Io)
    }
}

fn encode_artifact(artifact: &GpuArtifact) -> Result<Value, GpuStoreError> {
    let mut map = Map::new();
    map.insert("kind".into(), kind_name(artifact.kind).into());
    map.insert("sha256".into(), artifact.sha256.to_hex().into());
    map.insert("sizeBytes".into(), (artifact.size_bytes as i64).into());
    map.insert(
        "modifiedAtMs".into(),
        millis(artifact.modified_at).map_or(Value::Null, |value| value.into()),
    );
    map.insert("openRoute".into(), route_name(artifact.open_route).into());
    if let Some(value) = &artifact.agi_version {
        map.insert("agiVersion".into(), value.as_str().into());
    }
    if let Some(device) = &artifact.device {
        map.insert("device".into(), encode_device(device));
    }
    if let Some(value) = &artifact.package_name {
        map.insert("packageName".into(), value.as_str().into());
    }
    if let Some(value) = artifact.graphics_api {
        map.insert("graphicsApi".into(), graphics_api_name(value).into());
    }
    if let Some(value) = &artifact.graphics_implementation {
        map.insert("graphicsImplementation".into(), value.as_str().into());
    }
    if let Some(value) = millis(artifact.captured_at) {
        map.insert("capturedAtMs".into(), value.into());
    }
    if let Some(value) = millis(artifact.imported_at) {
        map.insert("importedAtMs".into(), value.into());
    }
    if let Some(value) = &artifact.notes {
        map.insert("notes".into(), value.as_str().into());
    }
    map.insert(
        "warnings".into(),
        Value::Array(
            artifact
                .warnings
                .iter()
                .map(|value| value.as_str().into())
                .collect(),
        ),
    );
    map.insert(
        "locations".into(),
        Value::Array(
            artifact
                .locations()
                .iter()
                .map(|path| {
                    path.to_str()
                        .map(Value::from)
                        .ok_or(GpuStoreError::InvalidArtifact)
                })
                .collect::<Result<_, _>>()?,
        ),
    );
    Ok(Value::Object(map))
}

fn decode_artifact(value: &Value, version: i64) -> Result<GpuArtifact, GpuStoreError> {
    let map = value.as_object().ok_or(GpuStoreError::InvalidArtifact)?;
    let kind = parse_kind(
        map.get("kind")
            .and_then(|value| value.as_str())
            .ok_or(GpuStoreError::InvalidArtifact)?,
    )?;
    let digest = SourceDigest::from_hex(
        map.get("sha256")
            .and_then(|value| value.as_str())
            .ok_or(GpuStoreError::InvalidArtifact)?,
    )
    .map_err(|_| GpuStoreError::InvalidArtifact)?;
    let size = map
        .get("sizeBytes")
        .and_then(|value| value.as_i64())
        .and_then(|value| u64::try_from(value).ok())
        .ok_or(GpuStoreError::InvalidArtifact)?;
    let modified_at = timestamp(map, "modifiedAtMs");
    let route = parse_route(
        map.get("openRoute")
            .and_then(|value| value.as_str())
            .ok_or(GpuStoreError::InvalidArtifact)?,
    )?;
    let locations = map
        .get("locations")
        .and_then(|value| value.as_array())
        .ok_or(GpuStoreError::InvalidArtifact)?
        .iter()
        .map(|value| {
            value
                .as_str()
                .map(PathBuf::from)
                .ok_or(GpuStoreError::InvalidArtifact)
        })
        .collect::<Result<Vec<_>, _>>()?;
    let (agi_version, device, package_name, graphics_api, graphics_implementation, captured_at) =
        if version >= STORE_VERSION {
            (
                text(map, "agiVersion")?,
                map.get("device").map(decode_device).transpose()?.flatten(),
                text(map, "packageName")?,
                map.get("graphicsApi")
                    .and_then(|value| value.as_str())
                    .map(parse_graphics_api)
                    .transpose()?,
                text(map, "graphicsImplementation")?,
                timestamp(map, "capturedAtMs"),
            )
        } else {
            (None, None, None, None, None, None)
        };
    let warnings = map
        .get("warnings")
        .and_then(|value| value.as_array())
        .map(|values| {
            values
                .iter()
                .map(|value| {
                    value
                        .as_str()
                        .map(str::to_owned)
                        .ok_or(GpuStoreError::InvalidArtifact)
                })
                .collect::<Result<Vec<_>, _>>()
        })
        .transpose()?
        .unwrap_or_default();
    GpuArtifact::from_indexed(IndexedArtifact {
        kind,
        sha256: digest,
        size_bytes: size,
        modified_at,
        open_route: route,
        agi_version,
        device,
        package_name,
        graphics_api,
        graphics_implementation,
        captured_at,
        imported_at: timestamp(map, "importedAtMs"),
        notes: text(map, "notes")?,
        warnings,
        locations,
    })
    .map_err(|_| GpuStoreError::InvalidArtifact)
}

fn encode_device(device: &GpuDeviceContext) -> Value {
    let mut map = Map::new();
    map.insert("serial".into(), device.serial.as_str().into());
    if let Some(value) = &device.model {
        map.insert("model".into(), value.as_str().into());
    }
    if let Some(value) = &device.android_release {
        map.insert("androidRelease".into(), value.as_str().into());
    }
    if let Some(value) = device.sdk_level {
        map.insert("sdkLevel".into(), (value as i64).into());
    }
    if let Some(value) = &device.gpu_vendor {
        map.insert("gpuVendor".into(), value.as_str().into());
    }
    if let Some(value) = &device.gpu_renderer {
        map.insert("gpuRenderer".into(), value.as_str().into());
    }
    if let Some(value) = &device.driver {
        map.insert("driver".into(), value.as_str().into());
    }
    if let Some(value) = &device.open_gl_es_version {
        map.insert("openGlEsVersion".into(), value.as_str().into());
    }
    if let Some(value) = &device.vulkan_version {
        map.insert("vulkanVersion".into(), value.as_str().into());
    }
    map.insert(
        "graphicsApis".into(),
        Value::Array(
            device
                .graphics_apis
                .iter()
                .map(|api| graphics_api_name(*api).into())
                .collect(),
        ),
    );
    if let Some(value) = &device.graphics_implementation {
        map.insert("graphicsImplementation".into(), value.as_str().into());
    }
    map.insert(
        "evidence".into(),
        Value::Array(
            device
                .evidence
                .iter()
                .map(|value| value.as_str().into())
                .collect(),
        ),
    );
    Value::Object(map)
}

fn decode_device(value: &Value) -> Result<Option<GpuDeviceContext>, GpuStoreError> {
    let map = value.as_object().ok_or(GpuStoreError::InvalidArtifact)?;
    let serial = map
        .get("serial")
        .and_then(|value| value.as_str())
        .ok_or(GpuStoreError::InvalidArtifact)?;
    if serial.trim().is_empty() {
        return Err(GpuStoreError::InvalidArtifact);
    }
    let mut graphics_apis = Vec::new();
    for item in map
        .get("graphicsApis")
        .and_then(|value| value.as_array())
        .map(Vec::as_slice)
        .unwrap_or_default()
    {
        let api = parse_graphics_api(item.as_str().ok_or(GpuStoreError::InvalidArtifact)?)?;
        if !graphics_apis.contains(&api) {
            graphics_apis.push(api);
        }
    }
    if graphics_apis.len() > 3 {
        return Err(GpuStoreError::InvalidArtifact);
    }
    let evidence = map
        .get("evidence")
        .and_then(|value| value.as_array())
        .map(|values| {
            values
                .iter()
                .map(|value| {
                    value
                        .as_str()
                        .map(str::to_owned)
                        .ok_or(GpuStoreError::InvalidArtifact)
                })
                .collect::<Result<Vec<_>, _>>()
        })
        .transpose()?
        .unwrap_or_default();
    if evidence.len() > crate::MAX_WARNINGS {
        return Err(GpuStoreError::InvalidArtifact);
    }
    Ok(Some(GpuDeviceContext {
        serial: serial.to_owned(),
        model: text(map, "model")?,
        android_release: text(map, "androidRelease")?,
        sdk_level: map
            .get("sdkLevel")
            .and_then(|value| value.as_i64())
            .and_then(|value| u32::try_from(value).ok()),
        gpu_vendor: text(map, "gpuVendor")?,
        gpu_renderer: text(map, "gpuRenderer")?,
        driver: text(map, "driver")?,
        open_gl_es_version: text(map, "openGlEsVersion")?,
        vulkan_version: text(map, "vulkanVersion")?,
        graphics_apis,
        graphics_implementation: text(map, "graphicsImplementation")?,
        evidence,
    }))
}

fn text(map: &Map<String, Value>, key: &str) -> Result<Option<String>, GpuStoreError> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_str()
            .map(|text| (!text.trim().is_empty()).then(|| text.to_owned()))
            .ok_or(GpuStoreError::InvalidArtifact),
    }
}

fn timestamp(map: &Map<String, Value>, key: &str) -> Option<SystemTime> {
    map.get(key)
        .and_then(|value| value.as_i64())
        .and_then(|value| u64::try_from(value).ok())
        .map(|value| UNIX_EPOCH + Duration::from_millis(value))
}

fn millis(value: Option<SystemTime>) -> Option<i64> {
    value?
        .duration_since(UNIX_EPOCH)
        .ok()?
        .as_millis()
        .try_into()
        .ok()
}
fn kind_name(value: ArtifactKind) -> &'static str {
    match value {
        ArtifactKind::AgiSystemProfile => "agiSystemProfile",
        ArtifactKind::AgiFrameProfile => "agiFrameProfile",
        ArtifactKind::PerfettoTrace => "perfettoTrace",
        ArtifactKind::Screenshot => "screenshot",
        ArtifactKind::ExternalReport => "externalReport",
        ArtifactKind::Unknown => "unknown",
    }
}
fn route_name(value: OpenRoute) -> &'static str {
    match value {
        OpenRoute::Agi => "agi",
        OpenRoute::Perfetto => "perfetto",
        OpenRoute::Desktop => "desktop",
        OpenRoute::None => "none",
    }
}
fn graphics_api_name(value: GraphicsApi) -> &'static str {
    match value {
        GraphicsApi::Vulkan => "vulkan",
        GraphicsApi::OpenGlEs => "openglEs",
        GraphicsApi::WebGpu => "webGpu",
    }
}
fn parse_graphics_api(value: &str) -> Result<GraphicsApi, GpuStoreError> {
    match value {
        "vulkan" => Ok(GraphicsApi::Vulkan),
        "openglEs" => Ok(GraphicsApi::OpenGlEs),
        "webGpu" => Ok(GraphicsApi::WebGpu),
        _ => Err(GpuStoreError::InvalidArtifact),
    }
}
fn parse_kind(value: &str) -> Result<ArtifactKind, GpuStoreError> {
    match value {
        "agiSystemProfile" => Ok(ArtifactKind::AgiSystemProfile),
        "agiFrameProfile" => Ok(ArtifactKind::AgiFrameProfile),
        "perfettoTrace" => Ok(ArtifactKind::PerfettoTrace),
        "screenshot" => Ok(ArtifactKind::Screenshot),
        "externalReport" => Ok(ArtifactKind::ExternalReport),
        "unknown" => Ok(ArtifactKind::Unknown),
        _ => Err(GpuStoreError::InvalidArtifact),
    }
}
fn parse_route(value: &str) -> Result<OpenRoute, GpuStoreError> {
    match value {
        "agi" => Ok(OpenRoute::Agi),
        "perfetto" => Ok(OpenRoute::Perfetto),
        "desktop" => Ok(OpenRoute::Desktop),
        "none" => Ok(OpenRoute::None),
        _ => Err(GpuStoreError::InvalidArtifact),
    }
}
