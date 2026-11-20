//! Host-bound, read-only stdio frontend for headless analysis. The MCP client
//! interprets the user's question; the APS server returns measured evidence.
//! No directory, URL, device, model or output path is accepted in a tool call.
mod benchmark;
mod follow_up;
mod protocol;
mod source;
mod stdio;
mod tools;

use source::Selected;
use std::{io, path::Path};

const USAGE: &str = "usage: aps mcp [--input LOCAL_FILE [--baseline BEFORE.json [--threshold-percent P]] | --artifact-url HTTPS_URL]";
type RpcError = (i64, &'static str);

pub fn run(args: &[String]) -> Result<(), String> {
    // Only the launching host can nominate a URL. Acquire once before reading
    // any JSON-RPC, hold the temp file through EOF, and never refetch per call.
    let downloaded;
    let selected = match args {
        [] => None,
        [flag, path] if flag == "--input" => Some(Selected::open(Path::new(path))?),
        [flag, url] if flag == "--artifact-url" => {
            downloaded = crate::input::SelectedInput::from_https(url)?;
            Some(Selected::open(downloaded.path())?)
        }
        _ if args.iter().any(|arg| arg == "--baseline") => Some(benchmark::open(args)?),
        _ => return Err(USAGE.into()),
    };
    let reader = io::stdin();
    let writer = io::stdout();
    stdio::serve(selected, reader.lock(), writer.lock())
        .map_err(|_| "E_MCP_IO: stdio closed".into())
}
