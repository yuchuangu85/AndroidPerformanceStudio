use crate::{
    BatterySnapshot, DeviceState, MAX_INPUT_BYTES, ResourceKind, ResourceTimer, SnapshotIdentity,
};
use std::collections::BTreeMap;

pub fn parse_snapshot(
    checkin: &str,
    report: &str,
    battery: &str,
    identity: SnapshotIdentity,
) -> Result<BatterySnapshot, String> {
    for input in [checkin, report, battery] {
        validate(input)?;
    }
    if identity.device_id.len() > 4096
        || identity
            .boot_id
            .as_ref()
            .is_some_and(|value| value.len() > 4096)
    {
        return Err("Battery identity exceeds text budget".into());
    }
    let mut snapshot = BatterySnapshot {
        identity,
        stats_period: None,
        device: parse_device_state(battery)?,
        timers: BTreeMap::new(),
        network_bytes: BTreeMap::new(),
        modeled_energy_mah: BTreeMap::new(),
        uid_records_present: false,
        warnings: Vec::new(),
    };
    let mut rows = 0usize;
    let mut battery_period = None;
    // Preserve the explicitly tagged legacy snapshot format. Live Android
    // checkin v9 uses milliseconds and typed timer records, not this old import
    // projection's unmarked microsecond groups.
    let legacy_timers = checkin
        .lines()
        .any(|line| line.split(',').nth(3) == Some("start"));
    for line in checkin
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        rows += 1;
        if rows > 250_000 {
            return Err("Battery checkin exceeds record budget".into());
        }
        let fields = csv_line(line)?;
        if fields.len() < 4 {
            snapshot
                .warnings
                .push("Malformed checkin row skipped.".into());
            continue;
        }
        if fields[3] == "start" {
            let period = fields[4..].join(":");
            if !period.is_empty() {
                if snapshot
                    .stats_period
                    .as_ref()
                    .is_some_and(|old| old != &period)
                {
                    return Err("Multiple battery statistics periods in one snapshot".into());
                }
                snapshot.stats_period = Some(period);
            }
        }
        // AOSP BatteryStats.dumpCheckinLocked: global `bt` data field 5 is
        // getStartClockTime(). The other `bt` times are changing counters and
        // must not be used as accumulator identity. A missing/changed period
        // remains unavailable; host time is never substituted.
        if fields[0] == "9"
            && fields[1] == "0"
            && fields[2] == "l"
            && fields[3] == "bt"
            && let Some(clock) = fields
                .get(9)
                .and_then(|value| value.parse::<u64>().ok())
                .filter(|value| *value > 0)
        {
            let period = format!("android-bt-clock:{clock}");
            if battery_period.as_ref().is_some_and(|old| old != &period) {
                return Err("Conflicting battery accumulator start clocks".into());
            }
            battery_period = Some(period);
        }
        if fields[1].parse::<u32>().ok() != Some(snapshot.identity.uid) || fields[2] != "l" {
            continue;
        }
        snapshot.uid_records_present = true;
        let kind = match fields[3].as_str() {
            "wl" | "kwl" | "wfl" => Some(ResourceKind::Wakelock),
            "apk" | "wua" | "wa" => Some(ResourceKind::Alarm),
            "jb" | "job" => Some(ResourceKind::Job),
            "sr" | "sensor" => Some(ResourceKind::Sensor),
            _ => None,
        };
        let data = &fields[4..];
        if let Some(kind) = kind {
            let Some((name, duration_ms, count)) = parse_timer(&fields[3], data, legacy_timers)?
            else {
                snapshot
                    .warnings
                    .push("Unrecognized or invalid timer layout; counter omitted.".into());
                continue;
            };
            if fields[3] == "wl" && !legacy_timers {
                snapshot.warnings.push("Wakelock evidence includes partial locks only; background partial locks are a subset and are not double-counted with their parent. Full/window locks are not included in this metric.".into());
            }
            if fields[3] == "wua" {
                snapshot.warnings.push("Wakeup-alarm evidence is count-only; its zero duration is not a measured alarm execution time. APK service start times are not treated as alarm durations.".into());
            }
            let key = (kind, name.clone());
            let entry = snapshot.timers.entry(key).or_insert(ResourceTimer {
                kind,
                name,
                duration_ms: 0,
                count: 0,
            });
            entry.duration_ms = entry
                .duration_ms
                .checked_add(duration_ms)
                .ok_or("Battery timer overflow")?;
            entry.count = entry
                .count
                .checked_add(count)
                .ok_or("Battery timer count overflow")?;
        } else if fields[3] == "nt" {
            if !snapshot.network_bytes.is_empty() {
                return Err("Duplicate network counters for selected UID".into());
            }
            for (index, key) in [
                "mobile_rx",
                "mobile_tx",
                "wifi_rx",
                "wifi_tx",
                "bluetooth_rx",
                "bluetooth_tx",
            ]
            .into_iter()
            .enumerate()
            {
                snapshot.network_bytes.insert(
                    key.into(),
                    data.get(index).and_then(|value| value.parse::<u64>().ok()),
                );
            }
        }
    }
    if snapshot.stats_period.is_none() {
        snapshot.stats_period = battery_period;
    }
    snapshot.modeled_energy_mah = parse_energy(report, snapshot.identity.uid)?;
    if !snapshot.uid_records_present {
        snapshot
            .warnings
            .push("No records for selected UID; missing counters are unknown, not zero.".into());
    }
    if snapshot.stats_period.is_none() {
        snapshot.warnings.push("Battery statistics period missing; cross-snapshot cumulative difference will remain unavailable.".into());
    }
    if snapshot.identity.shared_uid {
        snapshot
            .warnings
            .push("Shared UID: resource evidence cannot be attributed to one package.".into());
    }
    snapshot.warnings.sort();
    snapshot.warnings.dedup();
    snapshot.warnings.truncate(200);
    Ok(snapshot)
}

