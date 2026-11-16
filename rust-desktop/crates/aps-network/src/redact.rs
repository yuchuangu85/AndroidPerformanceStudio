//! Deny-by-default URL and header redaction, plus bounded RFC 3339 timestamps.
//!
//! The policy mirrors the Compose NetworkUrlRedactor: scheme, host and port are
//! preserved, userinfo and path are replaced, query keys are preserved while
//! values are redacted, and fragments are dropped. Header values are retained
//! only for content metadata; every other value becomes `<redacted>`.

pub const REDACTED_VALUE: &str = "<redacted>";
pub const REDACTED_PATH: &str = "/<redacted-path>";
pub const INVALID_URL: &str = "redacted://invalid-url";
pub const MAX_REDACTED_URL_CHARS: usize = 1024;
pub const MAX_HEADER_NAME_CHARS: usize = 128;
pub const MAX_HEADER_VALUE_CHARS: usize = 512;
pub const MAX_HEADERS: usize = 64;

const SAFE_VALUE_HEADERS: [&str; 3] = ["content-type", "content-length", "content-encoding"];
const SENSITIVE_KEYS: [&str; 19] = [
    "token",
    "access_token",
    "refresh_token",
    "id_token",
    "auth",
    "authorization",
    "apikey",
    "api_key",
    "key",
    "password",
    "passwd",
    "secret",
    "client_secret",
    "session",
    "sessionid",
    "jsessionid",
    "credential",
    "signature",
    "sig",
];

pub fn is_sensitive_query_key(key: &str) -> bool {
    SENSITIVE_KEYS
        .iter()
        .any(|sensitive| key.eq_ignore_ascii_case(sensitive))
}

/// Redacts one URL with no query-value allowlist (the application default).
pub fn redact_url(raw: &str) -> String {
    redact_url_with(raw, &[])
}

/// Redacts one URL. The returned string never contains a raw path, a
/// non-allowlisted query value, userinfo or a fragment.
pub fn redact_url_with(raw: &str, query_key_allowlist: &[&str]) -> String {
    let raw = raw.trim();
    if raw.is_empty() || raw.bytes().any(|byte| byte.is_ascii_control()) {
        return INVALID_URL.into();
    }
    let Some((scheme, rest)) = raw.split_once("://") else {
        return INVALID_URL.into();
    };
    if !valid_scheme(scheme) {
        return INVALID_URL.into();
    }
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let (authority, remainder) = rest.split_at(end);
    let (userinfo, hostport) = match authority.rsplit_once('@') {
        Some((userinfo, hostport)) => (Some(userinfo), hostport),
        None => (None, authority),
    };
    let Some((host, port)) = split_host_port(hostport) else {
        return INVALID_URL.into();
    };
    if host.is_empty() {
        return INVALID_URL.into();
    }
    let (path, after_path) = match remainder.find('?') {
        Some(index) => (&remainder[..index], &remainder[index + 1..]),
        None => (remainder, ""),
    };
    let query = after_path.split('#').next().unwrap_or("");
    let mut out = String::with_capacity(raw.len().min(MAX_REDACTED_URL_CHARS));
    out.push_str(scheme);
    out.push_str("://");
    if userinfo.is_some() {
        out.push_str(REDACTED_VALUE);
        out.push('@');
    }
    out.push_str(host);
    if let Some(port) = port {
        out.push(':');
        out.push_str(port);
    }
    match path {
        "" => {}
        "/" => out.push('/'),
        _ => out.push_str(REDACTED_PATH),
    }
    if !query.is_empty() {
        out.push('?');
        for (index, parameter) in query.split('&').enumerate() {
            if index > 0 {
                out.push('&');
            }
            let (key, value) = match parameter.split_once('=') {
                Some((key, value)) => (key, Some(value)),
                None => (parameter, None),
            };
            out.push_str(key);
            out.push('=');
            let allowlisted = query_key_allowlist.contains(&key) && !is_sensitive_query_key(key);
            match value {
                Some(value) if allowlisted => out.push_str(&bounded(value, MAX_HEADER_VALUE_CHARS)),
                _ => out.push_str(REDACTED_VALUE),
            }
        }
    }
    if out.chars().count() > MAX_REDACTED_URL_CHARS {
        return INVALID_URL.into();
    }
    out
}

fn valid_scheme(scheme: &str) -> bool {
    let mut bytes = scheme.bytes();
    matches!(bytes.next(), Some(byte) if byte.is_ascii_alphabetic())
        && bytes.all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'-' | b'.'))
}

