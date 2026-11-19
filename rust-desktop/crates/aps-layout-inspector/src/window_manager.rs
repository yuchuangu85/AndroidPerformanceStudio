//! Bounded, exact-name WindowManager type evidence for visible-window ZIPs.

#[cfg(test)]
mod tests;

use aps_platform::CancellationToken;
use std::collections::BTreeMap;

pub(crate) const MAX_DUMP_BYTES: usize = 4 * 1024 * 1024;
const MAX_RECORDS: usize = 1024;
const MAX_NAME_BYTES: usize = 4096;
const FIRST_SYSTEM_WINDOW: u32 = 2000;

#[derive(Debug, Default, PartialEq, Eq)]
pub(crate) struct WindowTypes {
    // Unknowns remain present so a later duplicate cannot turn ambiguity into
    // positive evidence. Keys are WindowState.getName(), not protocol ids.
    types: BTreeMap<String, Option<u32>>,
}

impl WindowTypes {
    pub fn parse(bytes: &[u8], cancel: &CancellationToken) -> Result<Self, String> {
        check_cancel(cancel)?;
        if bytes.len() > MAX_DUMP_BYTES {
            return Err("WindowManager dump exceeds input limit".into());
        }
        let text = std::str::from_utf8(bytes)
            .map_err(|_| "WindowManager dump is not valid UTF-8".to_string())?;
        let mut result = Self::default();
        let mut current: Option<Record> = None;
        let mut records = 0;
        for line in text.lines() {
            check_cancel(cancel)?;
            let content = line.trim_start_matches(' ');
            let indent = line.len() - content.len();
            if content.is_empty() {
                continue;
            }
            // AOSP's window listing uses two spaces. Also accept an unindented
            // listing, but never nested window descriptions inside a record.
            if indent <= 2 && content.starts_with("Window #") {
                result.finish(current.take());
                records += 1;
                if records > MAX_RECORDS {
                    return Err("WindowManager dump has too many window records".into());
                }
                current = Some(Record::new(content, indent)?);
            } else if let Some(record) = current.as_mut() {
                if indent <= record.indent {
                    result.finish(current.take());
                } else {
                    record.consume(content, indent);
                }
            }
        }
        result.finish(current);
        check_cancel(cancel)?;
        Ok(result)
    }

    /// Keep only unchanged system types observed under the same exact ZIP name.
    pub fn stable_system_windows(&self, after: &Self) -> Self {
        Self {
            types: self
                .types
                .iter()
                .filter_map(|(name, window_type)| {
                    let window_type = (*window_type)?;
                    (window_type >= FIRST_SYSTEM_WINDOW
                        && after.types.get(name) == Some(&Some(window_type)))
                    .then(|| (name.clone(), Some(window_type)))
                })
                .collect(),
        }
    }

    pub fn system_type(&self, exact_dump_entry_name: &str) -> Option<u32> {
        self.types
            .get(exact_dump_entry_name)
            .copied()
            .flatten()
            .filter(|window_type| *window_type >= FIRST_SYSTEM_WINDOW)
    }

    fn finish(&mut self, record: Option<Record>) {
        if let Some(record) = record {
            let window_type = record.window_type();
            self.types
                .entry(record.name)
                .and_modify(|existing| *existing = None)
                .or_insert(window_type);
        }
    }
}

struct Record {
    name: String,
    indent: usize,
    eligible: bool,
    attributes: Option<Attributes>,
}

impl Record {
    fn new(header: &str, indent: usize) -> Result<Self, String> {
        let invalid = || "WindowManager window record header is malformed".to_string();
        let header = header.strip_prefix("Window #").ok_or_else(invalid)?;
        let (number, window) = header.split_once(" Window{").ok_or_else(invalid)?;
        if !decimal(number) {
            return Err(invalid());
        }
        let window = window.strip_suffix("}:").ok_or_else(invalid)?;
        let (hash, rest) = window.split_once(' ').ok_or_else(invalid)?;
        let (user, title) = rest.split_once(' ').ok_or_else(invalid)?;
        if hash.is_empty()
            || !hash.bytes().all(|byte| byte.is_ascii_hexdigit())
            || !user.strip_prefix('u').is_some_and(decimal)
            || title.is_empty()
            || title.chars().any(char::is_control)
        {
            return Err(invalid());
        }
        // WindowState.toString() adds this marker; getName() does not. Exiting
        // windows cannot establish a positive match, including across duplicates.
        let (title, eligible) = match title.strip_suffix(" EXITING") {
            Some(title) => (title, false),
            None => (title, true),
        };
        let name_bytes = hash.len() + 1 + title.len();
        if name_bytes > MAX_NAME_BYTES {
            return Err("WindowManager window name exceeds input limit".into());
        }
        Ok(Self {
            name: format!("{hash} {title}"),
            indent,
            eligible,
            attributes: None,
        })
    }

    fn consume(&mut self, content: &str, indent: usize) {
        let own_indent = self.indent + 2;
        if let Some(attributes) = self.attributes.as_mut()
            && !attributes.closed
        {
            if indent > own_indent {
                attributes.consume(content);
                return;
            }
            self.eligible = false;
        }
        if indent == own_indent
            && let Some(value) = content.strip_prefix("mAttrs=")
        {
            if self.attributes.is_some() {
                self.eligible = false;
            }
            self.attributes = Some(Attributes::new(value));
        }
    }

    fn window_type(&self) -> Option<u32> {
        let attributes = self.attributes.as_ref()?;
        (self.eligible && attributes.valid && attributes.closed && attributes.type_fields == 1)
            .then_some(attributes.window_type)
            .flatten()
    }
}

