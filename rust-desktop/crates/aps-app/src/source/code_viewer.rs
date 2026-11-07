//! Read-only One Dark source viewer: Compose `SourceCodeViewer` parity.
//!
//! The viewer is deliberately self-contained: it owns its palette (the editor
//! surface is always dark, independent of the application theme), the per-line
//! tokenizer ported from the Compose oracle, a virtualized uniform-height line
//! list, the localized search row, and window text selection/copy.
//!
//! Selection uses GPUI Base's window text selection: one participant handle
//! covers the whole viewport, every painted line reports its laid-out run, and
//! content keys are document byte offsets so copying works even for lines that
//! have scrolled out of the virtualized viewport.

use std::{cell::RefCell, collections::BTreeSet, ops::Range, rc::Rc};

use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    base::{
        Scrollbar, ScrollbarAxis, ScrollbarHandle, TextSelection, TextSelectionContentKey,
        TextSelectionEvent, TextSelectionHandle, TextSelectionLayer, TextSelectionRegistration,
        TextSelectionRun,
    },
    component::{
        ActiveTheme, Disableable,
        button::{Button, ButtonVariants},
        input::{Input, InputEvent, InputState},
    },
    prelude::*,
    *,
};

/// One Dark palette, copied verbatim from `OneDarkSourceTheme.kt`.
mod palette {
    use gpui_kit::*;

    pub const EDITOR_BACKGROUND: u32 = 0x282C34;
    pub const FOREGROUND: u32 = 0xABB2BF;
    pub const LINE_NUMBER: u32 = 0x495162;
    pub const CURRENT_LINE: u32 = 0x2C313C;
    pub const SELECTION: u32 = 0x404859;
    pub const SEARCH_MATCH: u32 = 0x5C4E20;
    pub const CURRENT_SEARCH_MATCH: u32 = 0x3E638C;
    pub const SCROLLBAR: u32 = 0x4B575F;
    pub const SCROLLBAR_HOVER: u32 = 0x606368;

    pub const KEYWORD: u32 = 0xC678DD;
    pub const STRING: u32 = 0x98C379;
    pub const NUMBER: u32 = 0xD19A66;
    pub const COMMENT: u32 = 0x5C6370;
    pub const ANNOTATION: u32 = 0xE5C07B;
    pub const TAG: u32 = 0xE06C75;
    pub const ATTRIBUTE: u32 = 0xD19A66;

    pub fn color(hex: u32) -> Hsla {
        rgb(hex).into()
    }
}

// Fixed editor metrics: the code surface never follows the application scale,
// exactly like the Compose viewer.
const FONT_SIZE: f32 = 12.0;
const LINE_HEIGHT: f32 = 16.0;
const ROW_HEIGHT: f32 = 20.0;
const SEARCH_ROW_HEIGHT: f32 = 28.0;
const SEARCH_INPUT_HEIGHT: f32 = 22.0;
const LINE_NUMBER_WIDTH: f32 = 56.0;
const GUTTER_PADDING: f32 = 8.0;
const CODE_PADDING_END: f32 = 16.0;
const SCROLLBAR_INSET: f32 = 12.0;
const MONOSPACE: &str = "monospace";

/// Language of the displayed document, matching `SourceLanguage` in Compose.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(super) enum CodeLanguage {
    Kotlin,
    Java,
    Xml,
    C,
    Cpp,
    #[default]
    Other,
}

/// What the page hands to the viewer.
pub(super) struct SourceDocument {
    pub text: SharedString,
    pub language: CodeLanguage,
    /// Inclusive 1-based line range highlighted as the current location.
    pub highlighted_range: Option<(usize, usize)>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum TokenKind {
    Keyword,
    String,
    Number,
    Comment,
    Annotation,
    Tag,
    Attribute,
    Value,
    Preprocessor,
}

#[derive(Clone, Debug)]
struct SourceToken {
    kind: TokenKind,
    start: usize,
    end: usize,
}

#[derive(Clone, Debug)]
struct HighlightedLine {
    text: SharedString,
    /// Byte offset of the line start in the whole document.
    start: usize,
    tokens: Vec<SourceToken>,
}

#[derive(Clone, Copy, Debug, Default)]
struct LexerState {
    in_block_comment: bool,
    in_kotlin_raw_string: bool,
    in_xml_comment: bool,
}

/// One search hit, in line-relative byte offsets.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct SearchMatch {
    line_index: usize,
    start: usize,
    end: usize,
}

fn color_for(kind: TokenKind) -> Hsla {
    let hex = match kind {
        TokenKind::Keyword | TokenKind::Preprocessor => palette::KEYWORD,
        TokenKind::String | TokenKind::Value => palette::STRING,
        TokenKind::Number => palette::NUMBER,
        TokenKind::Comment => palette::COMMENT,
        TokenKind::Annotation => palette::ANNOTATION,
        TokenKind::Tag => palette::TAG,
        TokenKind::Attribute => palette::ATTRIBUTE,
    };
    palette::color(hex)
}

// ---------------------------------------------------------------------------
// Tokenizer: a line-for-line port of `highlightSource`/`highlightCodeLine`/
// `highlightXmlLine` from the Compose oracle.
// ---------------------------------------------------------------------------

fn highlight_source(text: &str, language: CodeLanguage) -> Vec<HighlightedLine> {
    let mut state = LexerState::default();
    let mut lines = Vec::new();
    let mut start = 0usize;
    let raw_lines: Vec<&str> = if text.is_empty() {
        vec![""]
    } else {
        text.split('\n').collect()
    };
    for raw in raw_lines {
        let line = raw.strip_suffix('\r').unwrap_or(raw);
        let (tokens, next) = match language {
            CodeLanguage::Xml => highlight_xml_line(line, state),
            CodeLanguage::Kotlin | CodeLanguage::Java | CodeLanguage::C | CodeLanguage::Cpp => {
                highlight_code_line(line, language, state)
            }
            CodeLanguage::Other => (Vec::new(), state),
        };
        lines.push(HighlightedLine {
            text: line.to_owned().into(),
            start,
            tokens,
        });
        state = next;
        start += raw.len() + 1;
    }
    lines
}

fn char_at(text: &str, index: usize) -> Option<char> {
    text.get(index..).and_then(|rest| rest.chars().next())
}

fn char_len_at(text: &str, index: usize) -> usize {
    char_at(text, index).map_or(1, char::len_utf8)
}

fn find_from(text: &str, needle: &str, from: usize) -> Option<usize> {
    text.get(from..)?.find(needle).map(|offset| from + offset)
}

fn starts_with_at(text: &str, index: usize, needle: &str) -> bool {
    text.get(index..)
        .is_some_and(|rest| rest.starts_with(needle))
}

fn is_identifier_start(character: char) -> bool {
    character.is_alphabetic() || character == '_' || character == '$'
}