/// Splits an authority into host (IPv6 brackets retained) and an optional port.
fn split_host_port(authority: &str) -> Option<(&str, Option<&str>)> {
    if let Some(rest) = authority.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        let port = match tail.strip_prefix(':') {
            Some(port) if valid_port(port) => Some(port),
            Some(_) => return None,
            None if tail.is_empty() => None,
            None => return None,
        };
        return Some((&authority[..host.len() + 2], port));
    }
    match authority.rsplit_once(':') {
        Some((host, port)) => valid_port(port).then_some((host, Some(port))),
        None => Some((authority, None)),
    }
}

fn valid_port(port: &str) -> bool {
    !port.is_empty()
        && port.len() <= 5
        && port.bytes().all(|byte| byte.is_ascii_digit())
        && port
            .parse::<u32>()
            .is_ok_and(|value| (1..=65_535).contains(&value))
}

/// Redacts header pairs into a bounded, ordered list. Duplicate names keep the
/// last observed value, matching a header map.
pub fn redact_headers(pairs: impl IntoIterator<Item = (String, String)>) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = Vec::new();
    for (name, value) in pairs {
        if out.len() >= MAX_HEADERS {
            break;
        }
        let name = name.trim();
        if name.is_empty()
            || name.chars().count() > MAX_HEADER_NAME_CHARS
            || name.chars().any(char::is_control)
        {
            continue;
        }
        let retained = SAFE_VALUE_HEADERS
            .iter()
            .any(|safe| name.eq_ignore_ascii_case(safe));
        let value = if retained {
            bounded(value.trim(), MAX_HEADER_VALUE_CHARS)
        } else {
            REDACTED_VALUE.to_owned()
        };
        if let Some(existing) = out
            .iter_mut()
            .find(|(existing, _)| existing.eq_ignore_ascii_case(name))
        {
            existing.1 = value;
        } else {
            out.push((name.to_owned(), value));
        }
    }
    out
}

fn bounded(value: &str, max_chars: usize) -> String {
    value.chars().take(max_chars).collect()
}

/// Parses a bounded RFC 3339/ISO-8601 timestamp into Unix milliseconds. Returns
/// `None` for anything the strict parser cannot interpret, never a guess.
pub fn parse_rfc3339_ms(raw: &str) -> Option<i64> {
    let raw = raw.trim();
    let bytes = raw.as_bytes();
    if bytes.len() < 20 || bytes[4] != b'-' || bytes[7] != b'-' || bytes[10] != b'T' {
        return None;
    }
    if bytes[13] != b':' || bytes[16] != b':' {
        return None;
    }
    let year = digits(&raw[0..4])?;
    let month = digits(&raw[5..7])?;
    let day = digits(&raw[8..10])?;
    let hour = digits(&raw[11..13])?;
    let minute = digits(&raw[14..16])?;
    let second = digits(&raw[17..19])?;
    if !(1..=12).contains(&month)
        || day == 0
        || day > days_in_month(year, month)
        || hour > 23
        || minute > 59
        || second > 59
    {
        return None;
    }
    let mut index = 19;
    let mut fraction_ms = 0_i64;
    if bytes.get(index) == Some(&b'.') {
        index += 1;
        let start = index;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
            index += 1;
        }
        let digits_count = index - start;
        if digits_count == 0 || digits_count > 9 {
            return None;
        }
        let value = i64::from(digits(&raw[start..index])?);
        fraction_ms = match digits_count {
            1..=3 => value * 10_i64.pow(3 - digits_count as u32),
            4..=9 => value / 10_i64.pow(digits_count as u32 - 3),
            _ => unreachable!(),
        };
    }
    let offset_minutes = match bytes.get(index) {
        Some(b'Z') | Some(b'z') if index + 1 == bytes.len() => 0,
        Some(sign @ (b'+' | b'-')) if index + 6 == bytes.len() => {
            let hours = digits(&raw[index + 1..index + 3])?;
            if raw.as_bytes()[index + 3] != b':' {
                return None;
            }
            let minutes = digits(&raw[index + 4..index + 6])?;
            if hours > 23 || minutes > 59 {
                return None;
            }
            let value = i64::from(hours) * 60 + i64::from(minutes);
            if *sign == b'-' { -value } else { value }
        }
        _ => return None,
    };
    let days = days_from_civil(year, month, day);
    let seconds =
        days * 86_400 + i64::from(hour) * 3_600 + i64::from(minute) * 60 + i64::from(second)
            - offset_minutes * 60;
    seconds.checked_mul(1_000)?.checked_add(fraction_ms)
}

pub fn format_rfc3339_ms(millis: i64) -> String {
    let seconds = millis.div_euclid(1_000);
    let fraction = millis.rem_euclid(1_000);
    let days = seconds.div_euclid(86_400);
    let seconds_of_day = seconds.rem_euclid(86_400);
    let (year, month, day) = civil_from_days(days);
    let hour = seconds_of_day / 3_600;
    let minute = (seconds_of_day % 3_600) / 60;
    let second = seconds_of_day % 60;
    if fraction == 0 {
        format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}Z")
    } else {
        format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}.{fraction:03}Z")
    }
}

