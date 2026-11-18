//! A single line-oriented edit inside the ordinary finding's recommendation
//! string. Avoid double-encoding JSON; the receiver still verifies the source,
//! exact line match, citations and AST. No paths or commands in the protocol.
const OLD: &str = "<<< OLD";
const NEW: &str = "=== NEW";
const END: &str = ">>> END";

pub fn example(old: &str, new: &str) -> String {
    format!(
        "{OLD}\n{old}\n{NEW}\n{}{END}",
        if new.is_empty() {
            String::new()
        } else {
            format!("{new}\n")
        }
    )
}

pub fn parse(text: &str) -> Option<(&str, &str)> {
    if text.len() > 8192
        || text
            .chars()
            .any(|c| c.is_control() && !matches!(c, '\n' | '\t'))
    {
        return None;
    }
    let (open, body) = text.split_once('\n')?;
    let (body, close) = body.strip_suffix('\n').unwrap_or(body).rsplit_once('\n')?;
    if open != OLD || close != END {
        return None;
    }
    let (old, new) = if let Some(old) = body.strip_suffix("\n=== NEW") {
        (old, "")
    } else {
        body.split_once("\n=== NEW\n")?
    };
    if old.trim().is_empty()
        || old == new
        || [old, new]
            .iter()
            .any(|v| v.lines().any(|line| matches!(line, OLD | NEW | END)))
    {
        return None;
    }
    Some((old, new))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preserves_lines_and_rejects_multiple_edits() {
        for (a, b) in [
            ("  f();", ""),
            ("  f();\n  g();", "  g();"),
            ("  f();", "  h();\n  f();"),
        ] {
            let text = example(a, b);
            assert_eq!(parse(&text), Some((a, b)));
            assert!(parse(&format!("{text}\n{text}")).is_none());
        }
    }
}
