//! Conservative .gitignore subset. Negation never widens access. Unsupported
//! escape/bracket syntax excludes the directory instead of guessing a match.
use std::io::Read;
use std::path::Path;
#[derive(Debug, Clone)]
pub(crate) struct IgnoreRule {
    base: String,
    pattern: String,
    directory_only: bool,
    basename: bool,
}
pub(crate) fn load(root: &Path, relative: &str, rules: &mut Vec<IgnoreRule>) -> Result<(), String> {
    let path = root.join(relative).join(".gitignore");
    let metadata = match std::fs::symlink_metadata(&path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(_) => return Err("cannot inspect source ignore policy".into()),
    };
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() > 64 * 1024 {
        return Err("source ignore policy is unsafe or exceeds budget".into());
    }
    let mut bytes = Vec::new();
    std::fs::File::open(&path)
        .map_err(|_| "cannot read source ignore policy")?
        .take(64 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "cannot read source ignore policy")?;
    if bytes.len() > 64 * 1024 {
        return Err("source ignore policy exceeds streaming budget".into());
    }
    let text = String::from_utf8(bytes).map_err(|_| "source ignore policy is not UTF-8")?;
    for line in text.lines() {
        let line = line.trim_end_matches('\r');
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        if line.len() > 512 || line.contains(['\\', '[', ']']) || line.ends_with(' ') {
            return Err(
                "unsupported source ignore syntax; directory is excluded conservatively".into(),
            );
        }
        if rules.len() == 1024 {
            return Err("source ignore rule budget exceeded".into());
        }
        let directory_only = line.ends_with('/');
        let anchored = line.starts_with('/');
        let pattern = line.trim_start_matches('/').trim_end_matches('/');
        if pattern.is_empty() {
            continue;
        }
        let basename = !anchored && !pattern.contains('/');
        rules.push(IgnoreRule {
            base: relative.into(),
            pattern: pattern.into(),
            directory_only,
            basename,
        });
    }
    Ok(())
}
pub(crate) fn excluded(path: &str, is_directory: bool, rules: &[IgnoreRule]) -> bool {
    rules.iter().any(|rule| {
        if rule.directory_only && !is_directory {
            return false;
        }
        let relative = if rule.base.is_empty() {
            path
        } else {
            let Some(rest) = path
                .strip_prefix(&rule.base)
                .and_then(|rest| rest.strip_prefix('/'))
            else {
                return false;
            };
            rest
        };
        let target = if rule.basename {
            relative.rsplit('/').next().unwrap_or(relative)
        } else {
            relative
        };
        glob(rule.pattern.as_bytes(), target.as_bytes())
    })
}
fn glob(pattern: &[u8], text: &[u8]) -> bool {
    let mut previous = vec![false; text.len() + 1];
    previous[0] = true;
    let mut index = 0;
    while index < pattern.len() {
        let mut current = vec![false; text.len() + 1];
        if pattern[index] == b'*' {
            let double = pattern.get(index + 1) == Some(&b'*');
            if double {
                index += 1;
            }
            current[0] = previous[0];
            for position in 1..=text.len() {
                current[position] = previous[position]
                    || ((double || text[position - 1] != b'/') && current[position - 1]);
            }
        } else {
            for position in 1..=text.len() {
                current[position] = previous[position - 1]
                    && if pattern[index] == b'?' {
                        text[position - 1] != b'/'
                    } else {
                        pattern[index] == text[position - 1]
                    };
            }
        }
        previous = current;
        index += 1;
    }
    previous[text.len()]
}
