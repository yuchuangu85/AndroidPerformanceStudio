//! Selected active PackageManager record only. Hidden system-package records,
//! resolver entries and per-user permission fields are not installation proof.
use super::*;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct InstallationIdentity {
    pub code_path: String,
    pub version_code: u64,
    pub last_update_time: String,
}

pub(super) fn read_installation(
    adb: &AdbClient,
    target: &BatteryCaptureTarget,
    control: &BatteryCaptureControl,
    deadline: Instant,
) -> Result<InstallationIdentity, String> {
    let output = read(
        adb,
        &target.serial,
        &["dumpsys", "package", &target.package_name],
        2 * 1024 * 1024,
        control,
        deadline,
    )?;
    parse_installation(&output, &target.package_name)
}

fn parse_installation(output: &str, package: &str) -> Result<InstallationIdentity, String> {
    let mut active_packages = false;
    let mut selected = false;
    let mut matches = 0;
    let mut code_path = None;
    let mut version_code = None;
    let mut last_update_time = None;
    let prefix = format!("  Package [{package}] (");
    for line in output.lines() {
        if line == "Packages:" {
            active_packages = true;
            selected = false;
            continue;
        }
        if !line.is_empty() && !line.starts_with(char::is_whitespace) {
            active_packages = false;
            selected = false;
        }
        if !active_packages || line.trim().is_empty() {
            continue;
        }
        if !line.starts_with("    ") {
            selected = line.starts_with(&prefix) && line.ends_with("):");
            if selected {
                matches += 1;
            }
            continue;
        }
        if !selected {
            continue;
        }
        // Exactly the package record's own property indentation. Deeper nested
        // fields cannot override or stand in for these three required values.
        let field = &line[4..];
        if let Some(value) = field.strip_prefix("codePath=") {
            if code_path.replace(value.to_owned()).is_some() {
                return Err("Ambiguous Battery package code path".into());
            }
        } else if let Some(value) = field.strip_prefix("versionCode=") {
            let value = value
                .split_whitespace()
                .next()
                .and_then(|value| value.parse::<u64>().ok())
                .ok_or("Battery package version code is unavailable")?;
            if version_code.replace(value).is_some() {
                return Err("Ambiguous Battery package version code".into());
            }
        } else if let Some(value) = field.strip_prefix("lastUpdateTime=")
            && last_update_time.replace(value.to_owned()).is_some()
        {
            return Err("Ambiguous Battery package update time".into());
        }
    }
    let identity = InstallationIdentity {
        code_path: code_path.ok_or("Selected Battery package code path is unavailable")?,
        version_code: version_code.ok_or("Selected Battery package version code is unavailable")?,
        last_update_time: last_update_time
            .ok_or("Selected Battery package update time is unavailable")?,
    };
    if matches != 1
        || !identity.code_path.starts_with('/')
        || identity.code_path.len() > 4096
        || identity.code_path.chars().any(char::is_control)
        || !valid_update_time(&identity.last_update_time)
    {
        return Err("Cannot establish unambiguous active PackageManager installation metadata for the selected Battery package.".into());
    }
    Ok(identity)
}

fn valid_update_time(value: &str) -> bool {
    value.len() == 19
        && value.bytes().enumerate().all(|(index, byte)| match index {
            4 | 7 => byte == b'-',
            10 => byte == b' ',
            13 | 16 => byte == b':',
            _ => byte.is_ascii_digit(),
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    const ACTIVE: &str = "Packages:\n  Package [example.app] (abc):\n    codePath=/data/app/example\n    versionCode=42 minSdk=23 targetSdk=36\n    lastUpdateTime=2026-11-11 00:01:02\n";

    #[test]
    fn active_installation_ignores_hidden_and_other_package_fields() {
        let output = format!(
            "{ACTIVE}  Package [other.app] (a):\n    codePath=/other\nHidden system packages:\n  Package [example.app] (b):\n    codePath=/system/app/original\n    versionCode=1\n    lastUpdateTime=2020-01-01 00:00:00\n"
        );
        let identity = parse_installation(&output, "example.app").unwrap();
        assert_eq!(identity.version_code, 42);
        assert_eq!(identity.code_path, "/data/app/example");
        assert_eq!(identity.last_update_time, "2026-11-11 00:01:02");
    }

    #[test]
    fn missing_duplicate_or_wrong_package_identity_fails_closed() {
        assert!(parse_installation(ACTIVE, "other.app").is_err());
        assert!(parse_installation(&format!("{ACTIVE}{ACTIVE}"), "example.app").is_err());
        assert!(
            parse_installation(
                &ACTIVE.replace("    codePath=", "      codePath="),
                "example.app"
            )
            .is_err()
        );
        assert!(
            parse_installation(
                &ACTIVE.replace("2026-11-11 00:01:02", "unknown"),
                "example.app"
            )
            .is_err()
        );
    }
}
