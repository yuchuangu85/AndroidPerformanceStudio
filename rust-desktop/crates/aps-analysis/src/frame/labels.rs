//! Minimize untrusted display labels without changing private frame grouping.
use aps_frame::FrameSample;
use std::collections::HashMap;

pub const MAX_LABEL_BYTES: usize = 512;
pub const REDACTED_REASON: &str = "";
const PRIVATE_CONTEXT_PREFIX: char = '\0';

pub(super) fn bounded(text: &str) -> bool {
    !text.trim().is_empty()
        && text.len() <= MAX_LABEL_BYTES
        && !text.chars().any(|c| {
            c.is_control() || matches!(c, '\u{200e}'..='\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}')
        })
}

/// A narrow path-shape guard, not a guarantee that an allowed label is public.
/// Only exact flattened Android components may contain a literal slash.
pub fn public(text: &str, allow_component: bool) -> bool {
    bounded(text) && (!path_shaped(text) || (allow_component && android_component(text)))
}

fn path_shaped(text: &str) -> bool {
    if text.contains(['/', '\\']) {
        return true;
    }
    let lower = text.to_ascii_lowercase();
    if lower.contains("%2f") || lower.contains("%5c") {
        return true;
    }
    lower
        .split(|c: char| c.is_whitespace() || matches!(c, '"' | '\'' | '(' | '[' | '=' | '<'))
        .any(|token| {
            let bytes = token.as_bytes();
            matches!(bytes, [b'a'..=b'z', b':', ..]) && bytes.len() > 2
                || token.starts_with("file:")
                || token.starts_with("content:")
        })
}

fn android_component(text: &str) -> bool {
    fn qualified(name: &str) -> bool {
        name.split('.').all(|part| {
            matches!(part.as_bytes(), [b'a'..=b'z' | b'A'..=b'Z' | b'_' | b'$', tail @ ..]
                if tail.iter().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'$')))
        })
    }
    let Some((package, class)) = text.split_once('/') else {
        return false;
    };
    // A fully qualified right-hand class must itself be dotted. This avoids
    // passing common two-segment relative paths as component names.
    package.contains('.')
        && !package.contains('$')
        && qualified(package)
        && (class.starts_with('.') || class.contains('.'))
        && qualified(class.strip_prefix('.').unwrap_or(class))
        && class
            .rsplit('.')
            .next()
            .is_some_and(|name| name.as_bytes().first().is_some_and(u8::is_ascii_uppercase))
}

/// Retain context equality before running the analyzer. Simply dropping two
/// different unsafe window values to None would join unrelated frame clusters.
/// The internal token is never a query label or emitted in a tool result.
pub(super) fn normalize_contexts(frames: &mut [FrameSample]) {
    let mut ids = HashMap::new();
    for sample in frames {
        for context in [&mut sample.window_id, &mut sample.activity_name] {
            if let Some(label) = context.as_mut()
                && !public(label, true)
            {
                let next = ids.len();
                let id = ids.entry(std::mem::take(label)).or_insert(next);
                *label = format!("{PRIVATE_CONTEXT_PREFIX}{id}");
            }
        }
    }
}

pub fn visible_context(label: &str) -> Option<&str> {
    (!label.starts_with(PRIVATE_CONTEXT_PREFIX)).then_some(label)
}
