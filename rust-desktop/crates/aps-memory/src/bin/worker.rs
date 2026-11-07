fn main() {
    if aps_memory::worker::serve(&mut std::io::stdin().lock(), &mut std::io::stdout().lock())
        .is_err()
    {
        // Never print source paths, HPROF runtime values or engine errors.
        std::process::exit(1);
    }
}
