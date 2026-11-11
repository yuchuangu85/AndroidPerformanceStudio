use crate::{ArtifactKind, GpuArtifact, GpuArtifactIndex, OpenRoute};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Value};
use aps_platform::atomic_write;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const STORE_VERSION: i64 = 1;
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
        if map.get("version").and_then(|value| value.as_i64()) != Some(STORE_VERSION) {
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
            artifacts.push(decode_artifact(item)?);
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
        modified_ms(artifact.modified_at).map_or(Value::Null, |value| value.into()),
    );
    map.insert("openRoute".into(), route_name(artifact.open_route).into());
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

fn decode_artifact(value: &Value) -> Result<GpuArtifact, GpuStoreError> {
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
    let modified_at = map
        .get("modifiedAtMs")
        .and_then(|value| value.as_i64())
        .and_then(|value| u64::try_from(value).ok())
        .map(|value| UNIX_EPOCH + Duration::from_millis(value));
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
    GpuArtifact::from_indexed(kind, digest, size, modified_at, route, locations)
        .map_err(|_| GpuStoreError::InvalidArtifact)
}

fn modified_ms(value: Option<SystemTime>) -> Option<i64> {
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
