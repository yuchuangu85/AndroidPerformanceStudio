pub mod analysis;
pub mod findings;
pub mod model;
pub mod parser;
pub mod query;
pub mod wire;

pub use query::Engine;
use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::path::Path;
use wire::Value;

#[derive(Default)]
pub struct Session {
    engine: Option<Engine>,
    source_digest: String,
    opened: bool,
}

fn argument<'a>(args: &'a BTreeMap<String, Value>, key: &str) -> Result<&'a Value, String> {
    args.get(key)
        .ok_or_else(|| format!("missing argument {key}"))
}
fn unsigned(args: &BTreeMap<String, Value>, key: &str) -> Result<usize, String> {
    usize::try_from(argument(args, key)?.as_int()?)
        .map_err(|_| format!("{key} must be a nonnegative index"))
}
fn optional_id(args: &BTreeMap<String, Value>, key: &str) -> Result<Option<u64>, String> {
    match args.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => Ok(Some(value.as_int()? as u64)),
    }
}

impl Session {
    pub fn respond(&mut self, request: &Value) -> (Value, bool) {
        let id = request.get("id").and_then(Value::as_int).unwrap_or(0);
        let result = self.dispatch(request);
        let (payload, close) = match result {
            Ok((payload, close)) => (
                Value::map([
                    ("version", wire::VERSION.into()),
                    ("id", id.into()),
                    ("sourceDigest", self.source_digest.as_str().into()),
                    ("status", "ok".into()),
                    ("payload", payload),
                ]),
                close,
            ),
            Err(error) => (self.error(id, error), false),
        };
        (payload, close)
    }
    fn error(&self, id: i64, error: String) -> Value {
        Value::map([
            ("version", wire::VERSION.into()),
            ("id", id.into()),
            ("sourceDigest", self.source_digest.as_str().into()),
            ("status", "error".into()),
            ("error", error.into()),
        ])
    }
    fn dispatch(&mut self, request: &Value) -> Result<(Value, bool), String> {
        if request.get("version")?.as_int()? != wire::VERSION {
            return Err("unsupported protocol version (expected 1)".into());
        }
        request.get("id")?.as_int()?;
        let op = request.get("op")?.as_str()?;
        let args = request.get("args")?.as_map()?;
        if op == "open" {
            if self.opened {
                return Err("this process already owns a snapshot; start a new process".into());
            }
            let path = argument(args, "path")?.as_str()?;
            let digest = argument(args, "sourceDigest")?.as_str()?;
            if digest.is_empty() || digest.len() > 1024 {
                return Err("sourceDigest must be a nonempty bounded string".into());
            }
            if let Some(value) = request.as_map()?.get("sourceDigest") {
                if value.as_str()? != digest {
                    return Err("sourceDigest mismatch".into());
                }
            }
            self.source_digest = digest.to_owned();
            let mapping = argument(args, "mapping")?
                .as_map()?
                .iter()
                .map(|(key, value)| Ok((key.clone(), value.as_str()?.to_owned())))
                .collect::<Result<BTreeMap<_, _>, String>>()?;
            let heap = model::Heap::open(Path::new(path))?;
            let engine = Engine::new(heap, mapping)?;
            let metadata = engine.metadata()?;
            self.engine = Some(engine);
            self.opened = true;
            return Ok((metadata, false));
        }
        for value in [
            request.as_map()?.get("sourceDigest"),
            args.get("sourceDigest"),
        ]
        .into_iter()
        .flatten()
        {
            if value.as_str()? != self.source_digest {
                return Err("sourceDigest mismatch".into());
            }
        }
        if op == "close" {
            self.engine = None;
            return Ok((Value::Null, true));
        }
        let engine = self
            .engine
            .as_ref()
            .ok_or("open a snapshot before querying")?;
        engine.heap.check_source()?;
        let payload = match op {
            "histogram" => engine.histogram(argument(args, "heapName")?.nullable_str()?)?,
            "rawHistogram" => engine.raw_histogram(argument(args, "heapName")?.nullable_str()?)?,
            "instances" => {
                let names = argument(args, "classNames")?
                    .as_list()?
                    .iter()
                    .map(|v| v.as_str().map(str::to_owned))
                    .collect::<Result<Vec<_>, _>>()?;
                engine.instances(
                    &names,
                    argument(args, "heapName")?.nullable_str()?,
                    unsigned(args, "offset")?,
                    unsigned(args, "limit")?,
                    argument(args, "ordinaryOnly")?.as_bool()?,
                    optional_id(args, "anchorObjectId")?,
                )?
            }
            "detail" => engine.detail(
                argument(args, "objectId")?.as_int()? as u64,
                unsigned(args, "arrayStart")?,
                unsigned(args, "arrayLimit")?,
            )?,
            "identity" => engine.identity(argument(args, "objectId")?.as_int()? as u64)?,
            "dominators" => engine.dominators(
                optional_id(args, "parentObjectId")?,
                unsigned(args, "offset")?,
                unsigned(args, "limit")?,
            )?,
            "findings" => engine.findings()?,
            _ => return Err(format!("unknown operation {op}")),
        };
        engine.heap.check_source()?;
        Ok((payload, false))
    }
}

pub fn serve(reader: &mut impl Read, writer: &mut impl Write) -> Result<(), String> {
    let mut session = Session::default();
    while let Some(bytes) = wire::read_frame(reader)? {
        let (response, close) = match wire::decode(&bytes) {
            Ok(request) => session.respond(&request),
            Err(error) => (session.error(0, error), false),
        };
        let encoded = match wire::encode(&response) {
            Ok(bytes) => bytes,
            Err(error) => {
                let id = response.get("id")?.as_int()?;
                wire::encode(&session.error(id, error))?
            }
        };
        wire::write_encoded(writer, &encoded)?;
        if close {
            break;
        }
    }
    Ok(())
}
