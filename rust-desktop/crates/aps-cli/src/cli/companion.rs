use std::{path::PathBuf, process::Command};

/// An installed companion only. Do not resolve a similarly named PATH binary.
pub fn benchmark_binary() -> Result<PathBuf, String> {
    let host = std::env::current_exe().map_err(|_| "E_RUNNER_UNAVAILABLE: cannot locate host")?;
    let path = host.with_file_name(format!("aps-benchmark{}", std::env::consts::EXE_SUFFIX));
    path.is_file()
        .then_some(path)
        .ok_or_else(|| "E_RUNNER_UNAVAILABLE: install aps-benchmark beside aps".into())
}

pub fn run_macrobenchmark(args: &[String]) -> Result<i32, String> {
    if !matches!(
        args.first().map(String::as_str),
        Some("macro-plan" | "macro-run")
    ) {
        return Err("E_RUNNER_MODE: expected macro-plan or macro-run".into());
    }
    let status = Command::new(benchmark_binary()?)
        .env_remove("APS_CLI_AI_KEY")
        .args(args)
        .status()
        .map_err(|_| "E_RUNNER_UNAVAILABLE: cannot execute companion")?;
    Ok(status.code().unwrap_or(1))
}
