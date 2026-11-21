//! Host-only startup selection. JSON-RPC tool arguments cannot change these paths.
use std::ffi::OsString;
use std::path::PathBuf;

const USAGE: &str =
    "usage: aps-mcp --hprof <B> --worker <aps-memory-worker> [--baseline-hprof <A>]";

pub(super) struct Options {
    pub(super) hprof: PathBuf,
    pub(super) worker: PathBuf,
    pub(super) baseline: Option<PathBuf>,
}

pub(super) fn args() -> Result<Options, String> {
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    let options = parse_args(&arguments)?;
    if !options.hprof.is_file()
        || !options.worker.is_file()
        || options
            .baseline
            .as_ref()
            .is_some_and(|path| !path.is_file())
    {
        return Err("MCP inputs must be regular files".into());
    }
    Ok(options)
}

fn parse_args(arguments: &[OsString]) -> Result<Options, String> {
    let (mut hprof, mut worker, mut baseline) = (None, None, None);
    let (pairs, remainder) = arguments.as_chunks::<2>();
    if !remainder.is_empty() {
        return Err(USAGE.into());
    }
    for [flag, value] in pairs {
        let slot = match flag.to_str() {
            Some("--hprof") => &mut hprof,
            Some("--worker") => &mut worker,
            Some("--baseline-hprof") => &mut baseline,
            _ => return Err(USAGE.into()),
        };
        if slot.replace(PathBuf::from(value)).is_some() {
            return Err(USAGE.into());
        }
    }
    Ok(Options {
        hprof: hprof.ok_or(USAGE)?,
        worker: worker.ok_or(USAGE)?,
        baseline,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn options(values: &[&str]) -> Result<Options, String> {
        parse_args(&values.iter().map(OsString::from).collect::<Vec<_>>())
    }

    #[test]
    fn cli_keeps_legacy_mode_and_binds_optional_baseline_independently_of_flag_order() {
        let single = options(&["--hprof", "B.hprof", "--worker", "worker"]).unwrap();
        assert_eq!(single.hprof, PathBuf::from("B.hprof"));
        assert_eq!(single.worker, PathBuf::from("worker"));
        assert!(single.baseline.is_none());
        let pair = options(&[
            "--worker",
            "worker",
            "--baseline-hprof",
            "A.hprof",
            "--hprof",
            "B.hprof",
        ])
        .unwrap();
        assert_eq!(pair.hprof, PathBuf::from("B.hprof"));
        assert_eq!(pair.baseline, Some(PathBuf::from("A.hprof")));
    }

    #[test]
    fn cli_rejects_missing_unknown_duplicate_and_unpaired_flags() {
        for arguments in [
            vec![],
            vec!["--hprof", "B.hprof"],
            vec!["--hprof", "B.hprof", "--worker"],
            vec![
                "--hprof",
                "B.hprof",
                "--worker",
                "worker",
                "--unknown",
                "A.hprof",
            ],
            vec![
                "--hprof",
                "B.hprof",
                "--worker",
                "worker",
                "--hprof",
                "other.hprof",
            ],
            vec![
                "--hprof", "B.hprof", "--worker", "worker", "--worker", "other",
            ],
            vec![
                "--hprof",
                "B.hprof",
                "--worker",
                "worker",
                "--baseline-hprof",
                "A.hprof",
                "--baseline-hprof",
                "other.hprof",
            ],
        ] {
            assert!(options(&arguments).is_err());
        }
    }
}
