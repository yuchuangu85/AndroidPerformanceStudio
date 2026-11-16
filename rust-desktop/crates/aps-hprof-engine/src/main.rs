fn main() {
    let stdin = std::io::stdin();
    let stdout = std::io::stdout();
    if let Err(error) = aps_hprof_engine::serve(&mut stdin.lock(), &mut stdout.lock()) {
        eprintln!("aps-hprof-engine: {error}");
        std::process::exit(1);
    }
}
