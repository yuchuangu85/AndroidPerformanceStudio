fn main() {
    let args = std::env::args_os()
        .skip(1)
        .map(|arg| {
            arg.into_string()
                .map_err(|_| "E_USAGE: arguments must be valid UTF-8".to_string())
        })
        .collect::<Result<Vec<_>, _>>();
    if let Err(error) = args.and_then(|args| aps_cli::mcp::run(&args)) {
        eprintln!("aps-orchestrator-mcp: {error}");
        std::process::exit(2);
    }
}
