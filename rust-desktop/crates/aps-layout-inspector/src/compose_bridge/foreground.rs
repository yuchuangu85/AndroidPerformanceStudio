//! Read-only foreground discovery for the explicitly requested Compose preflight.
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::{collections::BTreeSet, path::Path, time::Duration};

#[cfg(test)]
mod tests;

#[derive(Debug)]
pub(super) struct Target {
    pub package: String,
    pub pid: u32,
}

impl Target {
    pub fn resolve(adb: &Path, serial: &str, cancel: &CancellationToken) -> Result<Self, String> {
        aps_adb::validate_serial(serial).map_err(|error| error.to_string())?;
        let run = |args: &[&str]| -> Result<Vec<u8>, String> {
            let mut request = ProcessRequest::new(adb).args(["-s", serial]).args(args);
            request.timeout = Duration::from_secs(30);
            request.stdout_limit = 4 * 1024 * 1024;
            request.stderr_limit = 64 * 1024;
            let output = ProcessRunner
                .run(&request, cancel)
                .map_err(|error| error.to_string())?;
            if !output.status.success() {
                return Err(format!(
                    "Foreground app discovery failed (exit {:?}); keep the app open and retry",
                    output.status.code()
                ));
            }
            Ok(output.stdout)
        };
        let activities = ["shell", "dumpsys", "activity", "activities"];
        let before = run(&activities)?;
        let package = crate::compatible::foreground_package(&before)?;
        // Match the helper's pidof(package) target contract, not the first PID
        // in the device process table (which could belong to a background app).
        let output = run(&["shell", "pidof", &package])?;
        let pid = parse_pid(&output, &before, &package)?;
        let after = run(&activities)?;
        if crate::compatible::foreground_package(&after)? != package
            || parse_pid(&output, &after, &package)? != pid
        {
            return Err(
                "Foreground app changed; retry Compose inspection for the current app".into(),
            );
        }
        Ok(Self { package, pid })
    }

    pub fn validate(&self, package: &str, pid: u32) -> Result<(), String> {
        if self.package != package || self.pid != pid {
            return Err("Foreground app or process changed; enable Compose inspection again to authorize the current app".into());
        }
        Ok(())
    }
}

fn parse_pid(output: &[u8], activities: &[u8], package: &str) -> Result<u32, String> {
    let text = std::str::from_utf8(output).map_err(|_| "Invalid foreground app PID encoding")?;
    let pids: BTreeSet<u32> = text
        .split_whitespace()
        .map(|value| {
            value
                .parse::<u32>()
                .ok()
                .filter(|pid| *pid > 0 && *pid <= i32::MAX as u32)
                .ok_or("Invalid foreground app PID")
        })
        .collect::<Result<_, _>>()?;
    // Work-profile/cloned copies may share the same process name. Resolve the
    // focused ActivityRecord's owning process rather than asking for a PID or
    // choosing the first (potentially background-profile) result.
    if let Some(pid) = focused_activity_pid(activities, package)? {
        return if pids.contains(&pid) {
            Ok(pid)
        } else {
            Err("Foreground activity process is no longer available or is unsupported by the Compose helper; keep the app open and retry".into())
        };
    }
    if pids.len() == 1 {
        return Ok(*pids.first().expect("one validated PID"));
    }
    Err("Cannot determine a unique running process for the foreground app; keep the app open and retry Compose inspection".into())
}

fn focused_activity_pid(activities: &[u8], package: &str) -> Result<Option<u32>, String> {
    let text =
        std::str::from_utf8(activities).map_err(|_| "Invalid foreground activity encoding")?;
    let Some(record_id) = ["mFocusedApp", "topResumedActivity", "mResumedActivity"]
        .into_iter()
        .find_map(|marker| {
            text.lines()
                .filter(|line| line.contains(marker))
                .find_map(|line| {
                    let mut record = line.split_once("ActivityRecord{")?.1.split_whitespace();
                    let id = record.next()?;
                    let _user = record.next()?;
                    (record.next()?.split_once('/')?.0 == package).then_some(id)
                })
        })
    else {
        return Ok(None);
    };
    let mut in_record = false;
    let mut found_record = false;
    for line in text.lines() {
        if line.contains("ActivityRecord{") {
            in_record = line.contains("* Hist")
                && line
                    .split_once("ActivityRecord{")
                    .expect("checked record prefix")
                    .1
                    .split_whitespace()
                    .next()
                    == Some(record_id);
            found_record |= in_record;
        } else if in_record {
            let line = line.trim();
            if let Some(process) = line
                .strip_prefix("app=ProcessRecord{")
                .or_else(|| line.strip_prefix("mApp=ProcessRecord{"))
            {
                let pid = process
                    .split_whitespace()
                    .nth(1)
                    .and_then(|value| value.split_once(':'))
                    .and_then(|(pid, _)| pid.parse::<u32>().ok())
                    .filter(|pid| *pid > 0 && *pid <= i32::MAX as u32);
                return pid.map(Some).ok_or_else(|| "Foreground activity has no valid running process; keep the app open and retry".into());
            }
        }
    }
    if found_record {
        // app=null, missing or malformed owners must not fall back to another
        // profile's sole PID. Only an unsupported history format may fall back.
        Err("Foreground activity has no running process; keep the app open and retry".into())
    } else {
        Ok(None)
    }
}
