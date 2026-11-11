//! Small internal helpers shared by the parser, exporters and orchestration.
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// Current UTC instant as a fixed-width, lexicographically sortable RFC3339 string.
pub(crate) fn now_rfc3339() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0);
    format_rfc3339(seconds as i64)
}

pub(crate) fn now_unix_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

pub(crate) fn format_rfc3339(seconds: i64) -> String {
    let days = seconds.div_euclid(86_400);
    let of_day = seconds.rem_euclid(86_400);
    let (year, month, day) = civil_from_days(days);
    let hour = of_day / 3_600;
    let minute = (of_day % 3_600) / 60;
    let second = of_day % 60;
    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}Z")
}

/// Days since the Unix epoch to a proleptic Gregorian date (Howard Hinnant's algorithm).
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let shifted = days + 719_468;
    let era = if shifted >= 0 {
        shifted
    } else {
        shifted - 146_096
    } / 146_097;
    let day_of_era = shifted - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * month_prime + 2) / 5 + 1) as u32;
    let month = if month_prime < 10 {
        month_prime + 3
    } else {
        month_prime - 9
    } as u32;
    (if month <= 2 { year + 1 } else { year }, month, day)
}

/// Absolute, lexically normalised path (does not resolve symlinks or require existence).
pub(crate) fn absolute_normalize(path: &Path) -> PathBuf {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().unwrap_or_default().join(path)
    };
    let mut normalized = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                normalized.pop();
            }
            other => normalized.push(other.as_os_str()),
        }
    }
    normalized
}

/// Trim trailing fractional zeros while keeping the integer part intact.
fn trim_decimal(text: &mut String) {
    if text.contains('.') {
        while text.ends_with('0') {
            text.pop();
        }
        if text.ends_with('.') {
            text.pop();
        }
    }
}

/// C-style `%g` with a fixed number of significant digits (used by the PDF evidence table).
pub(crate) fn format_general(value: f64, significant: usize) -> String {
    if !value.is_finite() {
        return value.to_string();
    }
    if value == 0.0 {
        return "0".to_string();
    }
    let significant = significant.max(1);
    let exponent = value.abs().log10().floor() as i32;
    if exponent < -4 || exponent >= significant as i32 {
        let mantissa = value / 10f64.powi(exponent);
        let mut text = format!("{:.*}", significant - 1, mantissa);
        trim_decimal(&mut text);
        let sign = if exponent < 0 { '-' } else { '+' };
        format!("{text}e{sign}{:02}", exponent.abs())
    } else {
        let decimals = (significant as i32 - 1 - exponent).max(0) as usize;
        let mut text = format!("{:.*}", decimals, value);
        trim_decimal(&mut text);
        text
    }
}

/// C-style `%+.2f` used for signed percentage columns.
pub(crate) fn format_percent(value: f64) -> String {
    format!("{value:+.2}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc3339_is_fixed_width_utc() {
        assert_eq!(format_rfc3339(0), "1970-01-01T00:00:00Z");
        assert_eq!(format_rfc3339(1_700_000_000), "2023-11-14T22:13:20Z");
        assert_eq!(format_rfc3339(1_700_000_000).len(), 20);
        assert_eq!(format_rfc3339(-1), "1969-12-31T23:59:59Z");
    }

    #[test]
    fn general_format_matches_percent_g() {
        assert_eq!(format_general(100.0, 4), "100");
        assert_eq!(format_general(12.5, 4), "12.5");
        assert_eq!(format_general(1.23456, 4), "1.235");
        assert_eq!(format_general(0.0, 4), "0");
        assert_eq!(format_general(123_456.0, 4), "1.235e+05");
        assert_eq!(format_percent(20.0), "+20.00");
        assert_eq!(format_percent(-std::f64::consts::PI), "-3.14");
    }

    #[test]
    fn absolute_normalize_lexically_resolves_components() {
        assert_eq!(
            absolute_normalize(Path::new("/tmp/a/../b/./c")),
            PathBuf::from("/tmp/b/c")
        );
    }
}
