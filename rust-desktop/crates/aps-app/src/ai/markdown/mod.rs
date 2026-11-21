//! Safe, display-only Markdown for assistant answers.
//!
//! This is a bounded port of the Compose DshMarkdownParser / DshMarkdownInline
//! pair. It is a subset, not CommonMark: no HTML execution, no URI navigation,
//! no remote content and no I/O. Anything the parser does not understand stays
//! literal text.

mod inline;
mod render;

pub use inline::{InlineStyle, parse_inline};
pub use render::render_safe_text;

/// Maximum container nesting (quote/list) before the rest stays a paragraph.
pub const MAX_BLOCK_DEPTH: usize = 12;
/// Maximum inline emphasis/link nesting.
pub const MAX_INLINE_DEPTH: usize = 12;
/// Maximum input characters parsed as Markdown.
pub const MAX_MARKDOWN_CHARS: usize = 64 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Block {
    Paragraph(String),
    Heading {
        level: u8,
        text: String,
    },
    Code {
        text: String,
        language: String,
    },
    Quote(Vec<Block>),
    List(ListBlock),
    Table {
        headers: Vec<String>,
        rows: Vec<Vec<String>>,
        alignments: Vec<Alignment>,
    },
    Rule,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListBlock {
    pub ordered: bool,
    pub start: usize,
    pub items: Vec<ListItem>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListItem {
    pub blocks: Vec<Block>,
    pub checked: Option<bool>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Alignment {
    Left,
    Center,
    Right,
}

/// Parse one assistant answer into display blocks.
pub fn parse_markdown(text: &str) -> Vec<Block> {
    if text.chars().count() > MAX_MARKDOWN_CHARS {
        return vec![Block::Paragraph(text.to_owned())];
    }
    let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<String> = normalized.split('\n').map(str::to_owned).collect();
    parse_blocks(&lines, 0)
}

fn parse_blocks(lines: &[String], depth: usize) -> Vec<Block> {
    let mut out = Vec::new();
    let mut index = 0;
    while index < lines.len() {
        let line = &lines[index];
        if line.trim().is_empty() {
            index += 1;
            continue;
        }
        if depth >= MAX_BLOCK_DEPTH {
            out.push(Block::Paragraph(lines[index..].join("\n")));
            break;
        }
        if let Some(fence) = parse_fence(line) {
            let (text, next) = read_fence(lines, index, &fence);
            out.push(Block::Code {
                text,
                language: fence.language,
            });
            index = next;
            continue;
        }
        if indent(line) >= 4 {
            let (text, next) = read_indented_code(lines, index);
            out.push(Block::Code {
                text,
                language: String::new(),
            });
            index = next;
            continue;
        }
        if let Some((level, text)) = parse_heading(line) {
            out.push(Block::Heading { level, text });
            index += 1;
            continue;
        }
        if is_rule(line) {
            out.push(Block::Rule);
            index += 1;
            continue;
        }
        if quote_content(line).is_some() {
            let (content, next) = read_quote(lines, index);
            out.push(Block::Quote(parse_blocks(&content, depth + 1)));
            index = next;
            continue;
        }
        if parse_list_marker(line).is_some() {
            let (block, next) = read_list(lines, index, depth);
            out.push(block);
            index = next;
            continue;
        }
        if table_alignments(lines, index).is_some() {
            let (block, next) = read_table(lines, index);
            out.push(block);
            index = next;
            continue;
        }
        // Paragraph; a Setext underline turns the collected lines into a heading.
        let mut content = vec![lines[index].clone()];
        index += 1;
        let mut heading: Option<(u8, String)> = None;
        while index < lines.len() && !lines[index].trim().is_empty() {
            if let Some(level) = parse_setext(&lines[index]) {
                heading = Some((level, paragraph_text(&content)));
                index += 1;
                break;
            }
            if starts_block(lines, index) {
                break;
            }
            content.push(lines[index].clone());
            index += 1;
        }
        match heading {
            Some((level, text)) => out.push(Block::Heading { level, text }),
            None => out.push(Block::Paragraph(paragraph_text(&content))),
        }
    }
    out
}

fn read_fence(lines: &[String], start: usize, fence: &Fence) -> (String, usize) {
    let mut content = Vec::new();
    let mut index = start + 1;
    while index < lines.len() {
        let line = &lines[index];
        let candidate = line.trim_start_matches(' ');
        let length = candidate.chars().take_while(|c| *c == fence.marker).count();
        if indent(line) <= 3 && length >= fence.length && candidate[length..].trim().is_empty() {
            index += 1;
            break;
        }
        content.push(remove_indent(line, fence.indent));
        index += 1;
    }
    (content.join("\n"), index)
}

fn read_indented_code(lines: &[String], start: usize) -> (String, usize) {
    let mut content: Vec<String> = Vec::new();
    let mut end = 0;
    let mut index = start;
    while index < lines.len() && (lines[index].trim().is_empty() || indent(&lines[index]) >= 4) {
        content.push(remove_indent(&lines[index], 4));
        if !lines[index].trim().is_empty() {
            end = content.len();
        }
        index += 1;
    }
    content.truncate(end);
    (content.join("\n"), index)
}

fn read_quote(lines: &[String], start: usize) -> (Vec<String>, usize) {
    let mut content = Vec::new();
    let mut index = start;
    while index < lines.len() {
        let Some(value) = quote_content(&lines[index]) else {
            break;
        };
        content.push(value);
        index += 1;
    }
    (content, index)
}

fn read_list(lines: &[String], start: usize, depth: usize) -> (Block, usize) {
    let Some(first) = parse_list_marker(&lines[start]) else {
        return (Block::Paragraph(lines[start].clone()), start + 1);
    };
    let mut items = Vec::new();
    let mut index = start;
    while index < lines.len() {
        let Some(marker) = parse_list_marker(&lines[index]) else {
            break;
        };
        if marker.indent != first.indent || marker.kind != first.kind {
            break;
        }
        let task = parse_task(&marker.text);
        let mut content = vec![
            task.as_ref()
                .map(|(_, rest)| rest.clone())
                .unwrap_or_else(|| marker.text.clone()),
        ];
        index += 1;
        let mut after_blank = false;
        while index < lines.len() {
            let line = &lines[index];
            if line.trim().is_empty() {
                content.push(String::new());
                after_blank = true;
                index += 1;
            } else if indent(line) >= marker.content_indent {
                content.push(remove_indent(line, marker.content_indent));
                after_blank = false;
                index += 1;
            } else if !after_blank
                && !content.last().is_some_and(|line| line.trim().is_empty())
                && !starts_block(lines, index)
                && parse_list_marker(line).is_none()
            {
                // Lazy continuation is limited to an item's ordinary paragraph.
                if content.iter().any(|line| {
                    parse_fence(line).is_some()
                        || quote_content(line).is_some()
                        || parse_list_marker(line).is_some()
                }) {
                    break;
                }
                content.push(line.clone());
                index += 1;
            } else {
                break;
            }
        }
        while content.last().is_some_and(|line| line.trim().is_empty()) {
            content.pop();
        }
        items.push(ListItem {
            blocks: parse_blocks(&content, depth + 1),
            checked: task.map(|(checked, _)| checked),
        });
    }
    (
        Block::List(ListBlock {
            ordered: first.ordered,
            start: first.start,
            items,
        }),
        index,
    )
}

fn read_table(lines: &[String], start: usize) -> (Block, usize) {
    let alignments = table_alignments(lines, start).unwrap_or_default();
    let headers = table_cells(&lines[start]).0;
    let mut rows = Vec::new();
    let mut index = start + 2;
    while index < lines.len() && !lines[index].trim().is_empty() {
        // An unpiped short row is valid, but a new block ends the table.
        if indent(&lines[index]) >= 4 || starts_block(lines, index) {
            break;
        }
        let cells = table_cells(&lines[index]).0;
        rows.push(
            (0..headers.len())
                .map(|column| cells.get(column).cloned().unwrap_or_default())
                .collect(),
        );
        index += 1;
    }
    (
        Block::Table {
            headers,
            rows,
            alignments,
        },
        index,
    )
}

fn starts_block(lines: &[String], at: usize) -> bool {
    let line = &lines[at];
    parse_fence(line).is_some()
        || parse_heading(line).is_some()
        || quote_content(line).is_some()
        || is_rule(line)
        || parse_list_marker(line).is_some()
        || table_alignments(lines, at).is_some()
}

fn table_alignments(lines: &[String], at: usize) -> Option<Vec<Alignment>> {
    if at + 1 >= lines.len() || indent(&lines[at]) >= 4 || indent(&lines[at + 1]) >= 4 {
        return None;
    }
    let (header, header_pipe) = table_cells(&lines[at]);
    let (delimiter, delimiter_pipe) = table_cells(&lines[at + 1]);
    if (!header_pipe && !delimiter_pipe) || header.len() != delimiter.len() {
        return None;
    }
    if delimiter.iter().any(|cell| !is_table_delimiter(cell)) {
        return None;
    }
    Some(
        delimiter
            .iter()
            .map(|cell| {
                if cell.starts_with(':') && cell.ends_with(':') {
                    Alignment::Center
                } else if cell.ends_with(':') {
                    Alignment::Right
                } else {
                    Alignment::Left
                }
            })
            .collect(),
    )
}

#[derive(Debug, Clone)]
struct Fence {
    marker: char,
    length: usize,
    language: String,
    indent: usize,
}

fn parse_fence(line: &str) -> Option<Fence> {
    let indent = line.chars().take_while(|c| *c == ' ').count();
    if indent > 3 {
        return None;
    }
    let content = &line[indent..];
    let marker = content.chars().next()?;
    if marker != '\u{0060}' && marker != '~' {
        return None;
    }
    let length = content.chars().take_while(|c| *c == marker).count();
    if length < 3 {
        return None;
    }
    let info = content[length..].trim();
    if marker == '\u{0060}' && info.contains('\u{0060}') {
        return None;
    }
    let language = info
        .split_whitespace()
        .next()
        .unwrap_or_default()
        .to_owned();
    Some(Fence {
        marker,
        length,
        language,
        indent,
    })
}

fn parse_heading(line: &str) -> Option<(u8, String)> {
    let indent = line.chars().take_while(|c| *c == ' ').count();
    if indent > 3 {
        return None;
    }
    let content = &line[indent..];
    let level = content.chars().take_while(|c| *c == '#').count();
    if !(1..=6).contains(&level) {
        return None;
    }
    let rest = &content[level..];
    if !rest.is_empty() && !rest.starts_with([' ', '\t']) {
        return None;
    }
    Some((level as u8, heading_text(rest)))
}

fn heading_text(raw: &str) -> String {
    let trimmed = raw.trim();
    let stripped = trimmed.trim_end_matches('#');
    if stripped.len() != trimmed.len() {
        let without = stripped.trim_end();
        if stripped.ends_with([' ', '\t']) {
            return without.to_owned();
        }
    }
    trimmed.to_owned()
}

fn is_rule(line: &str) -> bool {
    if indent(line) > 3 {
        return false;
    }
    let compact: Vec<char> = line.chars().filter(|c| *c != ' ' && *c != '\t').collect();
    compact.len() >= 3
        && matches!(compact.first(), Some('-' | '*' | '_'))
        && compact.iter().all(|c| *c == compact[0])
}

fn parse_setext(line: &str) -> Option<u8> {
    let indent = line.chars().take_while(|c| *c == ' ').count();
    if indent > 3 {
        return None;
    }
    let rest = line[indent..].trim_end_matches([' ', '\t']);
    let first = rest.chars().next()?;
    if first != '=' && first != '-' {
        return None;
    }
    if !rest.chars().all(|c| c == first) {
        return None;
    }
    Some(if first == '=' { 1 } else { 2 })
}

fn quote_content(line: &str) -> Option<String> {
    let indent = line.chars().take_while(|c| *c == ' ').count();
    if indent > 3 {
        return None;
    }
    let rest = &line[indent..];
    let mut chars = rest.chars();
    if chars.next()? != '>' {
        return None;
    }
    let remainder = chars.as_str();
    Some(match remainder.chars().next() {
        Some(' ' | '\t') => remainder[1..].to_owned(),
        _ => remainder.to_owned(),
    })
}

#[derive(Debug, Clone)]
struct ListMarker {
    indent: usize,
    content_indent: usize,
    kind: char,
    ordered: bool,
    start: usize,
    text: String,
}

fn parse_list_marker(line: &str) -> Option<ListMarker> {
    if is_rule(line) {
        return None;
    }
    let leading = line.chars().take_while(|c| *c == ' ').count();
    if leading > 3 {
        return None;
    }
    let rest = &line[leading..];
    let mut chars = rest.chars();
    let first = chars.next()?;
    let (marker_len, ordered) = if first.is_ascii_digit() {
        let digits = rest.chars().take_while(|c| c.is_ascii_digit()).count();
        if digits == 0 || digits > 9 {
            return None;
        }
        let suffix = rest.chars().nth(digits)?;
        if suffix != '.' && suffix != ')' {
            return None;
        }
        (digits + 1, true)
    } else if matches!(first, '-' | '+' | '*') {
        (1, false)
    } else {
        return None;
    };
    let after = &rest[marker_len..];
    if !after.is_empty() && !after.starts_with([' ', '\t']) {
        return None;
    }
    let prefix = leading + marker_len;
    let padding = {
        let value = indent(&line[prefix..]);
        if (1..=4).contains(&value) { value } else { 1 }
    };
    let marker = &rest[..marker_len];
    let ordered_start = if ordered {
        marker[..marker.len() - 1].parse::<usize>().unwrap_or(1)
    } else {
        1
    };
    Some(ListMarker {
        indent: leading,
        content_indent: prefix + padding,
        kind: marker.chars().last().unwrap_or('-'),
        ordered,
        start: ordered_start,
        text: remove_indent(&line[prefix..], padding),
    })
}

fn parse_task(text: &str) -> Option<(bool, String)> {
    let mut chars = text.chars();
    if chars.next()? != '[' {
        return None;
    }
    let state = chars.next()?;
    if !matches!(state, ' ' | 'x' | 'X') {
        return None;
    }
    if chars.next()? != ']' {
        return None;
    }
    if chars.next()? != ' ' {
        return None;
    }
    Some((state != ' ', chars.as_str().to_owned()))
}

fn is_table_delimiter(cell: &str) -> bool {
    let trimmed = cell.trim();
    let trimmed = trimmed.strip_prefix(':').unwrap_or(trimmed);
    let trimmed = trimmed.strip_suffix(':').unwrap_or(trimmed);
    trimmed.len() >= 3 && trimmed.chars().all(|c| c == '-')
}

fn table_cells(line: &str) -> (Vec<String>, bool) {
    let text: Vec<char> = line.trim().chars().collect();
    // Match exact backtick run lengths; unmatched ticks must not hide real separators.
    let mut runs: Vec<(usize, usize)> = Vec::new();
    let mut index = 0;
    while index < text.len() {
        if text[index] == '\u{0060}' {
            let start = index;
            while index < text.len() && text[index] == '\u{0060}' {
                index += 1;
            }
            runs.push((start, index));
        } else {
            index += 1;
        }
    }
    let mut closing = std::collections::BTreeMap::new();
    let mut next_by_length: std::collections::BTreeMap<usize, usize> =
        std::collections::BTreeMap::new();
    for (start, end) in runs.iter().rev() {
        let length = end - start;
        if let Some(next) = next_by_length.get(&length) {
            closing.insert(*start, *next);
        }
        next_by_length.insert(length, *start);
    }
    let mut cells = Vec::new();
    let mut cell = String::new();
    let mut index = 0;
    let mut has_pipe = false;
    while index < text.len() {
        let character = text[index];
        match character {
            '\\' if index + 1 < text.len() => {
                cell.push(character);
                cell.push(text[index + 1]);
                index += 2;
            }
            '\u{0060}' => match closing.get(&index) {
                Some(end) => {
                    cell.extend(&text[index..=*end]);
                    index = *end + 1;
                }
                None => {
                    cell.push(character);
                    index += 1;
                }
            },
            '|' => {
                cells.push(cell.trim().to_owned());
                cell.clear();
                has_pipe = true;
                index += 1;
            }
            _ => {
                cell.push(character);
                index += 1;
            }
        }
    }
    cells.push(cell.trim().to_owned());
    if has_pipe && cells.first().is_some_and(|cell| cell.is_empty()) {
        cells.remove(0);
    }
    if has_pipe && cells.last().is_some_and(|cell| cell.is_empty()) {
        cells.pop();
    }
    (cells, has_pipe)
}

fn indent(line: &str) -> usize {
    let mut columns = 0;
    for character in line.chars() {
        match character {
            ' ' => columns += 1,
            '\t' => columns += 4 - columns % 4,
            _ => break,
        }
    }
    columns
}

fn remove_indent(line: &str, count: usize) -> String {
    let mut index = 0;
    let mut columns = 0;
    for character in line.chars() {
        if columns >= count {
            break;
        }
        match character {
            ' ' => columns += 1,
            '\t' => columns += 4 - columns % 4,
            _ => break,
        }
        index += character.len_utf8();
    }
    let mut output = " ".repeat(columns.saturating_sub(count));
    output.push_str(&line[index..]);
    output
}

fn paragraph_text(lines: &[String]) -> String {
    let mut output = String::new();
    for (index, line) in lines.iter().enumerate() {
        output.push_str(line.trim());
        if index < lines.len() - 1 {
            output.push(if line.ends_with("  ") { '\n' } else { ' ' });
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_paragraphs_headings_rules_and_fences() {
        let blocks = parse_markdown(
            "Intro line.\ncontinued\n\n# Title\n\n---\n\n~~~rust\nfn main() {}\n~~~\n",
        );
        assert_eq!(blocks.len(), 4, "{blocks:?}");
        assert_eq!(blocks[0], Block::Paragraph("Intro line. continued".into()));
        assert_eq!(
            blocks[1],
            Block::Heading {
                level: 1,
                text: "Title".into()
            }
        );
        assert_eq!(blocks[2], Block::Rule);
        assert_eq!(
            blocks[3],
            Block::Code {
                text: "fn main() {}".into(),
                language: "rust".into()
            }
        );
    }

    #[test]
    fn setext_heading_and_trailing_hashes() {
        let blocks = parse_markdown("Title\n=====\n\n## Another ##\n");
        assert_eq!(
            blocks[0],
            Block::Heading {
                level: 1,
                text: "Title".into()
            }
        );
        assert_eq!(
            blocks[1],
            Block::Heading {
                level: 2,
                text: "Another".into()
            }
        );
        // A '#' without a preceding space is content, not a closing marker.
        assert_eq!(parse_heading("## C#"), Some((2, "C#".to_owned())));
    }

    #[test]
    fn parses_quote_lists_and_tasks() {
        let blocks = parse_markdown("> quoted\n> second\n\n- one\n- [x] done\n- [ ] todo\n");
        assert_eq!(blocks.len(), 2, "{blocks:?}");
        assert_eq!(
            blocks[0],
            Block::Quote(vec![Block::Paragraph("quoted second".into())])
        );
        let Block::List(list) = &blocks[1] else {
            panic!("expected list, got {:?}", blocks[1]);
        };
        assert!(!list.ordered);
        assert_eq!(list.items.len(), 3);
        assert_eq!(list.items[0].checked, None);
        assert_eq!(list.items[1].checked, Some(true));
        assert_eq!(list.items[2].checked, Some(false));
        assert_eq!(list.items[1].blocks, vec![Block::Paragraph("done".into())]);
    }

    #[test]
    fn parses_ordered_nested_and_indented_lists() {
        let blocks = parse_markdown("3. third\n4. fourth\n   - nested\n");
        let Block::List(list) = &blocks[0] else {
            panic!("expected list");
        };
        assert!(list.ordered);
        assert_eq!(list.start, 3);
        assert_eq!(list.items.len(), 2);
        assert_eq!(list.items[1].blocks[0], Block::Paragraph("fourth".into()));
        assert_eq!(
            list.items[1].blocks[1],
            Block::List(ListBlock {
                ordered: false,
                start: 1,
                items: vec![ListItem {
                    blocks: vec![Block::Paragraph("nested".into())],
                    checked: None,
                }],
            })
        );
    }

    #[test]
    fn parses_pipe_tables_with_alignment() {
        let blocks = parse_markdown("| A | B | C |\n| :--- | :---: | ---: |\n| 1 | 2 | 3 |\n");
        assert_eq!(
            blocks[0],
            Block::Table {
                headers: vec!["A".into(), "B".into(), "C".into()],
                rows: vec![vec!["1".into(), "2".into(), "3".into()]],
                alignments: vec![Alignment::Left, Alignment::Center, Alignment::Right],
            }
        );
        // A delimiter without pipes does not start a table.
        assert_eq!(
            parse_markdown("a\n---\n"),
            vec![Block::Heading {
                level: 2,
                text: "a".into()
            }]
        );
    }

    #[test]
    fn table_cell_parsing_respects_code_spans_and_escapes() {
        let (cells, has_pipe) = table_cells("| `a|b` | c \\| d |");
        assert!(has_pipe);
        assert_eq!(cells, vec!["`a|b`".to_owned(), "c \\| d".to_owned()]);
    }

    #[test]
    fn unknown_syntax_stays_literal_and_depth_is_bounded() {
        assert_eq!(
            parse_markdown("plain **text** only"),
            vec![Block::Paragraph("plain **text** only".into())]
        );
        let mut nested = String::new();
        for _ in 0..40 {
            nested.push_str("> ");
        }
        nested.push_str("deep");
        let blocks = parse_markdown(&nested);
        let mut node = &blocks[0];
        let mut depth = 0;
        while let Block::Quote(children) = node {
            depth += 1;
            node = &children[0];
        }
        assert!(depth <= MAX_BLOCK_DEPTH, "depth {depth}");
    }

    #[test]
    fn oversized_input_falls_back_to_one_paragraph() {
        let huge = "a".repeat(MAX_MARKDOWN_CHARS + 1);
        let blocks = parse_markdown(&huge);
        assert!(matches!(blocks.as_slice(), [Block::Paragraph(_)]));
    }

    #[test]
    fn indented_code_strips_trailing_blank_lines() {
        let blocks = parse_markdown("    code\n    more\n\n");
        assert_eq!(
            blocks[0],
            Block::Code {
                text: "code\nmore".into(),
                language: String::new()
            }
        );
    }
}