fn is_identifier_part(character: char) -> bool {
    character.is_alphanumeric() || character == '_' || character == '$'
}

fn highlight_code_line(
    text: &str,
    language: CodeLanguage,
    initial: LexerState,
) -> (Vec<SourceToken>, LexerState) {
    let mut tokens = Vec::new();
    let mut index = 0usize;
    let mut in_block_comment = initial.in_block_comment;
    let mut in_kotlin_raw_string = initial.in_kotlin_raw_string;
    let is_native = matches!(language, CodeLanguage::C | CodeLanguage::Cpp);
    let length = text.len();

    while index < length {
        if in_block_comment {
            match find_from(text, "*/", index) {
                None => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: index,
                        end: length,
                    });
                    index = length;
                }
                Some(end) => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: index,
                        end: end + 2,
                    });
                    index = end + 2;
                    in_block_comment = false;
                }
            }
            continue;
        }
        if in_kotlin_raw_string {
            match find_from(text, "\"\"\"", index) {
                None => {
                    tokens.push(SourceToken {
                        kind: TokenKind::String,
                        start: index,
                        end: length,
                    });
                    index = length;
                }
                Some(end) => {
                    tokens.push(SourceToken {
                        kind: TokenKind::String,
                        start: index,
                        end: end + 3,
                    });
                    index = end + 3;
                    in_kotlin_raw_string = false;
                }
            }
            continue;
        }
        let Some(character) = char_at(text, index) else {
            break;
        };
        if starts_with_at(text, index, "//") {
            tokens.push(SourceToken {
                kind: TokenKind::Comment,
                start: index,
                end: length,
            });
            index = length;
        } else if starts_with_at(text, index, "/*") {
            match find_from(text, "*/", index + 2) {
                None => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: index,
                        end: length,
                    });
                    in_block_comment = true;
                    index = length;
                }
                Some(end) => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: index,
                        end: end + 2,
                    });
                    index = end + 2;
                }
            }
        } else if language == CodeLanguage::Kotlin && starts_with_at(text, index, "\"\"\"") {
            match find_from(text, "\"\"\"", index + 3) {
                None => {
                    tokens.push(SourceToken {
                        kind: TokenKind::String,
                        start: index,
                        end: length,
                    });
                    in_kotlin_raw_string = true;
                    index = length;
                }
                Some(end) => {
                    tokens.push(SourceToken {
                        kind: TokenKind::String,
                        start: index,
                        end: end + 3,
                    });
                    index = end + 3;
                }
            }
        } else if character == '"' || character == '\'' {
            let end = quoted_end(text, index);
            tokens.push(SourceToken {
                kind: TokenKind::String,
                start: index,
                end,
            });
            index = end;
        } else if character == '@' && char_at(text, index + 1).is_some_and(is_identifier_start) {
            let end = identifier_end(text, index + 1, true);
            tokens.push(SourceToken {
                kind: TokenKind::Annotation,
                start: index,
                end,
            });
            index = end;
        } else if is_native && character == '#' && text[..index].chars().all(char::is_whitespace) {
            let end = identifier_end(text, index + 1, false);
            tokens.push(SourceToken {
                kind: TokenKind::Preprocessor,
                start: index,
                end,
            });
            index = end;
        } else if character.is_ascii_digit() {
            let end = number_end(text, index);
            tokens.push(SourceToken {
                kind: TokenKind::Number,
                start: index,
                end,
            });
            index = end;
        } else if is_identifier_start(character) {
            let end = identifier_end(text, index, false);
            if let Some(kind) = token_kind_for_identifier(&text[index..end], language) {
                tokens.push(SourceToken {
                    kind,
                    start: index,
                    end,
                });
            }
            index = end;
        } else {
            index += character.len_utf8();
        }
    }

    (
        tokens,
        LexerState {
            in_block_comment,
            in_kotlin_raw_string,
            in_xml_comment: initial.in_xml_comment,
        },
    )
}

fn highlight_xml_line(text: &str, initial: LexerState) -> (Vec<SourceToken>, LexerState) {
    let mut tokens = Vec::new();
    let mut index = 0usize;
    let mut in_xml_comment = initial.in_xml_comment;
    let length = text.len();

    while index < length {
        if in_xml_comment {
            match find_from(text, "-->", index) {
                None => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: index,
                        end: length,
                    });
                    index = length;
                }
                Some(end) => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: index,
                        end: end + 3,
                    });
                    index = end + 3;
                    in_xml_comment = false;
                }
            }
            continue;
        }

        let Some(tag_start) = find_from(text, "<", index) else {
            break;
        };
        if starts_with_at(text, tag_start, "<!--") {
            match find_from(text, "-->", tag_start + 4) {
                None => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: tag_start,
                        end: length,
                    });
                    in_xml_comment = true;
                    index = length;
                }
                Some(end) => {
                    tokens.push(SourceToken {
                        kind: TokenKind::Comment,
                        start: tag_start,
                        end: end + 3,
                    });
                    index = end + 3;
                }
            }
            continue;
        }

        let mut cursor = tag_start + 1;
        if text.as_bytes().get(cursor) == Some(&b'/') {
            cursor += 1;
        }
        if text.as_bytes().get(cursor) == Some(&b'?') {
            cursor += 1;
        }
        let name_start = cursor;
        cursor = xml_name_end(text, cursor);
        if cursor > name_start {
            tokens.push(SourceToken {
                kind: TokenKind::Tag,
                start: name_start,
                end: cursor,
            });
        }

        while cursor < length && text.as_bytes().get(cursor) != Some(&b'>') {
            let Some(character) = char_at(text, cursor) else {
                break;
            };
            if character.is_whitespace() || character == '/' || character == '?' {
                cursor += character.len_utf8();
                continue;
            }
            let attribute_start = cursor;
            cursor = xml_name_end(text, cursor);
            if cursor == attribute_start {
                cursor += character.len_utf8();
                continue;
            }
            tokens.push(SourceToken {
                kind: TokenKind::Attribute,
                start: attribute_start,
                end: cursor,
            });
            while let Some(character) = char_at(text, cursor) {
                if character.is_whitespace() {
                    cursor += character.len_utf8();
                } else {
                    break;
                }
            }
            if cursor >= length || text.as_bytes().get(cursor) != Some(&b'=') {
                continue;
            }
            cursor += 1;
            while let Some(character) = char_at(text, cursor) {
                if character.is_whitespace() {
                    cursor += character.len_utf8();
                } else {
                    break;
                }
            }
            if cursor >= length {
                break;
            }
            let value_start = cursor;
            cursor = match char_at(text, cursor) {
                Some(character) if character == '"' || character == '\'' => {
                    quoted_end(text, cursor)
                }
                _ => {
                    let mut end = cursor;
                    while let Some(character) = char_at(text, end) {
                        if character.is_whitespace() || character == '>' {
                            break;
                        }
                        end += character.len_utf8();
                    }
                    end
                }
            };
            tokens.push(SourceToken {
                kind: TokenKind::Value,
                start: value_start,
                end: cursor,
            });
        }
        index = if cursor < length { cursor + 1 } else { cursor };
    }

    (
        tokens,
        LexerState {
            in_xml_comment,
            ..initial
        },
    )
}

