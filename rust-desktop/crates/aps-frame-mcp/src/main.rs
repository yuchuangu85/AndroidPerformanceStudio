use aps_frame_mcp::{FrameMcpServer, MAX_REQUEST_BYTES, rpc_error};
use aps_json::Limits;
use serde_json::Value;
use std::io::{self, BufRead, Write};
use std::path::PathBuf;

const USAGE: &str = "usage: aps-frame-mcp --input REPORT [--capture-receipt RECEIPT --trace TRACE [--baseline-input REPORT --baseline-receipt RECEIPT --baseline-trace TRACE]]";

fn main() {
    if let Err(error) = run() {
        let _ = writeln!(io::stderr(), "aps-frame-mcp: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut args = std::env::args_os().skip(1);
    let mut paths: [Option<PathBuf>; 6] = Default::default();
    const FLAGS: [&str; 6] = [
        "--input",
        "--capture-receipt",
        "--trace",
        "--baseline-input",
        "--baseline-receipt",
        "--baseline-trace",
    ];
    while let Some(flag) = args.next() {
        let index = FLAGS.iter().position(|&s| flag == s).ok_or(USAGE)?;
        let path = args
            .next()
            .filter(|p| !p.is_empty() && !FLAGS.iter().any(|s| p == *s))
            .ok_or(USAGE)?;
        if paths[index].replace(path.into()).is_some() {
            return Err(USAGE.into());
        }
    }
    let mut server = match paths.each_ref().map(|p| p.as_deref()) {
        [Some(report), None, None, None, None, None] => FrameMcpServer::open(report),
        [Some(report), Some(receipt), Some(trace), None, None, None] => {
            FrameMcpServer::open_bound(report, Some((receipt, trace)), None)
        }
        [
            Some(report),
            Some(receipt),
            Some(trace),
            Some(b),
            Some(r),
            Some(t),
        ] => FrameMcpServer::open_bound(report, Some((receipt, trace)), Some((b, r, t))),
        _ => return Err(USAGE.into()),
    }?;
    let stdin = io::stdin();
    let mut reader = stdin.lock();
    let mut stdout = io::BufWriter::new(io::stdout());
    while let Some(line) = read_line(&mut reader)? {
        let response = match line {
            None => Some(rpc_error(Value::Null, -32600, "Request exceeds 64 KiB")),
            Some(bytes) if bytes.iter().all(u8::is_ascii_whitespace) => None,
            Some(bytes) => match aps_json::parse_slice(
                &bytes,
                Limits {
                    max_bytes: MAX_REQUEST_BYTES as u64,
                    max_depth: 12,
                    max_nodes: 4096,
                    max_string_bytes: 2048,
                    max_collection_items: 128,
                },
            ) {
                Ok(request) if request.is_object() => server.dispatch(&request),
                Ok(_) => Some(rpc_error(Value::Null, -32600, "Expected a JSON-RPC object")),
                Err(_) => Some(rpc_error(
                    Value::Null,
                    -32700,
                    "Malformed or unbounded JSON request",
                )),
            },
        };
        if let Some(response) = response {
            serde_json::to_writer(&mut stdout, &response).map_err(|error| error.to_string())?;
            stdout.write_all(b"\n").map_err(|error| error.to_string())?;
            stdout.flush().map_err(|error| error.to_string())?;
        }
    }
    Ok(())
}

// Drain oversized lines without accumulating them, so a later request still works.
fn read_line(reader: &mut impl BufRead) -> Result<Option<Option<Vec<u8>>>, String> {
    let mut line = Vec::new();
    let mut oversized = false;
    loop {
        let chunk = reader.fill_buf().map_err(|_| "Cannot read MCP stdin")?;
        if chunk.is_empty() {
            return Ok(if oversized {
                Some(None)
            } else if line.is_empty() {
                None
            } else {
                Some(Some(line))
            });
        }
        let end = chunk.iter().position(|&byte| byte == b'\n');
        let length = end.map_or(chunk.len(), |index| index + 1);
        if !oversized {
            if line.len().saturating_add(length) > MAX_REQUEST_BYTES {
                oversized = true;
                line.clear();
            } else {
                line.extend_from_slice(&chunk[..length]);
            }
        }
        reader.consume(length);
        if end.is_some() {
            return Ok(Some(if oversized { None } else { Some(line) }));
        }
    }
}
