//! Local, review-only unified diff. Neither the model nor this generator writes source.
use tree_sitter::{Node, Parser};
const MAX_SOURCE: usize = 512 * 1024;
pub(super) struct TracePatch {
    pub marker: String,
    pub diff: String,
    pub instrumented: aps_contracts::SourceDigest,
}
struct Method {
    name: String,
    line: usize,
    open: usize,
    close: usize,
}

fn language(path: &str) -> Result<bool, String> {
    if path.len() > 512
        || path.split('/').any(|p| {
            p.is_empty()
                || p.starts_with('.')
                || !p
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
        })
    {
        return Err("Unsupported repository-relative source path.".into());
    }
    match path.rsplit('.').next() {
        Some("kt") => Ok(true),
        Some("java") => Ok(false),
        _ => Err("Select a Kotlin or Java file.".into()),
    }
}
fn indent(line: &str) -> &str {
    &line[..line.len() - line.trim_start_matches([' ', '\t']).len()]
}
fn child<'a>(n: Node<'a>, kind: &str) -> Option<Node<'a>> {
    n.named_children(&mut n.walk()).find(|c| c.kind() == kind)
}
fn candidate(
    n: Node<'_>,
    kotlin: bool,
    src: &str,
    lines: &[&str],
    allow_probe: bool,
) -> Option<Method> {
    let name = n.child_by_field_name("name")?;
    let spelling = src.get(name.byte_range())?;
    if spelling.len() > 128
        || !spelling
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_')
    {
        return None;
    }
    let body = if kotlin {
        child(child(n, "function_body")?, "block")?
    } else {
        n.child_by_field_name("body")?
    };
    let head = src.get(n.start_byte()..body.start_byte())?;
    // Leave room for the five wrapper lines even for the largest allowed input.
    // On review, keep the original source budget plus exactly the wrapper overhead.
    if kotlin
        && (head.contains('@')
            || head
                .split(|c: char| !c.is_alphanumeric() && c != '_')
                .any(|s| matches!(s, "suspend" | "inline" | "tailrec" | "external" | "expect")))
    {
        return None;
    }
    let text = src.get(body.byte_range())?;
    let open = body.start_position().row;
    let close = body.end_position().row;
    if close <= open + 1
        || close - open > if allow_probe { 133 } else { 128 }
        || text.len() > if allow_probe { 20 * 1024 } else { 16 * 1024 }
        || (!allow_probe && text.contains("APS.Frame/"))
        || text.contains("\"\"\"")
        || !text.ends_with('}')
        || lines
            .get(open)?
            .get(body.start_position().column..)?
            .trim_end()
            != "{"
        || lines.get(close)?.trim() != "}"
        || !lines[close].ends_with('\n')
        || lines[close].len() > 2048
        || lines[open].len() > 2048
        || !lines[open + 1..close].iter().any(|l| !l.trim().is_empty())
    {
        return None;
    }
    if allow_probe {
        // Line checks alone could accept a replacement that closes the try early
        // and introduces a second try. Require the original AST enclosure.
        let wrapper = child(
            body,
            if kotlin {
                "try_expression"
            } else {
                "try_statement"
            },
        )?;
        let inner = child(wrapper, "block")?;
        if close < open + 7
            || wrapper.start_position().row != open + 2
            || wrapper.end_position().row != close - 1
            || inner.start_position().row != open + 2
            || inner.end_position().row != close - 3
        {
            return None;
        }
    }
    Some(Method {
        name: spelling.to_owned(),
        line: name.start_position().row + 1,
        open,
        close,
    })
}
fn methods<'a>(
    path: &str,
    src: &'a str,
    allow_probe: bool,
) -> Result<(Vec<Method>, Vec<&'a str>), String> {
    let kotlin = language(path)?;
    if src.is_empty()
        || src.len() > MAX_SOURCE
        || src.contains('\0')
        || (kotlin && src.contains("kotlin.contracts"))
    {
        return Err("Unsupported or oversized source.".into());
    }
    let lines: Vec<_> = src.split_inclusive('\n').collect();
    if src.contains('\r')
        && lines.iter().any(|l| {
            l.trim_end_matches("\r\n").contains('\r') || l.ends_with('\n') && !l.ends_with("\r\n")
        })
    {
        return Err("Mixed line endings.".into());
    }
    let mut parser = Parser::new();
    parser
        .set_language(&if kotlin {
            tree_sitter_kotlin_ng::LANGUAGE.into()
        } else {
            tree_sitter_java::LANGUAGE.into()
        })
        .map_err(|_| "Source grammar unavailable.")?;
    let tree = parser.parse(src, None).ok_or("Could not parse source.")?;
    if tree.root_node().has_error() {
        return Err("Source contains incomplete or unsupported syntax.".into());
    }
    let mut cursor = tree.walk();
    let mut found = Vec::new();
    loop {
        let n = cursor.node();
        if n.kind()
            == if kotlin {
                "function_declaration"
            } else {
                "method_declaration"
            }
        {
            if let Some(m) = candidate(n, kotlin, src, &lines, allow_probe) {
                if found.len() == 256 {
                    return Err("Too many methods in file.".into());
                }
                found.push(m);
            }
        } else if cursor.goto_first_child() {
            continue;
        }
        loop {
            if cursor.goto_next_sibling() {
                break;
            }
            if !cursor.goto_parent() {
                return Ok((found, lines));
            }
        }
    }
}
pub(super) fn trace_methods(path: &str, src: &str) -> Result<Vec<(usize, String)>, String> {
    Ok(methods(path, src, false)?
        .0
        .into_iter()
        .map(|m| (m.line, m.name))
        .collect())
}
pub(super) fn marker(
    path: &str,
    line: usize,
    name: &str,
    hash: aps_contracts::SourceDigest,
) -> String {
    let key = format!("{path}\0{line}\0{name}\0{}", hash.to_hex());
    format!(
        "APS.Frame/{}",
        &aps_source::digest_text(&key).to_hex()[..16]
    )
}
pub(super) fn trace_patch(path: &str, src: &str, line: usize) -> Result<TracePatch, String> {
    let (list, lines) = methods(path, src, false)?;
    let mut hits = list.into_iter().filter(|m| m.line == line);
    let m = hits
        .next()
        .ok_or("Select a supported synchronous method.")?;
    if hits.next().is_some() {
        return Err("Ambiguous declaration line.".into());
    }
    let marker = marker(path, line, &m.name, aps_source::digest_text(src));
    let base = indent(lines[m.close]);
    let interior = &lines[m.open + 1..m.close];
    let unit = interior
        .iter()
        .filter(|l| !l.trim().is_empty())
        .filter_map(|l| indent(l).strip_prefix(base))
        .find(|s| !s.is_empty() && s.len() <= 8)
        .unwrap_or("    ");
    let one = format!("{base}{unit}");
    let two = format!("{one}{unit}");
    let eol = if lines[m.open].ends_with("\r\n") {
        "\r\n"
    } else {
        "\n"
    };
    let semi = if path.ends_with(".java") { ";" } else { "" };
    let n = m.close - m.open + 1;
    let mut diff = format!(
        "diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -{},{} +{},{} @@\n",
        m.open + 1,
        n,
        m.open + 1,
        n + 5
    );
    diff.push(' ');
    diff.push_str(lines[m.open]);
    let mut inserted =
        format!("{one}android.os.Trace.beginSection(\"{marker}\"){semi}{eol}{one}try {{{eol}");
    for l in interior {
        if !l.trim().is_empty() {
            inserted.push_str(unit);
        }
        inserted.push_str(l);
    }
    inserted.push_str(&format!(
        "{one}}} finally {{{eol}{two}android.os.Trace.endSection(){semi}{eol}{one}}}{eol}"
    ));
    for l in interior {
        diff.push('-');
        diff.push_str(l);
    }
    for l in inserted.split_inclusive('\n') {
        diff.push('+');
        diff.push_str(l);
    }
    let updated = format!(
        "{}{}{}",
        lines[..=m.open].concat(),
        inserted,
        lines[m.close..].concat()
    );
    edit_range(path, &updated, line, &m.name, &marker)?;
    let instrumented = aps_source::digest_text(&updated);
    diff.push(' ');
    diff.push_str(lines[m.close]);
    Ok(TracePatch {
        marker,
        diff,
        instrumented,
    })
}
/// Only the inner lines of the exact generated synchronous try/finally are
/// eligible. Reparse the proposed file and insist that the declaration and
/// outer probe boundary still enclose the replacement (never arbitrary hunks).
pub(super) fn edit_range(
    path: &str,
    src: &str,
    line: usize,
    name: &str,
    marker: &str,
) -> Result<std::ops::Range<usize>, String> {
    let (found, lines) = methods(path, src, true)?;
    let mut matches = found.iter().filter(|m| m.line == line && m.name == name);
    let m = matches
        .next()
        .ok_or("Instrumented method no longer matches.")?;
    let semi = if path.ends_with(".java") { ";" } else { "" };
    if matches.next().is_some()
        || m.close < m.open + 7
        || lines[m.open + 1].trim() != format!("android.os.Trace.beginSection(\"{marker}\"){semi}")
        || lines[m.open + 2].trim() != "try {"
        || lines[m.close - 3].trim() != "} finally {"
        || lines[m.close - 2].trim() != format!("android.os.Trace.endSection(){semi}")
        || lines[m.close - 1].trim() != "}"
    {
        return Err("Generated probe boundary does not match.".into());
    }
    Ok(m.open + 3..m.close - 3)
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        io::Write,
        process::{Command, Stdio},
    };
    #[test]
    fn reviewed_diff_applies_to_kotlin_and_java() {
        for (path, source) in [
            (
                "src/Feed.kt",
                "class Feed {\n    fun bind(i: Int): Int {\n        return i+1\n    }\n}\n"
                    .to_owned(),
            ),
            (
                "src/Feed.java",
                "class Feed {\n    int bind(int i) {\n        return i+1;\n    }\n}\n"
                    .replace('\n', "\r\n"),
            ),
        ] {
            assert_eq!(
                trace_methods(path, &source).unwrap(),
                vec![(2, "bind".to_owned())]
            );
            let p = trace_patch(path, &source, 2).unwrap();
            assert!(aps_perfetto::frame_probes::valid_marker(&p.marker));
            let t = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
            fs::create_dir(t.path().join("src")).unwrap();
            fs::write(t.path().join(path), &source).unwrap();
            for check in [true, false] {
                let mut cmd = Command::new("git");
                cmd.current_dir(t.path()).arg("apply");
                if check {
                    cmd.arg("--check");
                }
                let mut c = cmd.arg("-").stdin(Stdio::piped()).spawn().unwrap();
                c.stdin
                    .take()
                    .unwrap()
                    .write_all(p.diff.as_bytes())
                    .unwrap();
                assert!(c.wait().unwrap().success());
            }
            let after = fs::read_to_string(t.path().join(path)).unwrap();
            assert!(after.contains("} finally {"));
            assert!(trace_methods(path, &after).unwrap().is_empty());
            if path.ends_with(".java") {
                assert!(!after.replace("\r\n", "").contains('\n'));
            }
        }
    }
    #[test]
    fn rejects_unsafe_declarations_paths_and_lines() {
        let src = "fun bind() {\n    draw()\n}\n";
        for path in [
            "../A.kt",
            "/A.kt",
            "C:\\A.kt",
            ".git/X.kt",
            "bad.gradle.kts",
        ] {
            assert!(trace_patch(path, src, 1).is_err());
        }
        for modifier in ["suspend", "inline", "tailrec", "@Composable\n"] {
            assert!(trace_patch("A.kt", &format!("{modifier} {src}"), 1).is_err());
        }
        assert!(trace_patch("A.kt", src, 2).is_err());
        assert!(trace_patch("A.kt", "fun broken( {", 1).is_err());
    }
}