struct Attributes {
    depth: usize,
    closed: bool,
    valid: bool,
    type_fields: usize,
    window_type: Option<u32>,
}

impl Attributes {
    fn new(value: &str) -> Self {
        let mut attributes = Self {
            depth: 0,
            closed: false,
            valid: value.starts_with('{'),
            type_fields: 0,
            window_type: None,
        };
        attributes.consume(value);
        attributes
    }

    fn consume(&mut self, content: &str) {
        let bytes = content.as_bytes();
        let mut offset = 0;
        while offset < bytes.len() {
            match bytes[offset] {
                b'{' => self.depth += 1,
                b'}' => {
                    if self.depth == 0 {
                        self.valid = false;
                        return;
                    }
                    self.depth -= 1;
                    if self.depth == 0 {
                        self.closed = true;
                        self.valid &= content[offset + 1..].trim().is_empty();
                        return;
                    }
                }
                _ => {}
            }
            if self.depth == 1
                && (offset == 0
                    || bytes[offset - 1].is_ascii_whitespace()
                    || bytes[offset - 1] == b'{')
                && bytes[offset..].starts_with(b"ty=")
            {
                let start = offset + 3;
                let end = bytes[start..]
                    .iter()
                    .position(|byte| byte.is_ascii_whitespace() || matches!(byte, b'{' | b'}'))
                    .map_or(bytes.len(), |length| start + length);
                self.type_fields += 1;
                self.window_type = parse_type(&content[start..end]);
                self.valid &= self.window_type.is_some();
                offset = end;
                continue;
            }
            offset += 1;
        }
    }
}

fn decimal(value: &str) -> bool {
    !value.is_empty() && value.bytes().all(|byte| byte.is_ascii_digit())
}

fn parse_type(value: &str) -> Option<u32> {
    if decimal(value) {
        return value
            .parse::<u32>()
            .ok()
            .filter(|window_type| *window_type <= i32::MAX as u32);
    }
    if let Some(hex) = value
        .strip_prefix("0x")
        .or_else(|| value.strip_prefix("0X"))
    {
        return (!hex.is_empty() && hex.bytes().all(|byte| byte.is_ascii_hexdigit()))
            .then(|| u32::from_str_radix(hex, 16).ok())
            .flatten()
            .filter(|window_type| *window_type <= i32::MAX as u32);
    }
    // Explicit ViewDebug.IntToString mapping on LayoutParams.type in AOSP
    // WindowManager.java (blob 101d5c950b71344017d72112c759401185a88b80).
    // Numeric OEM values must fit a nonnegative Java int, but are not limited
    // by LAST_SYSTEM_WINDOW. Unknown symbols cannot be inferred from sequence
    // or from a window's title/package.
    Some(match value {
        "BASE_APPLICATION" => 1,
        "APPLICATION" => 2,
        "APPLICATION_STARTING" => 3,
        "DRAWN_APPLICATION" => 4,
        "APPLICATION_PANEL" => 1000,
        "APPLICATION_MEDIA" => 1001,
        "APPLICATION_SUB_PANEL" => 1002,
        "APPLICATION_ATTACHED_DIALOG" => 1003,
        "APPLICATION_MEDIA_OVERLAY" => 1004,
        "APPLICATION_ABOVE_SUB_PANEL" => 1005,
        "STATUS_BAR" => 2000,
        "SEARCH_BAR" => 2001,
        "PHONE" => 2002,
        "SYSTEM_ALERT" => 2003,
        "KEYGUARD" => 2004,
        "TOAST" => 2005,
        "SYSTEM_OVERLAY" => 2006,
        "PRIORITY_PHONE" => 2007,
        "SYSTEM_DIALOG" => 2008,
        "KEYGUARD_DIALOG" => 2009,
        "SYSTEM_ERROR" => 2010,
        "INPUT_METHOD" => 2011,
        "INPUT_METHOD_DIALOG" => 2012,
        "WALLPAPER" => 2013,
        "STATUS_BAR_PANEL" => 2014,
        "SECURE_SYSTEM_OVERLAY" => 2015,
        "DRAG" => 2016,
        "STATUS_BAR_SUB_PANEL" => 2017,
        "POINTER" => 2018,
        "NAVIGATION_BAR" => 2019,
        "VOLUME_OVERLAY" => 2020,
        "BOOT_PROGRESS" => 2021,
        "INPUT_CONSUMER" => 2022,
        "NAVIGATION_BAR_PANEL" => 2024,
        "DISPLAY_OVERLAY" => 2026,
        "MAGNIFICATION_OVERLAY" => 2027,
        "PRIVATE_PRESENTATION" => 2030,
        "VOICE_INTERACTION" => 2031,
        "ACCESSIBILITY_OVERLAY" => 2032,
        "VOICE_INTERACTION_STARTING" => 2033,
        "DOCK_DIVIDER" => 2034,
        "QS_DIALOG" => 2035,
        "SCREENSHOT" => 2036,
        "PRESENTATION" => 2037,
        "APPLICATION_OVERLAY" => 2038,
        "ACCESSIBILITY_MAGNIFICATION_OVERLAY" => 2039,
        "NOTIFICATION_SHADE" => 2040,
        "STATUS_BAR_ADDITIONAL" => 2041,
        _ => return None,
    })
}

fn check_cancel(cancel: &CancellationToken) -> Result<(), String> {
    if cancel.is_cancelled() {
        Err("WindowManager dump parsing cancelled".into())
    } else {
        Ok(())
    }
}
