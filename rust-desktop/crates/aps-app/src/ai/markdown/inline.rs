//! Display-only inline Markdown runs.
//!
//! Ported from Compose DshMarkdownInline: escapes, code spans, emphasis,
//! strikethrough, safe http(s) link labels and inert HTML literal text. The
//! parser never resolves a link target, loads a resource or executes markup.

use super::MAX_INLINE_DEPTH;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct InlineStyle {
    pub bold: bool,
    pub italic: bool,
    pub strike: bool,
    pub code: bool,
    pub link: bool,
}

impl InlineStyle {
    fn merge(self, other: InlineStyle) -> Self {
        Self {
            bold: self.bold || other.bold,
            italic: self.italic || other.italic,
            strike: self.strike || other.strike,
            code: self.code || other.code,
            link: self.link || other.link,
        }
    }

    fn code() -> Self {
        Self {
            code: true,
            ..Self::default()
        }
    }

    fn link() -> Self {
        Self {
            link: true,
            ..Self::default()
        }
    }

    pub fn is_plain(self) -> bool {
        self == Self::default()
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InlineSegment {
    pub text: String,
    pub style: InlineStyle,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Inline {
    pub text: String,
    pub segments: Vec<InlineSegment>,
}

/// Parse inline markup. Malformed nesting falls back to the literal source,
/// exactly like Compose's AnnotatedString(source) fallback.
pub fn parse_inline(source: &str) -> Inline {
    let chars: Vec<char> = source.chars().collect();
    let work = chars.len() as i64 * 32 + 128;
    let limit = chars.len();
    let mut parser = Parser { chars, work };
    match parser.parse(0, limit, None, 0, InlineStyle::default()) {
        Some((segments, _)) if parser.work >= 0 => Inline {
            text: segments
                .iter()
                .map(|segment| segment.text.as_str())
                .collect(),
            segments,
        },
        _ => plain(source),
    }
}

fn plain(source: &str) -> Inline {
    Inline {
        text: source.to_owned(),
        segments: if source.is_empty() {
            Vec::new()
        } else {
            vec![InlineSegment {
                text: source.to_owned(),
                style: InlineStyle::default(),
            }]
        },
    }
}

fn push_text(segments: &mut Vec<InlineSegment>, text: &str, style: InlineStyle) {
    if text.is_empty() {
        return;
    }
    if let Some(last) = segments.last_mut() {
        if last.style == style {
            last.text.push_str(text);
            return;
        }
    }
    segments.push(InlineSegment {
        text: text.to_owned(),
        style,
    });
}

fn push_range(
    segments: &mut Vec<InlineSegment>,
    chars: &[char],
    start: usize,
    end: usize,
    style: InlineStyle,
) {
    if start >= end {
        return;
    }
    let text: String = chars[start..end].iter().collect();
    push_text(segments, &text, style);
}

struct Parser {
    chars: Vec<char>,
    work: i64,
}

impl Parser {
    fn spend(&mut self) -> bool {
        self.work -= 1;
        self.work >= 0
    }

    fn parse(
        &mut self,
        start: usize,
        limit: usize,
        closing: Option<&str>,
        depth: usize,
        style: InlineStyle,
    ) -> Option<(Vec<InlineSegment>, usize)> {
        if depth > MAX_INLINE_DEPTH {
            return None;
        }
        let mut segments: Vec<InlineSegment> = Vec::new();
        let mut index = start;
        let mut plain_start = start;
        while index < limit {
            if !self.spend() {
                return None;
            }
            let character = self.chars[index];
            if character == '\\' && index + 1 < limit && is_escapable(self.chars[index + 1]) {
                push_range(&mut segments, &self.chars, plain_start, index, style);
                push_range(&mut segments, &self.chars, index + 1, index + 2, style);
                index += 2;
                plain_start = index;
                continue;
            }
            if character == '`' {
                let width = self.run_end(index, limit) - index;
                let mut end = index + width;
                while end < limit {
                    if !self.spend() {
                        return None;
                    }
                    if self.chars[end] == '`' {
                        let next = self.run_end(end, limit);
                        if next - end == width {
                            break;
                        }
                        end = next;
                    } else {
                        end += 1;
                    }
                }
                if end == limit {
                    return None;
                }
                push_range(&mut segments, &self.chars, plain_start, index, style);
                push_range(
                    &mut segments,
                    &self.chars,
                    index + width,
                    end,
                    style.merge(InlineStyle::code()),
                );
                index = end + width;
                plain_start = index;
                continue;
            }
            if character == '<' {
                push_range(&mut segments, &self.chars, plain_start, index, style);
                let end = self.html_end(index, limit);
                push_range(&mut segments, &self.chars, index, end, style);
                index = end;
                plain_start = index;
                continue;
            }
            if character == '['
                || (character == '!' && index + 1 < limit && self.chars[index + 1] == '[')
            {
                let image = character == '!';
                let label_start = index + if image { 2 } else { 1 };
                let label_end = self.balanced_end(label_start - 1, limit, '[', ']')?;
                let target_start = label_end + 1;
                let target_end = if target_start < limit && self.chars[target_start] == '(' {
                    Some(self.balanced_end(target_start, limit, '(', ')')?)
                } else {
                    None
                };
                if image || target_end.is_some() {
                    let mut end = target_end.map_or(target_start, |end| end + 1);
                    if image
                        && target_end.is_none()
                        && target_start < limit
                        && self.chars[target_start] == '['
                    {
                        end = self.balanced_end(target_start, limit, '[', ']')? + 1;
                    }
                    push_range(&mut segments, &self.chars, plain_start, index, style);
                    if !image
                        && target_end.is_some()
                        && self.safe_http_target(target_start + 1, target_end.unwrap_or(limit))
                    {
                        let Some((nested, _)) = self.parse(
                            label_start,
                            label_end,
                            None,
                            depth + 1,
                            style.merge(InlineStyle::link()),
                        ) else {
                            return None;
                        };
                        for segment in nested {
                            push_text(&mut segments, &segment.text, segment.style);
                        }
                    } else {
                        push_range(&mut segments, &self.chars, index, end, style);
                    }
                    index = end;
                    plain_start = index;
                    continue;
                }
            }
            if character == '*' || character == '_' || character == '~' {
                let width = self.run_end(index, limit) - index;
                if let Some(close) = closing {
                    if close.starts_with(character) && self.can_close(index, width, limit) {
                        let matches = if close.chars().count() == 1 {
                            width % 2 == 1
                        } else {
                            width >= close.chars().count()
                        };
                        if matches {
                            push_range(&mut segments, &self.chars, plain_start, index, style);
                            return Some((segments, index + close.chars().count()));
                        }
                    }
                }
                if (character == '~' && width != 2) || (character != '~' && width > 3) {
                    return None;
                }
                if self.can_open(index, width, limit) {
                    let marker = character.to_string().repeat(if width >= 2 { 2 } else { 1 });
                    let nested_style = match character {
                        '~' => InlineStyle {
                            strike: true,
                            ..style
                        },
                        _ if marker.len() == 2 => InlineStyle {
                            bold: true,
                            ..style
                        },
                        _ => InlineStyle {
                            italic: true,
                            ..style
                        },
                    };
                    let Some((nested, end)) = self.parse(
                        index + marker.chars().count(),
                        limit,
                        Some(&marker),
                        depth + 1,
                        nested_style,
                    ) else {
                        return None;
                    };
                    push_range(&mut segments, &self.chars, plain_start, index, style);
                    for segment in nested {
                        push_text(&mut segments, &segment.text, segment.style);
                    }
                    index = end;
                    plain_start = index;
                    continue;
                }
                index += width;
            } else {
                index += 1;
            }
        }
        if closing.is_some() {
            return None;
        }
        push_range(&mut segments, &self.chars, plain_start, limit, style);
        Some((segments, limit))
    }

    fn run_end(&self, start: usize, limit: usize) -> usize {
        let mut end = start + 1;
        while end < limit && self.chars[end] == self.chars[start] {
            end += 1;
        }
        end
    }

    fn can_open(&self, index: usize, width: usize, limit: usize) -> bool {
        index + width < limit
            && !self.chars[index + width].is_whitespace()
            && !(self.chars[index] == '_' && index > 0 && self.chars[index - 1].is_alphanumeric())
    }

    fn can_close(&self, index: usize, width: usize, limit: usize) -> bool {
        index > 0
            && !self.chars[index - 1].is_whitespace()
            && !(self.chars[index] == '_'
                && index + width < limit
                && self.chars[index + width].is_alphanumeric())
    }

    fn balanced_end(
        &mut self,
        start: usize,
        limit: usize,
        open: char,
        close: char,
    ) -> Option<usize> {
        let mut nesting = 1;
        let mut index = start + 1;
        while index < limit {
            if !self.spend() {
                return None;
            }
            match self.chars[index] {
                '\\' => index += 1,
                character if character == open => {
                    nesting += 1;
                    if nesting > MAX_INLINE_DEPTH {
                        return None;
                    }
                }
                character if character == close => {
                    nesting -= 1;
                    if nesting == 0 {
                        return Some(index);
                    }
                }
                _ => {}
            }
            index += 1;
        }
        None
    }

    fn html_end(&mut self, start: usize, limit: usize) -> usize {
        let first_end = self.tag_end(start, limit);
        let Some(name) = match_tag(&self.chars, start) else {
            return first_end;
        };
        if self.chars.get(start + 1) == Some(&'/') {
            return first_end;
        }
        if is_void_tag(&name) {
            return first_end;
        }
        let first: String = self.chars[start..first_end].iter().collect();
        if first.trim_end().ends_with("/>") {
            return first_end;
        }
        let mut nesting = 1;
        let mut index = first_end;
        while index < limit {
            if !self.spend() {
                return limit;
            }
            if self.chars[index] == '<' {
                let next = match_tag(&self.chars, index);
                let closing = self.chars.get(index + 1) == Some(&'/');
                let end = self.tag_end(index, limit);
                if closing
                    && next
                        .as_deref()
                        .is_some_and(|next| next.eq_ignore_ascii_case(&name))
                {
                    nesting -= 1;
                    if nesting == 0 {
                        return end;
                    }
                }
                if !closing
                    && next
                        .as_deref()
                        .is_some_and(|next| next.eq_ignore_ascii_case(&name))
                    && !self.chars[index..end]
                        .iter()
                        .collect::<String>()
                        .trim_end()
                        .ends_with("/>")
                {
                    nesting += 1;
                }
                index = end;
            } else {
                index += 1;
            }
        }
        limit
    }

    fn tag_end(&mut self, start: usize, limit: usize) -> usize {
        let mut quote: Option<char> = None;
        let mut index = start + 1;
        while index < limit {
            if !self.spend() {
                return limit;
            }
            let character = self.chars[index];
            index += 1;
            if let Some(active) = quote {
                if character == active {
                    quote = None;
                }
            } else if character == '\'' || character == '"' {
                quote = Some(character);
            } else if character == '>' {
                return index;
            }
        }
        limit
    }

    fn safe_http_target(&self, start: usize, end: usize) -> bool {
        let target: String = self.chars[start..end].iter().collect();
        let lower = target.to_ascii_lowercase();
        let rest = if let Some(rest) = lower.strip_prefix("https://") {
            rest
        } else if let Some(rest) = lower.strip_prefix("http://") {
            rest
        } else {
            return false;
        };
        let host = rest.split(['/', '?', '#']).next().unwrap_or_default();
        !host.is_empty()
            && !host.contains(char::is_whitespace)
            && !target.chars().any(char::is_control)
    }
}

fn is_escapable(character: char) -> bool {
    character.is_ascii_punctuation()
}

fn match_tag(chars: &[char], start: usize) -> Option<String> {
    if chars.get(start) != Some(&'<') {
        return None;
    }
    let mut index = start + 1;
    let closing = chars.get(index) == Some(&'/');
    if closing {
        index += 1;
    }
    let first = *chars.get(index)?;
    if !first.is_ascii_alphabetic() {
        return None;
    }
    let name_start = index;
    index += 1;
    while index < chars.len()
        && (chars[index].is_ascii_alphanumeric() || chars[index] == ':' || chars[index] == '-')
    {
        index += 1;
    }
    if chars
        .get(index)
        .is_some_and(|character| character.is_ascii_alphanumeric() || *character == '_')
    {
        return None;
    }
    Some(chars[name_start..index].iter().collect())
}

fn is_void_tag(name: &str) -> bool {
    const VOID: &[&str] = &[
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
        "source", "track", "wbr",
    ];
    VOID.iter().any(|tag| tag.eq_ignore_ascii_case(name))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn styles(text: &str) -> Vec<(String, InlineStyle)> {
        parse_inline(text)
            .segments
            .into_iter()
            .map(|segment| (segment.text, segment.style))
            .collect()
    }

    fn bold() -> InlineStyle {
        InlineStyle {
            bold: true,
            ..InlineStyle::default()
        }
    }

    fn italic() -> InlineStyle {
        InlineStyle {
            italic: true,
            ..InlineStyle::default()
        }
    }

    fn strike() -> InlineStyle {
        InlineStyle {
            strike: true,
            ..InlineStyle::default()
        }
    }

    fn code() -> InlineStyle {
        InlineStyle::code()
    }

    fn link() -> InlineStyle {
        InlineStyle::link()
    }

    #[test]
    fn parses_bold_italic_and_strike() {
        assert_eq!(
            styles("a **b** c *d* e ~~f~~ g"),
            vec![
                ("a ".to_owned(), InlineStyle::default()),
                ("b".to_owned(), bold()),
                (" c ".to_owned(), InlineStyle::default()),
                ("d".to_owned(), italic()),
                (" e ".to_owned(), InlineStyle::default()),
                ("f".to_owned(), strike()),
                (" g".to_owned(), InlineStyle::default()),
            ]
        );
    }

    #[test]
    fn parses_nested_emphasis_and_merges_runs() {
        assert_eq!(
            styles("**bold *both***"),
            vec![
                ("bold ".to_owned(), bold()),
                (
                    "both".to_owned(),
                    InlineStyle {
                        bold: true,
                        italic: true,
                        ..InlineStyle::default()
                    }
                ),
            ]
        );
    }

    #[test]
    fn parses_code_spans_with_exact_runs() {
        assert_eq!(
            styles("run `fn main()` now"),
            vec![
                ("run ".to_owned(), InlineStyle::default()),
                ("fn main()".to_owned(), code()),
                (" now".to_owned(), InlineStyle::default()),
            ]
        );
        // Unmatched backticks fall back to literal source.
        let input = "a ` b";
        let parsed = parse_inline(input);
        assert_eq!(parsed.text, input);
        assert!(
            parsed
                .segments
                .iter()
                .all(|segment| segment.style.is_plain())
        );
    }

    #[test]
    fn escapes_keep_the_literal_character_without_the_backslash() {
        let input = "\\*not italic\\*";
        assert_eq!(
            styles(input),
            vec![("*not italic*".to_owned(), InlineStyle::default())]
        );
    }

    #[test]
    fn safe_http_links_render_their_label_only() {
        assert_eq!(
            styles("see [docs](https://example.com/page) now"),
            vec![
                ("see ".to_owned(), InlineStyle::default()),
                ("docs".to_owned(), link()),
                (" now".to_owned(), InlineStyle::default()),
            ]
        );
        // Non-http targets stay literal text, including their brackets.
        let input = "[x](javascript:alert(1))";
        assert_eq!(parse_inline(input).text, input);
        assert!(
            parse_inline(input)
                .segments
                .iter()
                .all(|segment| segment.style.is_plain())
        );
    }

    #[test]
    fn html_is_literal_and_never_styled() {
        let input = "<b>bold</b> and <img src=\"x\">";
        let parsed = parse_inline(input);
        assert_eq!(parsed.text, input);
        assert!(
            parsed
                .segments
                .iter()
                .all(|segment| segment.style.is_plain())
        );
    }

    #[test]
    fn unmatched_emphasis_falls_back_to_literal_text() {
        let input = "two **open";
        let parsed = parse_inline(input);
        assert_eq!(parsed.text, input);
        assert!(
            parsed
                .segments
                .iter()
                .all(|segment| segment.style.is_plain())
        );
    }

    #[test]
    fn work_budget_bounds_nesting_attempts() {
        let input = "*".repeat(6_000);
        let parsed = parse_inline(&input);
        assert_eq!(parsed.text, input);
    }
}