fn digits(value: &str) -> Option<u32> {
    (!value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit()))
        .then(|| value.parse().ok())
        .flatten()
}

fn days_in_month(year: u32, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if year.is_multiple_of(4) && (!year.is_multiple_of(100) || year.is_multiple_of(400)) => {
            29
        }
        2 => 28,
        _ => 0,
    }
}

fn days_from_civil(year: u32, month: u32, day: u32) -> i64 {
    let year = i64::from(year) - i64::from(month <= 2);
    let era = year.div_euclid(400);
    let yoe = year - era * 400;
    let month = i64::from(month);
    let doy = (153 * (month + if month > 2 { -3 } else { 9 }) + 2) / 5 + i64::from(day) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (
        if month <= 2 { year + 1 } else { year },
        month as u32,
        day as u32,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_redaction_preserves_authority_and_redacts_identity() {
        assert_eq!(
            redact_url("https://alice:pw@Example.Test:8443/users/42?token=abc&page=2#frag"),
            "https://<redacted>@Example.Test:8443/<redacted-path>?token=<redacted>&page=<redacted>"
        );
        assert_eq!(redact_url("http://example.test"), "http://example.test");
        assert_eq!(redact_url("http://example.test/"), "http://example.test/");
        assert_eq!(
            redact_url("https://[2001:db8::1]:443/a?q=1"),
            "https://[2001:db8::1]:443/<redacted-path>?q=<redacted>"
        );
    }

    #[test]
    fn malformed_or_unbounded_urls_are_replaced() {
        for raw in [
            "",
            "not a url",
            "https:///path",
            "1https://example.test",
            "https://example.test:0/",
            "https://example.test:99999/",
            "https://example.test/a
b",
        ] {
            assert_eq!(redact_url(raw), INVALID_URL, "raw: {raw:?}");
        }
        let long = format!("https://example.test/?{}", "k=1&".repeat(400));
        assert_eq!(redact_url(&long), INVALID_URL);
    }

    #[test]
    fn allowlisted_query_values_survive_but_sensitive_keys_never_do() {
        assert_eq!(
            redact_url_with(
                "https://example.test/a?lang=en&token=abc&page=2",
                &["lang", "token", "page"]
            ),
            "https://example.test/<redacted-path>?lang=en&token=<redacted>&page=2"
        );
    }

    #[test]
    fn headers_keep_only_content_metadata_and_are_bounded() {
        let headers = redact_headers(vec![
            ("Content-Type".into(), "application/json".into()),
            ("Authorization".into(), "Bearer secret".into()),
            ("X-Repeat".into(), "one".into()),
            ("X-Repeat".into(), "two".into()),
            (
                "bad
name"
                    .into(),
                "x".into(),
            ),
        ]);
        assert_eq!(
            headers,
            vec![
                ("Content-Type".to_owned(), "application/json".to_owned()),
                ("Authorization".to_owned(), REDACTED_VALUE.to_owned()),
                ("X-Repeat".to_owned(), REDACTED_VALUE.to_owned()),
            ]
        );
    }

    #[test]
    fn timestamps_roundtrip_and_reject_invalid_values() {
        for raw in [
            "2026-11-16T22:00:00Z",
            "2026-11-16T22:00:00.500Z",
            "2026-11-16T22:00:00+08:00",
            "2026-11-16T22:00:00-05:30",
        ] {
            let millis = parse_rfc3339_ms(raw).unwrap();
            let canonical = format_rfc3339_ms(millis);
            assert_eq!(parse_rfc3339_ms(&canonical), Some(millis));
        }
        assert_eq!(
            parse_rfc3339_ms("2026-11-16T22:00:00+08:00"),
            parse_rfc3339_ms("2026-11-16T14:00:00Z")
        );
        assert_eq!(parse_rfc3339_ms("2026-13-01T00:00:00Z"), None);
        assert_eq!(parse_rfc3339_ms("2026-02-30T00:00:00Z"), None);
        assert_eq!(parse_rfc3339_ms("2026-11-16 22:00:00Z"), None);
        assert_eq!(parse_rfc3339_ms("secret-timestamp"), None);
        assert_eq!(parse_rfc3339_ms("2026-11-16T00:00:00"), None);
    }

    #[test]
    fn leap_day_and_epoch_are_stable() {
        assert_eq!(
            parse_rfc3339_ms("2024-02-29T00:00:00Z"),
            Some(1_709_164_800_000)
        );
        assert_eq!(parse_rfc3339_ms("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(format_rfc3339_ms(0), "1970-01-01T00:00:00Z");
    }
}