fn parse_timer(
    kind: &str,
    data: &[String],
    legacy: bool,
) -> Result<Option<(String, u64, u64)>, String> {
    let number = |index: usize| data.get(index).and_then(|value| value.parse::<u64>().ok());
    if kind == "wl"
        && data.len() == 25
        && ["f", "p", "bp", "w"]
            .into_iter()
            .enumerate()
            .all(|(index, marker)| data[index * 6 + 2] == marker)
    {
        // printWakeLockCheckin emits duration-ms, marker, count, current,
        // maximum and unpooled duration. `bp` overlaps `p`; never sum them.
        return Ok(number(7)
            .zip(number(9))
            .map(|(duration, count)| (data[0].clone(), duration, count)));
    }
    if matches!(kind, "jb" | "job") && data.len() == 5
        || matches!(kind, "sr" | "sensor") && data.len() == 6
    {
        return Ok(number(1)
            .zip(number(2))
            .map(|(duration, count)| (data[0].clone(), duration, count)));
    }
    if kind == "wua" && data.len() == 2 {
        return Ok(number(1).map(|count| (data[0].clone(), 0, count)));
    }
    // The APK record repeats package wakeup count for each service and its time
    // is service start duration. Treating it as an alarm would overcount both.
    if !legacy || data.len() < 3 || kind == "apk" {
        return Ok(None);
    }
    let mut duration_us = 0u64;
    let mut count = 0u64;
    for section in data[1..].chunks(5) {
        let values = section
            .first()
            .and_then(|value| value.parse::<u64>().ok())
            .zip(section.get(1).and_then(|value| value.parse::<u64>().ok()));
        let Some((duration, occurrences)) = values else {
            return Ok(None);
        };
        duration_us = duration_us
            .checked_add(duration)
            .ok_or("Battery timer overflow")?;
        count = count
            .checked_add(occurrences)
            .ok_or("Battery timer count overflow")?;
    }
    Ok(Some((data[0].clone(), duration_us / 1000, count)))
}

