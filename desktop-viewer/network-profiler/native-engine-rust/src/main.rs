use std::fs::File;
use std::io::{self, Write};

fn run() -> Result<(), String> {
    let mut args = std::env::args_os().skip(1);
    let command = args.next().ok_or("missing command")?;
    let tsv = command == "summarize-tsv";
    let entries = command == "stream-entries";
    if command != "summarize" && !tsv && !entries {
        return Err(
            "usage: aps-har-engine <summarize|summarize-tsv|stream-entries> <file.har|->".into(),
        );
    }
    let path = args.next().ok_or("missing HAR path")?;
    if args.next().is_some() {
        return Err("unexpected argument".into());
    }
    if entries {
        let output = io::stdout();
        return if path == "-" {
            aps_har_engine::stream_entries(io::stdin().lock(), output.lock())
        } else {
            let file = File::open(path).map_err(|_| "cannot open HAR input")?;
            aps_har_engine::stream_entries(file, output.lock())
        };
    }
    let summary = if path == "-" {
        aps_har_engine::summarize(io::stdin().lock())?
    } else {
        let file = File::open(path).map_err(|_| "cannot open HAR input")?;
        aps_har_engine::summarize(file)?
    };
    let output = if tsv {
        format!("formatVersion\t1\nentryCount\t{}\n", summary.entry_count)
    } else {
        format!("{}\n", summary.to_json())
    };
    io::stdout()
        .lock()
        .write_all(output.as_bytes())
        .map_err(|_| "cannot write HAR summary".into())
}
fn main() {
    if let Err(error) = run() {
        eprintln!("aps-har-engine: {error}");
        std::process::exit(1);
    }
}
