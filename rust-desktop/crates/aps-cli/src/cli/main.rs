fn main() {
    let args = std::env::args_os()
        .skip(1)
        .map(|arg| {
            arg.into_string()
                .map_err(|_| "E_USAGE: arguments must be valid UTF-8".to_owned())
        })
        .collect::<Result<Vec<_>, _>>();
    let code = match args.and_then(aps_cli::cli::run) {
        Ok(code) => code,
        Err(error) => {
            eprintln!("aps: {error}");
            2
        }
    };
    std::process::exit(code);
}
