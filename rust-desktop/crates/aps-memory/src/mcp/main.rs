//! Read-only stdio MCP server for one selected HPROF and an optional comparison baseline.
//! This binary uses the memory library; the library does not compile its protocol.
mod args;
mod comparison;
mod projection;
mod protocol;
mod server;

use aps_platform::CancellationToken;
use serde_json::Value;
use std::io::{self, BufRead, Write};

fn main() {
    if let Err(error) = run() {
        let _ = writeln!(io::stderr(), "aps-mcp: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let options = args::args()?;
    let cancel = CancellationToken::new();
    let mut server = server::McpServer::open(&options, &cancel)?;
    let stdin = io::stdin();
    let mut stdout = io::BufWriter::new(io::stdout());
    for line in stdin.lock().lines() {
        let line = line.map_err(|error| error.to_string())?;
        if line.trim().is_empty() {
            continue;
        }
        let request: Value = serde_json::from_str(&line).map_err(|_| "invalid JSON-RPC input")?;
        if request.get("id").is_none() {
            continue;
        }
        let response = protocol::dispatch(&mut server, &cancel, &request);
        serde_json::to_writer(&mut stdout, &response).map_err(|error| error.to_string())?;
        stdout.write_all(b"\n").map_err(|error| error.to_string())?;
        stdout.flush().map_err(|error| error.to_string())?;
    }
    Ok(())
}