fn token_kind_for_identifier(value: &str, language: CodeLanguage) -> Option<TokenKind> {
    if value_words().contains(&value) {
        Some(TokenKind::Value)
    } else if keywords_for(language).contains(&value) {
        Some(TokenKind::Keyword)
    } else {
        None
    }
}

fn keywords_for(language: CodeLanguage) -> &'static [&'static str] {
    match language {
        CodeLanguage::Kotlin => KOTLIN_KEYWORDS,
        CodeLanguage::Java => JAVA_KEYWORDS,
        CodeLanguage::C => C_KEYWORDS,
        CodeLanguage::Cpp => CPP_KEYWORDS,
        CodeLanguage::Xml | CodeLanguage::Other => &[],
    }
}

fn quoted_end(text: &str, start: usize) -> usize {
    let Some(quote) = char_at(text, start) else {
        return text.len();
    };
    let mut index = start + quote.len_utf8();
    while let Some(character) = char_at(text, index) {
        if character == '\\' {
            index += character.len_utf8();
            if index < text.len() {
                index += char_len_at(text, index);
            }
        } else if character == quote {
            return index + character.len_utf8();
        } else {
            index += character.len_utf8();
        }
    }
    text.len()
}

fn identifier_end(text: &str, start: usize, allow_dot_and_colon: bool) -> usize {
    let mut index = start;
    while let Some(character) = char_at(text, index) {
        if is_identifier_part(character)
            || (allow_dot_and_colon && (character == '.' || character == ':'))
        {
            index += character.len_utf8();
        } else {
            break;
        }
    }
    index
}

fn number_end(text: &str, start: usize) -> usize {
    let mut index = start + char_len_at(text, start);
    while let Some(character) = char_at(text, index) {
        if number_characters(character) {
            index += character.len_utf8();
        } else {
            break;
        }
    }
    index
}

fn xml_name_end(text: &str, start: usize) -> usize {
    let mut index = start;
    while let Some(byte) = text.as_bytes().get(index) {
        if xml_name_byte(*byte) {
            index += 1;
        } else {
            break;
        }
    }
    index
}

fn number_characters(character: char) -> bool {
    character.is_ascii_digit()
        || matches!(
            character,
            '.' | '_' | 'x' | 'X' | 'a'..='f' | 'A'..='F' | '+' | '-'
        )
}

fn xml_name_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b':' | b'.')
}

fn value_words() -> &'static [&'static str] {
    &["true", "false", "null", "NULL", "nullptr"]
}

const KOTLIN_KEYWORDS: &[&str] = &[
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "val",
    "var",
    "when",
    "while",
    "by",
    "catch",
    "constructor",
    "delegate",
    "dynamic",
    "field",
    "file",
    "finally",
    "get",
    "import",
    "init",
    "param",
    "property",
    "receiver",
    "set",
    "setparam",
    "where",
    "actual",
    "abstract",
    "annotation",
    "companion",
    "const",
    "crossinline",
    "data",
    "enum",
    "expect",
    "external",
    "final",
    "infix",
    "inline",
    "inner",
    "internal",
    "lateinit",
    "noinline",
    "open",
    "operator",
    "out",
    "override",
    "private",
    "protected",
    "public",
    "reified",
    "sealed",
    "suspend",
    "tailrec",
    "vararg",
];

const JAVA_KEYWORDS: &[&str] = &[
    "abstract",
    "assert",
    "boolean",
    "break",
    "byte",
    "case",
    "catch",
    "char",
    "class",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "extends",
    "final",
    "finally",
    "float",
    "for",
    "goto",
    "if",
    "implements",
    "import",
    "instanceof",
    "int",
    "interface",
    "long",
    "native",
    "new",
    "package",
    "private",
    "protected",
    "public",
    "return",
    "short",
    "static",
    "strictfp",
    "super",
    "switch",
    "synchronized",
    "this",
    "throw",
    "throws",
    "transient",
    "try",
    "void",
    "volatile",
    "while",
    "record",
    "sealed",
    "permits",
    "var",
    "yield",
];

const C_KEYWORDS: &[&str] = &[
    "auto",
    "break",
    "case",
    "char",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "extern",
    "float",
    "for",
    "goto",
    "if",
    "inline",
    "int",
    "long",
    "register",
    "restrict",
    "return",
    "short",
    "signed",
    "sizeof",
    "static",
    "struct",
    "switch",
    "typedef",
    "union",
    "unsigned",
    "void",
    "volatile",
    "while",
    "_Bool",
    "_Complex",
    "_Imaginary",
];

/// The Compose oracle defines C++ as the C set plus these words.
const CPP_KEYWORDS: &[&str] = &[
    "auto",
    "break",
    "case",
    "char",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "extern",
    "float",
    "for",
    "goto",
    "if",
    "inline",
    "int",
    "long",
    "register",
    "restrict",
    "return",
    "short",
    "signed",
    "sizeof",
    "static",
    "struct",
    "switch",
    "typedef",
    "union",
    "unsigned",
    "void",
    "volatile",
    "while",
    "_Bool",
    "_Complex",
    "_Imaginary",
    "alignas",
    "alignof",
    "and",
    "asm",
    "bitand",
    "bitor",
    "bool",
    "catch",
    "char8_t",
    "char16_t",
    "char32_t",
    "class",
    "concept",
    "consteval",
    "constexpr",
    "constinit",
    "const_cast",
    "co_await",
    "co_return",
    "co_yield",
    "decltype",
    "delete",
    "dynamic_cast",
    "explicit",
    "export",
    "false",
    "friend",
    "mutable",
    "namespace",
    "new",
    "noexcept",
    "not",
    "not_eq",
    "nullptr",
    "operator",
    "or",
    "or_eq",
    "private",
    "protected",
    "public",
    "reflexpr",
    "reinterpret_cast",
    "requires",
    "static_assert",
    "static_cast",
    "template",
    "this",
    "thread_local",
    "throw",
    "true",
    "try",
    "typeid",
    "typename",
    "using",
    "virtual",
    "wchar_t",
    "xor",
    "xor_eq",
];

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

fn is_blank_query(query: &str) -> bool {
    query.trim().is_empty()
}

fn character_eq_ignore_case(left: char, right: char) -> bool {
    left == right || left.to_lowercase().eq(right.to_lowercase())
}

