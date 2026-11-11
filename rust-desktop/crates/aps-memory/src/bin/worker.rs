fn main() {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if !args.is_empty() {
        if args.len() != 4
            || args[0] != "--bitmap-extract"
            || aps_memory::bitmap::serve_extract(
                std::path::Path::new(&args[1]),
                std::path::Path::new(&args[2]),
                &args[3].to_string_lossy(),
                &mut std::io::stdout().lock(),
            )
            .is_err()
        {
            std::process::exit(1);
        }
        return;
    }
    if aps_memory::worker::serve(&mut std::io::stdin().lock(), &mut std::io::stdout().lock())
        .is_err()
    {
        // Never print source paths, HPROF runtime values or engine errors.
        std::process::exit(1);
    }
}