pub fn parse_device_state(text: &str) -> Result<DeviceState, String> {
    validate(text)?;
    let values: BTreeMap<_, _> = text
        .lines()
        .filter_map(|line| line.split_once(':'))
        .map(|(key, value)| (key.trim().to_ascii_lowercase(), value.trim()))
        .collect();
    let powers: Vec<_> = [
        "ac powered",
        "usb powered",
        "wireless powered",
        "dock powered",
    ]
    .into_iter()
    .filter_map(|key| values.get(key))
    .filter_map(|value| match *value {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    })
    .collect();
    Ok(DeviceState {
        level_percent: values
            .get("level")
            .and_then(|value| value.parse::<u8>().ok())
            .filter(|value| *value <= 100),
        temperature_tenths_celsius: values
            .get("temperature")
            .and_then(|value| value.parse().ok()),
        voltage_millivolts: values.get("voltage").and_then(|value| value.parse().ok()),
        powered: (!powers.is_empty()).then(|| powers.into_iter().any(|powered| powered)),
    })
}

fn parse_energy(text: &str, uid: u32) -> Result<BTreeMap<String, f64>, String> {
    let mut energy = BTreeMap::new();
    for line in text.lines().map(str::trim) {
        let Some(line) = line
            .strip_prefix("Uid ")
            .or_else(|| line.strip_prefix("UID "))
        else {
            continue;
        };
        let Some((identity, data)) = line.split_once(':') else {
            continue;
        };
        let parsed_uid = if let Some(identity) = identity.trim().strip_prefix('u') {
            identity.split_once('a').and_then(|(user, app)| {
                Some(
                    user.parse::<u32>()
                        .ok()?
                        .checked_mul(100_000)?
                        .checked_add(10_000)?
                        .checked_add(app.parse().ok()?)?,
                )
            })
        } else {
            identity.trim().parse::<u32>().ok()
        };
        if parsed_uid != Some(uid) {
            continue;
        }
        let total = data
            .split_whitespace()
            .next()
            .and_then(|value| value.parse::<f64>().ok())
            .filter(|value| value.is_finite() && *value >= 0.0);
        if let Some(total) = total {
            if energy.insert("total".into(), total).is_some() {
                return Err("Ambiguous repeated UID modeled-energy totals".into());
            }
        }
        for token in data
            .split(|character: char| character.is_whitespace() || matches!(character, '(' | ')'))
        {
            let Some((component, value)) = token.split_once('=') else {
                continue;
            };
            if component.is_empty()
                || !component
                    .bytes()
                    .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'_' | b'-'))
            {
                continue;
            }
            if let Some(value) = value
                .parse::<f64>()
                .ok()
                .filter(|value| value.is_finite() && *value >= 0.0)
            {
                if energy.insert(component.into(), value).is_some() {
                    return Err("Duplicate UID modeled-energy component".into());
                }
            }
        }
    }
    Ok(energy)
}

fn validate(text: &str) -> Result<(), String> {
    if text.len() > MAX_INPUT_BYTES || text.lines().any(|line| line.len() > 1024 * 1024) {
        return Err("Battery input exceeds parser budget".into());
    }
    Ok(())
}

fn csv_line(line: &str) -> Result<Vec<String>, String> {
    let mut fields = Vec::new();
    let mut value = String::new();
    let mut quoted = false;
    let mut characters = line.chars().peekable();
    while let Some(character) = characters.next() {
        match character {
            '"' if quoted && characters.peek() == Some(&'"') => {
                value.push('"');
                characters.next();
            }
            '"' => quoted = !quoted,
            ',' if !quoted => {
                fields.push(std::mem::take(&mut value));
            }
            _ => value.push(character),
        }
    }
    if quoted {
        return Err("Unterminated Battery checkin CSV quote".into());
    }
    fields.push(value);
    if fields.len() > 512 {
        return Err("Battery checkin exceeds column budget".into());
    }
    Ok(fields)
}