/// Case-insensitive, left-to-right, non-overlapping substring matches per line,
/// matching the Compose `sourceSearchMatches` contract.
fn source_search_matches(lines: &[Rc<HighlightedLine>], query: &str) -> Vec<SearchMatch> {
    if is_blank_query(query) {
        return Vec::new();
    }
    let query: Vec<char> = query.chars().collect();
    let mut matches = Vec::new();
    for (line_index, line) in lines.iter().enumerate() {
        let characters: Vec<(usize, char)> = line.text.char_indices().collect();
        let mut index = 0usize;
        while index + query.len() <= characters.len() {
            let matched = query.iter().enumerate().all(|(offset, expected)| {
                character_eq_ignore_case(characters[index + offset].1, *expected)
            });
            if matched {
                let start = characters[index].0;
                let end = characters
                    .get(index + query.len())
                    .map_or(line.text.len(), |(byte, _)| *byte);
                matches.push(SearchMatch {
                    line_index,
                    start,
                    end,
                });
                index += query.len();
            } else {
                index += 1;
            }
        }
    }
    matches
}

fn highlighted_line_index(range: Option<(usize, usize)>, line_count: usize) -> Option<usize> {
    let (start, _) = range?;
    start.checked_sub(1).filter(|index| *index < line_count)
}

fn normalize_highlighted_range(
    range: Option<(usize, usize)>,
    line_count: usize,
) -> Option<(usize, usize)> {
    let (start, end) = range?;
    let start = start.max(1).min(line_count);
    let end = end.max(1).min(line_count);
    Some((start, end))
}

fn line_is_highlighted(line_number: usize, range: Option<(usize, usize)>) -> bool {
    let Some((start, end)) = range else {
        return false;
    };
    line_number >= start.min(end) && line_number <= start.max(end)
}

// ---------------------------------------------------------------------------
// Highlight layering
// ---------------------------------------------------------------------------

/// Merge overlapping highlight layers, later layers winning per field, so the
/// selection and search backgrounds never erase syntax colors.
fn merge_highlights(
    length: usize,
    layers: Vec<(Range<usize>, HighlightStyle)>,
) -> Vec<(Range<usize>, HighlightStyle)> {
    let mut events = Vec::with_capacity(layers.len() * 2 + 2);
    for (index, (range, _)) in layers.iter().enumerate() {
        let start = range.start.min(length);
        let end = range.end.min(length);
        if start < end {
            events.push((start, index, true));
            events.push((end, index, false));
        }
    }
    events.sort_unstable();
    let mut active: [BTreeSet<usize>; 7] = Default::default();
    let mut output = Vec::with_capacity(events.len() + 1);
    let mut position = 0usize;
    let mut event_index = 0usize;
    while position < length {
        while event_index < events.len() && events[event_index].0 == position {
            let (_, index, entering) = events[event_index];
            let style = &layers[index].1;
            let specified = [
                style.color.is_some(),
                style.background_color.is_some(),
                style.font_weight.is_some(),
                style.font_style.is_some(),
                style.underline.is_some(),
                style.strikethrough.is_some(),
                style.fade_out.is_some(),
            ];
            for (field, specified) in specified.into_iter().enumerate() {
                if specified {
                    if entering {
                        active[field].insert(index);
                    } else {
                        active[field].remove(&index);
                    }
                }
            }
            event_index += 1;
        }
        let end = events.get(event_index).map_or(length, |event| event.0);
        let mut style = HighlightStyle::default();
        macro_rules! resolve {
            ($field:ident, $index:expr) => {
                if let Some(index) = active[$index].last() {
                    style.$field = layers[*index].1.$field;
                }
            };
        }
        resolve!(color, 0);
        resolve!(background_color, 1);
        resolve!(font_weight, 2);
        resolve!(font_style, 3);
        resolve!(underline, 4);
        resolve!(strikethrough, 5);
        resolve!(fade_out, 6);
        output.push((position..end, style));
        position = end;
    }
    output
}

// ---------------------------------------------------------------------------
// Text selection geometry
// ---------------------------------------------------------------------------

struct LineRun {
    line_index: usize,
    line_start: usize,
    text: SharedString,
    layout: TextLayout,
    bounds: Bounds<Pixels>,
}

#[derive(Default)]
struct SelectionGeometry {
    bounds: Bounds<Pixels>,
    scroll_offset: Point<Pixels>,
    runs: Vec<LineRun>,
}

impl SelectionGeometry {
    fn clear(&mut self) {
        self.runs.clear();
    }

    /// Map a participant-local point to a document byte offset.
    fn key_at(&self, point: Point<Pixels>) -> Option<u64> {
        if self.runs.is_empty() {
            return None;
        }
        let position = point + self.bounds.origin + self.scroll_offset;
        let run = self
            .runs
            .iter()
            .find(|run| run.bounds.contains(&position))
            .or_else(|| {
                self.runs.iter().min_by(|left, right| {
                    distance_to_bounds(position, left.bounds)
                        .total_cmp(&distance_to_bounds(position, right.bounds))
                })
            })?;
        let index = match run.layout.index_for_position(position) {
            Ok(index) | Err(index) => index,
        }
        .min(run.text.len());
        Some((run.line_start + index) as u64)
    }
}

fn distance_to_bounds(point: Point<Pixels>, bounds: Bounds<Pixels>) -> f32 {
    let dx: f32 = (bounds.left() - point.x)
        .max(point.x - bounds.right())
        .max(px(0.))
        .into();
    let dy: f32 = (bounds.top() - point.y)
        .max(point.y - bounds.bottom())
        .max(px(0.))
        .into();
    dx * dx + dy * dy
}

// ---------------------------------------------------------------------------
// Custom elements
// ---------------------------------------------------------------------------

/// The selectable, syntax-colored text of one line.
struct CodeText {
    id: ElementId,
    line_index: usize,
    line_start: usize,
    line: Rc<HighlightedLine>,
    search_matches: Vec<(usize, usize)>,
    selected_match: Option<(usize, usize)>,
    selection: Option<Range<usize>>,
    geometry: Rc<RefCell<SelectionGeometry>>,
    styled_text: Option<StyledText>,
}

impl IntoElement for CodeText {
    type Element = Self;

    fn into_element(self) -> Self::Element {
        self
    }
}

impl Element for CodeText {
    type RequestLayoutState = ();
    type PrepaintState = ();

    fn id(&self) -> Option<ElementId> {
        Some(self.id.clone())
    }

