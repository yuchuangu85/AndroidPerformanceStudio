//! Bounded JSON Lines transport. Protocol notifications never reach stdout.
use super::{
    RpcError,
    protocol::{dispatch, error_response},
    source::Selected,
};
use aps_json::{Limits, Value};
use std::io::{self, BufRead, Write};

const REQUEST_LIMIT: usize = 64 * 1024;

/// Frame one line at a time while *draining* oversized requests; read_line()
/// alone would allocate the attacker's entire request before checking a limit.
fn message(reader: &mut impl BufRead) -> io::Result<Option<Result<Vec<u8>, RpcError>>> {
    let mut bytes = Vec::new();
    let mut overflow = false;
    loop {
        let buf = reader.fill_buf()?;
        if buf.is_empty() && bytes.is_empty() && !overflow {
            return Ok(None);
        }
        let end = buf.iter().position(|b| *b == b'\n');
        let n = end.unwrap_or(buf.len());
        let done = end.is_some() || buf.is_empty();
        if !overflow {
            if bytes.len().saturating_add(n) > REQUEST_LIMIT {
                overflow = true;
            } else {
                bytes.extend_from_slice(&buf[..n]);
            }
        }
        reader.consume(n + usize::from(end.is_some()));
        if done {
            return Ok(Some(if overflow {
                Err((-32600, "request exceeds 64 KiB"))
            } else {
                Ok(bytes)
            }));
        }
    }
}

pub(super) fn serve(
    mut selected: Option<Selected>,
    mut reader: impl BufRead,
    mut out: impl Write,
) -> io::Result<()> {
    while let Some(msg) = message(&mut reader)? {
        let response = match msg {
            Ok(bytes) if bytes.iter().all(u8::is_ascii_whitespace) => continue,
            Err(error) => error_response(&Value::Null, error),
            Ok(bytes) => match aps_json::parse_slice(
                &bytes,
                Limits {
                    max_bytes: REQUEST_LIMIT as u64,
                    max_depth: 16,
                    max_nodes: 4096,
                    max_string_bytes: 16 * 1024,
                    max_collection_items: 128,
                },
            ) {
                Ok(request) => match dispatch(selected.as_mut(), &request) {
                    Some(response) => response,
                    None => continue,
                },
                Err(_) => error_response(&Value::Null, (-32700, "malformed or unbounded JSON")),
            },
        };
        serde_json::to_writer(&mut out, &response)?;
        out.write_all(b"\n")?;
        out.flush()?;
    }
    Ok(())
}
