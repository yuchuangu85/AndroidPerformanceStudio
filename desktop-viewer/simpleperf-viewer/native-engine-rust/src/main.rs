use aps_simpleperf_engine::{
    analyze_path, input_limit_from_env, query_path, serve_binary, write_query_page, Query,
};
use std::io::{self, Write};
use std::path::Path;

fn run() -> Result<(), String> {
    let args: Vec<_> = std::env::args().skip(1).collect();
    match args.as_slice() {
        [] => serve_binary(&mut io::stdin().lock(), &mut io::stdout().lock()),
        [mode] if mode == "--binary" => {
            serve_binary(&mut io::stdin().lock(), &mut io::stdout().lock())
        }
        [command, path, query_args @ ..] if command == "query" => {
            let query = Query::from_cli(query_args)?;
            let page = query_path(Path::new(path), input_limit_from_env()?, &query)?;
            write_query_page(&mut io::stdout().lock(), page)
        }
        [command, path] if command == "summarize" || command == "summarize-tsv" => {
            let result = analyze_path(Path::new(path), input_limit_from_env()?)?;
            let s = result.summary;
            let summary = format!(
                "formatVersion\t{}\nrecordCount\t{}\nsampleCount\t{}\nlostSampleCount\t{}\nlostEventCount\t{}\ntotalEventCount\t{}\nfileCount\t{}\nthreadCount\t{}\n",
                s.format_version, s.record_count, s.sample_count, s.lost_sample_count,
                s.lost_event_count, s.total_event_count, s.file_count, s.thread_count,
            );
            io::stdout()
                .lock()
                .write_all(summary.as_bytes())
                .map_err(|e| format!("write summary: {e}"))
        }
        _ => Err(concat!(
            "usage: aps-simpleperf-engine [--binary | summarize <path> | ",
            "query <path> <threadCsv|-> <eventCsv|-> <start|-> <end|-> ",
            "<forward|inverted> <samples|events> <offset> <limit>]"
        )
        .into()),
    }
}

fn main() {
    if let Err(error) = run() {
        eprintln!("aps-simpleperf-engine: {error}");
        std::process::exit(1);
    }
}