    fn source_location(&self) -> Option<&'static std::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        id: Option<&GlobalElementId>,
        inspector_id: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        let mut layers: Vec<(Range<usize>, HighlightStyle)> = Vec::new();
        for token in &self.line.tokens {
            layers.push((
                token.start..token.end,
                HighlightStyle::color(color_for(token.kind)),
            ));
        }
        for (start, end) in &self.search_matches {
            let background = if self.selected_match == Some((*start, *end)) {
                palette::CURRENT_SEARCH_MATCH
            } else {
                palette::SEARCH_MATCH
            };
            layers.push((
                *start..*end,
                HighlightStyle {
                    background_color: Some(palette::color(background)),
                    ..Default::default()
                },
            ));
        }
        if let Some(selection) = &self.selection {
            if selection.start < selection.end {
                layers.push((
                    selection.clone(),
                    HighlightStyle {
                        background_color: Some(palette::color(palette::SELECTION)),
                        ..Default::default()
                    },
                ));
            }
        }
        let highlights = merge_highlights(self.line.text.len(), layers);
        self.styled_text =
            Some(StyledText::new(self.line.text.clone()).with_highlights(highlights));
        let refinement = TextStyleRefinement {
            color: Some(palette::color(palette::FOREGROUND)),
            font_family: Some(MONOSPACE.into()),
            font_size: Some(px(FONT_SIZE).into()),
            line_height: Some(px(LINE_HEIGHT).into()),
            white_space: Some(WhiteSpace::Nowrap),
            ..Default::default()
        };
        let styled = self.styled_text.as_mut().expect("styled text initialized");
        window.with_text_style(Some(refinement), |window| {
            styled.request_layout(id, inspector_id, window, cx)
        })
    }

    fn prepaint(
        &mut self,
        id: Option<&GlobalElementId>,
        inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        let styled = self.styled_text.as_mut().expect("styled text laid out");
        styled.prepaint(id, inspector_id, bounds, &mut (), window, cx);
        let layout = styled.layout().clone();
        self.geometry.borrow_mut().runs.push(LineRun {
            line_index: self.line_index,
            line_start: self.line_start,
            text: self.line.text.clone(),
            layout,
            bounds,
        });
    }

    fn paint(
        &mut self,
        id: Option<&GlobalElementId>,
        inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        let styled = self.styled_text.as_mut().expect("styled text laid out");
        styled.paint(id, inspector_id, bounds, &mut (), &mut (), window, cx);
    }
}

/// Wraps the virtualized code surface, registers the single window-selection
/// participant and projects its painted runs for copy and hit testing.
struct SelectionSurface {
    child: AnyElement,
    vertical: UniformListScrollHandle,
    selection: TextSelectionHandle,
    geometry: Rc<RefCell<SelectionGeometry>>,
}

impl IntoElement for SelectionSurface {
    type Element = Self;

    fn into_element(self) -> Self::Element {
        self
    }
}

impl Element for SelectionSurface {
    type RequestLayoutState = ();
    type PrepaintState = Hitbox;

    fn id(&self) -> Option<ElementId> {
        Some(ElementId::NamedInteger(
            "source-code-selection".into(),
            self.selection.entity_id().as_u64(),
        ))
    }

    fn source_location(&self) -> Option<&'static std::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        (self.child.request_layout(window, cx), ())
    }

    fn prepaint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) -> Hitbox {
        {
            let mut geometry = self.geometry.borrow_mut();
            geometry.clear();
            geometry.bounds = bounds;
        }
        // Keep the scrollbar gutters out of the selection hitbox so dragging the
        // overlay scrollbars never starts a text gesture underneath them.
        let selectable = Bounds::from_corners(
            bounds.origin,
            bounds.bottom_right() - point(px(SCROLLBAR_INSET), px(SCROLLBAR_INSET)),
        );
        let hitbox = window.insert_hitbox(selectable, HitboxBehavior::Normal);
        self.child.prepaint(window, cx);
        let offset = ScrollbarHandle::offset(&self.vertical);
        self.geometry.borrow_mut().scroll_offset = offset;
        let text_bounds = self
            .geometry
            .borrow()
            .runs
            .iter()
            .map(|run| run.bounds)
            .collect();
        self.selection.register(
            TextSelectionRegistration::new(hitbox.clone(), selectable)
                .with_scroll_offset(offset)
                .with_text_bounds(text_bounds)
                .with_rendered_element(&self.selection, window, cx),
            window,
            cx,
        );
        hitbox
    }

    fn paint(
        &mut self,
        _: Option<&GlobalElementId>,
        _: Option<&InspectorElementId>,
        _: Bounds<Pixels>,
        _: &mut (),
        _: &mut Hitbox,
        window: &mut Window,
        cx: &mut App,
    ) {
        let runs = self
            .geometry
            .borrow()
            .runs
            .iter()
            .map(|run| {
                TextSelectionRun::new(run.text.clone(), run.layout.clone(), run.bounds)
                    .with_document_order(run.line_index as u64)
            })
            .collect::<Vec<_>>();
        self.selection.update_runs(&runs, cx);
        self.child.paint(window, cx);
    }
}

// ---------------------------------------------------------------------------
// Viewer entity
// ---------------------------------------------------------------------------

/// Read-only syntax-highlighted source viewer.
pub(super) struct SourceCodeViewer {
    language: Language,
    document: Option<SourceDocument>,
    lines: Rc<Vec<Rc<HighlightedLine>>>,
    line_count: usize,
    line_number_digits: usize,
    longest_line: usize,
    highlighted_range: Option<(usize, usize)>,
    search: Entity<InputState>,
    _search_subscription: Subscription,
    query: String,
    matches: Rc<Vec<SearchMatch>>,
    selected_match: usize,
    select_all: bool,
    pending_scroll: Option<usize>,
    vertical: UniformListScrollHandle,
    selection: TextSelectionHandle,
    _selection_subscription: Subscription,
    geometry: Rc<RefCell<SelectionGeometry>>,
    focus: FocusHandle,
}

impl SourceCodeViewer {
    pub(super) fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let focus = cx.focus_handle();
        let search = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(Language::System.text(TextKey::SourceSearchCode))
        });
        let search_subscription = cx.subscribe(&search, |this, input, event, cx| match event {
            InputEvent::Change => {
                this.query = input.read(cx).value().to_string();
                this.refresh_matches();
                this.pending_scroll = this.matches.first().map(|hit| hit.line_index);
                cx.notify();
            }
            InputEvent::PressEnter { .. } => this.select_next_match(1, cx),
            _ => {}
        });
        let selection = TextSelectionHandle::new("", cx);
        let geometry = Rc::new(RefCell::new(SelectionGeometry::default()));
        let weak = cx.entity().downgrade();
        selection.resolve_content_key_with(
            {
                let geometry = geometry.clone();
                move |point, _cx| {
                    geometry
                        .borrow()
                        .key_at(point)
                        .map(TextSelectionContentKey::new)
                }
            },
            cx,
        );
        selection.copy_with(
            {
                let weak = weak.clone();
                move |cx| {
                    weak.upgrade()
                        .map_or_else(String::new, |view| view.read(cx).selected_text(cx))
                }
            },
            cx,
        );
        selection.focus_with(
            {
                let focus = focus.clone();
                move |window, cx| window.focus(&focus, cx)
            },
            cx,
        );
        let selection_subscription = selection.subscribe(
            move |event, cx| {
                let select_all = matches!(
                    event,
                    TextSelectionEvent::SelectionChanged(Some(_)) | TextSelectionEvent::Cleared
                );
                if select_all {
                    let _ = weak.update(cx, |view, cx| {
                        if view.select_all {
                            view.select_all = false;
                        }
                        cx.notify();
                    });
                } else if matches!(event, TextSelectionEvent::SelectionChanged(_)) {
                    let _ = weak.update(cx, |view, cx| {
                        if view.select_all {
                            view.select_all = false;
                            cx.notify();
                        }
                    });
                }
            },
            cx,
        );
        Self {
            language: Language::System,
            document: None,
            lines: Rc::new(Vec::new()),
            line_count: 0,
            line_number_digits: 1,
            longest_line: 0,
            highlighted_range: None,
            search,
            _search_subscription: search_subscription,
            query: String::new(),
            matches: Rc::new(Vec::new()),
            selected_match: 0,
            select_all: false,
            pending_scroll: None,
            vertical: UniformListScrollHandle::new(),
            selection,
            _selection_subscription: selection_subscription,
            geometry,
            focus,
        }
    }

    pub(super) fn set_document(
        &mut self,
        document: SourceDocument,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // Compose remembers the query per source text and the highlighted range
        // separately, so a range-only update keeps the search state.
        let same_content = self.document.as_ref().is_some_and(|existing| {
            existing.text == document.text && existing.language == document.language
        });
        if same_content {
            self.highlighted_range =
                normalize_highlighted_range(document.highlighted_range, self.line_count);
            self.document = Some(document);
            self.pending_scroll = highlighted_line_index(self.highlighted_range, self.line_count);
            cx.notify();
            return;
        }
        let lines = highlight_source(&document.text, document.language);
        self.line_count = lines.len().max(1);
        self.line_number_digits = self.line_count.to_string().len();
        self.longest_line = lines
            .iter()
            .enumerate()
            .max_by_key(|(_, line)| (line.text.chars().count(), line.text.len()))
            .map_or(0, |(index, _)| index);
        self.highlighted_range =
            normalize_highlighted_range(document.highlighted_range, self.line_count);
        self.lines = Rc::new(lines.into_iter().map(Rc::new).collect());
        self.document = Some(document);
        self.query.clear();
        self.search
            .update(cx, |state, cx| state.set_value("", window, cx));
        self.matches = Rc::new(Vec::new());
        self.selected_match = 0;
        self.select_all = false;
        self.pending_scroll = highlighted_line_index(self.highlighted_range, self.line_count);
        // Clearing emits selection events; defer it so the participant callback
        // never updates this entity while `set_document` already holds it.
        window.defer(cx, |window, cx| TextSelection::clear(window, cx));
        cx.notify();
    }

    pub(super) fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        self.search.update(cx, |state, cx| {
            state.set_placeholder(language.text(TextKey::SourceSearchCode), window, cx)
        });
        cx.notify();
    }

    fn refresh_matches(&mut self) {
        self.matches = Rc::new(source_search_matches(&self.lines, &self.query));
        self.selected_match = 0;
    }

    fn select_next_match(&mut self, offset: isize, cx: &mut Context<Self>) {
        let count = self.matches.len();
        if count == 0 {
            return;
        }
        self.selected_match =
            (self.selected_match as isize + offset).rem_euclid(count as isize) as usize;
        if let Some(hit) = self.matches.get(self.selected_match) {
            self.pending_scroll = Some(hit.line_index);
        }
        cx.notify();
    }

    fn selected_text(&self, cx: &App) -> String {
        if self.select_all {
            return self
                .document
                .as_ref()
                .map_or_else(String::new, |document| document.text.to_string());
        }
        let Some(snapshot) = self.selection.snapshot(cx) else {
            return String::new();
        };
        let (Some(anchor), Some(cursor)) = (
            snapshot.anchor().content_key(),
            snapshot.cursor().content_key(),
        ) else {
            return String::new();
        };
        let (anchor, cursor) = (anchor.value() as usize, cursor.value() as usize);
        let (start, end) = (anchor.min(cursor), anchor.max(cursor));
        let Some(document) = &self.document else {
            return String::new();
        };
        document
            .text
            .get(start..end)
            .map_or_else(String::new, str::to_owned)
    }

    /// The document byte range currently selected, used to paint selection
    /// backgrounds inside the virtualized lines.
    fn selection_range(&self, cx: &App) -> Option<Range<usize>> {
        if self.select_all {
            let length = self
                .document
                .as_ref()
                .map_or(0, |document| document.text.len());
            return (length > 0).then_some(0..length);
        }
        let snapshot = self.selection.snapshot(cx)?;
        let anchor = snapshot.anchor().content_key()?.value() as usize;
        let cursor = snapshot.cursor().content_key()?.value() as usize;
        Some(anchor.min(cursor)..anchor.max(cursor))
    }

    fn search_row(&mut self, cx: &mut Context<Self>) -> Div {
        let query_is_visible = !is_blank_query(&self.query);
        let match_count = self.matches.len();
        let mut row = div()
            .h(px(SEARCH_ROW_HEIGHT))
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .px(px(6.))
            .py(px(2.))
            .child(
                div().flex_1().min_w_0().child(
                    Input::new(&self.search)
                        .h(px(SEARCH_INPUT_HEIGHT))
                        .cleanable(true),
                ),
            );
        if !query_is_visible {
            return row;
        }
        let enabled = match_count > 0;
        let language = self.language;
        row = row.child(div().w(px(4.))).child(
            div()
                .min_w(px(32.))
                .whitespace_nowrap()
                .font_family(MONOSPACE)
                .text_size(px(11.))
                .text_color(if enabled {
                    cx.theme().muted_foreground
                } else {
                    cx.theme().danger
                })
                .child(if enabled {
                    format!("{}/{}", self.selected_match + 1, match_count)
                } else {
                    "0/0".to_owned()
                }),
        );
        row.child(div().w(px(2.)))
            .child(
                Button::new("source-search-previous")
                    .ghost()
                    .h(px(20.))
                    .w(px(20.))
                    .px(px(0.))
                    .rounded(px(3.))
                    .text_size(px(11.))
                    .text_color(cx.theme().muted_foreground)
                    .disabled(!enabled)
                    .accessibility_label(language.text(TextKey::SourceSearchPrevious))
                    .child("◀")
                    .on_click(cx.listener(|this, _, _, cx| this.select_next_match(-1, cx))),
            )
            .child(div().w(px(2.)))
            .child(
                Button::new("source-search-next")
                    .ghost()
                    .h(px(20.))
                    .w(px(20.))
                    .px(px(0.))
                    .rounded(px(3.))
                    .text_size(px(11.))
                    .text_color(cx.theme().muted_foreground)
                    .disabled(!enabled)
                    .accessibility_label(language.text(TextKey::SourceSearchNext))
                    .child("▶")
                    .on_click(cx.listener(|this, _, _, cx| this.select_next_match(1, cx))),
            )
    }

    fn select_all(&mut self, cx: &mut Context<Self>) {
        let Some(document) = &self.document else {
            return;
        };
        let text = document.text.to_string();
        self.select_all = true;
        self.selection.set_fallback_copy_text(text, cx);
        self.selection.set_local_selection(true, cx);
        cx.notify();
    }
}

impl Render for SourceCodeViewer {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if self.document.is_none() {
            return div()
                .size_full()
                .bg(palette::color(palette::EDITOR_BACKGROUND))
                .into_any_element();
        }
        if let Some(index) = self.pending_scroll.take() {
            self.vertical.scroll_to_item(index, ScrollStrategy::Top);
        }
        let selection_range = self.selection_range(cx);
        let geometry = self.geometry.clone();
        let vertical = self.vertical.clone();
        let lines = self.lines.clone();
        let matches = self.matches.clone();
        let line_number_digits = self.line_number_digits;
        let highlighted_range = self.highlighted_range;
        let selected_match_index = self.selected_match;

        let list = {
            let lines = lines.clone();
            let matches = matches.clone();
            let geometry = geometry.clone();
            let selection_range = selection_range.clone();
            uniform_list("source-code-lines", self.line_count, move |range, _, _| {
                let mut items = Vec::with_capacity(range.len());
                for index in range {
                    let Some(line) = lines.get(index).cloned() else {
                        continue;
                    };
                    let line_number = index + 1;
                    let selected = line_is_highlighted(line_number, highlighted_range);
                    let selected_match = matches.get(selected_match_index).copied();
                    let mut match_ranges = Vec::new();
                    let mut selected_range = None;
                    for hit in matches.iter().filter(|hit| hit.line_index == index) {
                        let range = (hit.start, hit.end);
                        if selected_match == Some(*hit) {
                            selected_range = Some(range);
                        }
                        match_ranges.push(range);
                    }
                    let selection = selection_range.as_ref().and_then(|selection| {
                        let line_start = line.start;
                        let line_end = line_start + line.text.len();
                        let start = selection.start.max(line_start);
                        let end = selection.end.min(line_end);
                        (start < end).then(|| start - line_start..end - line_start)
                    });
                    let number = format!("{line_number:>width$}", width = line_number_digits);
                    items.push(
                        div()
                            .id(("source-code-line", index))
                            .h(px(ROW_HEIGHT))
                            .flex()
                            .items_center()
                            .child(
                                // The highlight band is content-sized like the
                                // Compose Row, not the full viewport.
                                div()
                                    .h_full()
                                    .flex()
                                    .items_center()
                                    .when(selected, |this| {
                                        this.bg(palette::color(palette::CURRENT_LINE))
                                    })
                                    .child(
                                        div()
                                            .w(px(LINE_NUMBER_WIDTH))
                                            .flex_shrink_0()
                                            .px(px(GUTTER_PADDING))
                                            .flex()
                                            .items_center()
                                            .justify_end()
                                            .font_family(MONOSPACE)
                                            .text_size(px(FONT_SIZE))
                                            .text_color(palette::color(palette::LINE_NUMBER))
                                            .child(number),
                                    )
                                    .child(div().flex_shrink_0().pr(px(CODE_PADDING_END)).child(
                                        CodeText {
                                            id: ElementId::NamedInteger(
                                                "source-code-text".into(),
                                                index as u64,
                                            ),
                                            line_index: index,
                                            line_start: line.start,
                                            line: line.clone(),
                                            search_matches: match_ranges,
                                            selected_match: selected_range,
                                            selection,
                                            geometry: geometry.clone(),
                                            styled_text: None,
                                        },
                                    )),
                            )
                            .into_any_element(),
                    );
                }
                items
            })
            .track_scroll(&vertical)
            .with_width_from_item(Some(self.longest_line))
            .with_horizontal_sizing_behavior(ListHorizontalSizingBehavior::Unconstrained)
            .h_full()
            .w_full()
            .pr(px(SCROLLBAR_INSET))
            .pb(px(SCROLLBAR_INSET))
        };

        let body = div()
            .relative()
            .flex_1()
            .min_h_0()
            .min_w_0()
            .w_full()
            .child(list)
            .child(
                Scrollbar::new(&vertical)
                    .axis(ScrollbarAxis::Both)
                    .styles(|styles| {
                        styles
                            .thumb(|thumb| thumb.bg(palette::color(palette::SCROLLBAR)))
                            .thumb_hover(|thumb| thumb.bg(palette::color(palette::SCROLLBAR_HOVER)))
                    }),
            );

        div()
            .size_full()
            .flex()
            .flex_col()
            .bg(palette::color(palette::EDITOR_BACKGROUND))
            .track_focus(&self.focus)
            .on_key_down(cx.listener(|this, event: &KeyDownEvent, _window, cx| {
                if !event.keystroke.modifiers.secondary(cx) {
                    return;
                }
                match event.keystroke.key.to_ascii_lowercase().as_str() {
                    "c" => {
                        let text = this.selected_text(cx);
                        if !text.is_empty() {
                            cx.write_to_clipboard(ClipboardItem::new_string(text));
                            cx.stop_propagation();
                        }
                    }
                    "a" => {
                        this.select_all(cx);
                        cx.stop_propagation();
                    }
                    _ => {}
                }
            }))
            .child(TextSelectionLayer)
            .child(self.search_row(cx))
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .min_w_0()
                    .w_full()
                    .flex()
                    .child(SelectionSurface {
                        child: body.into_any_element(),
                        vertical: self.vertical.clone(),
                        selection: self.selection.clone(),
                        geometry: self.geometry.clone(),
                    }),
            )
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tokens(line: &HighlightedLine) -> Vec<(TokenKind, String)> {
        line.tokens
            .iter()
            .map(|token| (token.kind, line.text[token.start..token.end].to_owned()))
            .collect()
    }

    fn kinds_on_line(lines: &[HighlightedLine], index: usize) -> Vec<TokenKind> {
        lines[index].tokens.iter().map(|token| token.kind).collect()
    }

    #[::core::prelude::v1::test]
    fn resolves_line_index_and_range_like_compose() {
        assert_eq!(highlighted_line_index(Some((3, 5)), 10), Some(2));
        assert_eq!(highlighted_line_index(Some((0, 1)), 10), None);
        assert_eq!(highlighted_line_index(Some((11, 12)), 10), None);
        assert_eq!(highlighted_line_index(None, 10), None);
        let range = normalize_highlighted_range(Some((0, 99)), 4);
        assert_eq!(range, Some((1, 4)));
        assert!(line_is_highlighted(1, range));
        assert!(line_is_highlighted(4, range));
        assert!(!line_is_highlighted(5, range));
        assert!(!line_is_highlighted(1, None));
    }

    #[::core::prelude::v1::test]
    fn kotlin_tokens_match_the_compose_lexer() {
        let lines = highlight_source(
            "package sample\nimport a.b\n@Composable fun render(value: Int) {\n    // note\n    val text = \"hi\"\n    val n = 42\n}\n",
            CodeLanguage::Kotlin,
        );
        assert_eq!(
            tokens(&lines[0]),
            vec![(TokenKind::Keyword, "package".into())]
        );
        assert_eq!(
            tokens(&lines[1]),
            vec![(TokenKind::Keyword, "import".into())]
        );
        let rendered = tokens(&lines[2]);
        assert_eq!(rendered[0], (TokenKind::Annotation, "@Composable".into()));
        assert_eq!(rendered[1], (TokenKind::Keyword, "fun".into()));
        assert_eq!(
            tokens(&lines[3]),
            vec![(TokenKind::Comment, "// note".into())]
        );
        let string_line = tokens(&lines[4]);
        assert!(string_line.contains(&(TokenKind::Keyword, "val".into())));
        assert!(string_line.contains(&(TokenKind::String, "\"hi\"".into())));
        let number_line = tokens(&lines[5]);
        assert!(number_line.contains(&(TokenKind::Number, "42".into())));
    }

    #[::core::prelude::v1::test]
    fn multiline_block_comments_and_kotlin_raw_strings_keep_state() {
        let lines = highlight_source(
            "/* first\n still comment */ val x = 1\nval raw = \"\"\"a\n b\"\"\"\nfun next() = 2\n",
            CodeLanguage::Kotlin,
        );
        assert_eq!(
            tokens(&lines[0]),
            vec![(TokenKind::Comment, "/* first".into())]
        );
        assert_eq!(
            tokens(&lines[1])[0],
            (TokenKind::Comment, " still comment */".into())
        );
        assert!(
            tokens(&lines[1])
                .iter()
                .any(|(kind, text)| *kind == TokenKind::Number && text == "1")
        );
        assert_eq!(tokens(&lines[2])[1], (TokenKind::String, "\"\"\"a".into()));
        assert_eq!(tokens(&lines[3])[0], (TokenKind::String, " b\"\"\"".into()));
        assert!(tokens(&lines[4]).contains(&(TokenKind::Keyword, "fun".into())));
    }

    #[::core::prelude::v1::test]
    fn java_and_native_tokens_cover_keywords_annotations_and_preprocessor() {
        let java = highlight_source(
            "public final class A {\n    @Override void run() {}\n}\n",
            CodeLanguage::Java,
        );
        assert!(tokens(&java[0]).contains(&(TokenKind::Keyword, "public".into())));
        assert!(tokens(&java[1]).contains(&(TokenKind::Annotation, "@Override".into())));
        let cpp = highlight_source(
            "#include <jni.h>\nextern \"C\" int add(int a);\n",
            CodeLanguage::Cpp,
        );
        assert_eq!(
            tokens(&cpp[0])[0],
            (TokenKind::Preprocessor, "#include".into())
        );
        assert!(tokens(&cpp[1]).contains(&(TokenKind::Keyword, "extern".into())));
        assert!(tokens(&cpp[1]).contains(&(TokenKind::String, "\"C\"".into())));
    }

    #[::core::prelude::v1::test]
    fn xml_tokens_cover_tags_attributes_values_and_comments() {
        let lines = highlight_source(
            "<!-- header -->\n<TextView android:id=\"@+id/title\" />\n<!-- multi\nend -->\n",
            CodeLanguage::Xml,
        );
        assert_eq!(
            tokens(&lines[0]),
            vec![(TokenKind::Comment, "<!-- header -->".into())]
        );
        let row = tokens(&lines[1]);
        assert_eq!(row[0], (TokenKind::Tag, "TextView".into()));
        assert!(row.contains(&(TokenKind::Attribute, "android:id".into())));
        assert!(
            row.iter()
                .any(|(kind, text)| *kind == TokenKind::Value && text == "\"@+id/title\"")
        );
        assert_eq!(
            tokens(&lines[2])[0],
            (TokenKind::Comment, "<!-- multi".into())
        );
        assert_eq!(tokens(&lines[3])[0], (TokenKind::Comment, "end -->".into()));
    }

    #[::core::prelude::v1::test]
    fn other_languages_render_without_tokens() {
        // Compose `toSourceLines` keeps a trailing empty line after a final newline.
        let lines = highlight_source("plain text\nfun not_kotlin()\n", CodeLanguage::Other);
        assert_eq!(kinds_on_line(&lines, 0), Vec::new());
        assert_eq!(kinds_on_line(&lines, 1), Vec::new());
        assert_eq!(lines.len(), 3);
        assert!(lines[2].text.is_empty());
    }

    #[::core::prelude::v1::test]
    fn empty_document_has_one_empty_line() {
        let lines = highlight_source("", CodeLanguage::Kotlin);
        assert_eq!(lines.len(), 1);
        assert!(lines[0].text.is_empty());
        assert!(lines[0].tokens.is_empty());
    }

    #[::core::prelude::v1::test]
    fn search_matches_are_case_insensitive_non_overlapping_and_per_line() {
        let lines: Vec<Rc<HighlightedLine>> =
            highlight_source("Alpha alpha ALPHA\nbeta alpha\n", CodeLanguage::Other)
                .into_iter()
                .map(Rc::new)
                .collect();
        let matches = source_search_matches(&lines, "alpha");
        assert_eq!(matches.len(), 4);
        assert_eq!(
            matches[0],
            SearchMatch {
                line_index: 0,
                start: 0,
                end: 5
            }
        );
        assert_eq!(
            matches[1],
            SearchMatch {
                line_index: 0,
                start: 6,
                end: 11
            }
        );
        assert_eq!(
            matches[2],
            SearchMatch {
                line_index: 0,
                start: 12,
                end: 17
            }
        );
        assert_eq!(
            matches[3],
            SearchMatch {
                line_index: 1,
                start: 5,
                end: 10
            }
        );
        assert!(source_search_matches(&lines, "   ").is_empty());
        assert!(source_search_matches(&lines, "").is_empty());
    }

    #[::core::prelude::v1::test]
    fn search_offsets_are_byte_offsets_for_non_ascii_text() {
        let lines: Vec<Rc<HighlightedLine>> =
            highlight_source("中文 target 中文\n", CodeLanguage::Other)
                .into_iter()
                .map(Rc::new)
                .collect();
        let matches = source_search_matches(&lines, "TARGET");
        assert_eq!(matches.len(), 1);
        let line = &lines[0].text;
        assert_eq!(&line[matches[0].start..matches[0].end], "target");
    }
}
