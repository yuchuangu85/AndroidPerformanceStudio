//! Native, inert Markdown presentation for assistant answers.
//!
//! Parsing cannot grant navigation or load remote assets. Body text uses
//! StyledText runs for emphasis/code/link styling; fenced code is rendered per
//! line with selectable runs and a copy/wrap header.

use super::{Alignment, Block, InlineStyle, ListBlock, ListItem, parse_inline, parse_markdown};
use crate::ai::AiWorkspace;
use crate::ai::style::{DshChatColors, tokens};
use crate::ai::widgets::{self, MONOSPACE};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{assets::IconName, base::SelectableText, prelude::*, *};
use std::ops::Range;

/// Lightweight token categories for fenced report snippets.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenKind {
    Comment,
    String,
    Number,
    Keyword,
}

const CODE_LANGUAGES: &[&str] = &[
    "kotlin",
    "kt",
    "java",
    "javascript",
    "js",
    "typescript",
    "ts",
    "json",
    "python",
    "py",
    "bash",
    "sh",
    "shell",
    "sql",
    "c",
    "cpp",
    "c++",
    "swift",
    "rust",
    "rs",
    "yaml",
    "yml",
];

const KEYWORDS: &[&str] = &[
    "val",
    "var",
    "fun",
    "class",
    "public",
    "private",
    "return",
    "if",
    "else",
    "for",
    "while",
    "when",
    "in",
    "is",
    "as",
    "import",
    "package",
    "object",
    "interface",
    "true",
    "false",
    "null",
    "def",
    "from",
    "let",
    "const",
    "function",
    "async",
    "await",
    "try",
    "catch",
    "throw",
    "new",
    "void",
    "static",
    "SELECT",
    "FROM",
    "WHERE",
    "JOIN",
    "GROUP",
    "BY",
    "ORDER",
    "LIMIT",
];

/// Unknown languages stay verbatim: no token spans are produced.
pub fn highlight_code(text: &str, language: &str) -> Vec<(Range<usize>, TokenKind)> {
    if !CODE_LANGUAGES
        .iter()
        .any(|candidate| candidate.eq_ignore_ascii_case(language))
    {
        return Vec::new();
    }
    let bytes = text.as_bytes();
    let mut tokens = Vec::new();
    let mut index = 0;
    while index < bytes.len() {
        let rest = &text[index..];
        if rest.starts_with("//") || rest.starts_with('#') {
            let end = rest.find('\n').map_or(bytes.len(), |offset| index + offset);
            tokens.push((index..end, TokenKind::Comment));
            index = end;
            continue;
        }
        if let Some(quote) = rest.chars().next().filter(|c| *c == '"' || *c == '\'') {
            let mut end = index + quote.len_utf8();
            let mut escaped = false;
            while end < bytes.len() {
                let character = text[end..].chars().next().unwrap_or(' ');
                end += character.len_utf8();
                if escaped {
                    escaped = false;
                } else if character == '\\' {
                    escaped = true;
                } else if character == quote {
                    break;
                }
            }
            tokens.push((index..end, TokenKind::String));
            index = end;
            continue;
        }
        let character = rest.chars().next().unwrap_or(' ');
        if character.is_ascii_digit() {
            let mut end = index;
            let mut seen_dot = false;
            for (offset, value) in rest.char_indices() {
                if value.is_ascii_digit() {
                    end = index + offset + 1;
                } else if value == '.' && !seen_dot {
                    seen_dot = true;
                } else {
                    break;
                }
            }
            tokens.push((index..end, TokenKind::Number));
            index = end;
            continue;
        }
        if character.is_alphabetic() || character == '_' {
            let end = rest
                .char_indices()
                .find(|(_, value)| !value.is_alphanumeric() && *value != '_')
                .map_or(bytes.len(), |(offset, _)| index + offset);
            let word = &text[index..end];
            if KEYWORDS.iter().any(|keyword| *keyword == word) {
                tokens.push((index..end, TokenKind::Keyword));
            }
            index = end;
            continue;
        }
        index += character.len_utf8();
    }
    tokens
}

fn token_color(kind: TokenKind, colors: &DshChatColors) -> Rgba {
    match kind {
        TokenKind::Comment => colors.muted,
        TokenKind::String => {
            if colors.dark {
                rgb(0xa3d9a5)
            } else {
                rgb(0x257048)
            }
        }
        TokenKind::Number => {
            if colors.dark {
                rgb(0xf1c88a)
            } else {
                rgb(0x8a5324)
            }
        }
        TokenKind::Keyword => colors.send,
    }
}

/// Render one assistant answer as inert Markdown.
pub fn render_safe_text(
    text: &str,
    colors: &DshChatColors,
    language: Language,
    tag: &str,
    entity: &WeakEntity<AiWorkspace>,
    wrap: &std::collections::HashSet<String>,
) -> AnyElement {
    let blocks = parse_markdown(text);
    render_blocks(&blocks, colors, language, tag, entity, wrap, false).into_any_element()
}

fn render_blocks(
    blocks: &[Block],
    colors: &DshChatColors,
    language: Language,
    tag: &str,
    entity: &WeakEntity<AiWorkspace>,
    wrap: &std::collections::HashSet<String>,
    compact: bool,
) -> Div {
    let mut column = div().w_full().flex().flex_col();
    let mut previous_rule = false;
    for (index, block) in blocks.iter().enumerate() {
        let gap = if index == 0 {
            0.0
        } else if compact {
            8.0
        } else if matches!(block, Block::Heading { level, .. } if *level <= 3)
            || matches!(block, Block::Rule)
            || previous_rule
        {
            32.0
        } else {
            16.0
        };
        previous_rule = matches!(block, Block::Rule);
        let block_tag = format!("{tag}-block-{index}");
        let mut wrapper = div().w_full();
        if gap > 0.0 {
            wrapper = wrapper.pt(px(gap));
        }
        column = column.child(wrapper.child(render_block(
            block, colors, language, &block_tag, entity, wrap, compact,
        )));
    }
    column
}

fn render_block(
    block: &Block,
    colors: &DshChatColors,
    language: Language,
    tag: &str,
    entity: &WeakEntity<AiWorkspace>,
    wrap: &std::collections::HashSet<String>,
    _compact: bool,
) -> AnyElement {
    match block {
        Block::Paragraph(text) => inline_text(
            text,
            colors,
            tokens::BODY_FONT_SIZE,
            tokens::BODY_LINE_HEIGHT,
            FontWeight::NORMAL,
            TextAlign::Left,
        ),
        Block::Heading { level, text } => {
            let (size, line_height, weight) = match level {
                1 => (21.0, 30.0, FontWeight::BOLD),
                2 => (19.0, 28.0, FontWeight::BOLD),
                3 => (18.0, 26.0, FontWeight::BOLD),
                _ => (
                    tokens::BODY_FONT_SIZE,
                    tokens::BODY_LINE_HEIGHT,
                    FontWeight::SEMIBOLD,
                ),
            };
            inline_text(text, colors, size, line_height, weight, TextAlign::Left)
        }
        Block::Code {
            text,
            language: code_language,
        } => code_block(text, code_language, colors, language, tag, entity, wrap),
        Block::Quote(children) => {
            let mut row = div().w_full().flex().items_stretch();
            row = row.child(div().w(px(2.)).flex_shrink_0().bg(colors.border));
            row = row.child(div().flex_1().min_w_0().pl(px(14.)).child(render_blocks(
                children, colors, language, tag, entity, wrap, true,
            )));
            row.into_any_element()
        }
        Block::List(list) => render_list(list, colors, language, tag, entity, wrap),
        Block::Table {
            headers,
            rows,
            alignments,
        } => render_table(headers, rows, alignments, colors),
        Block::Rule => div()
            .w_full()
            .h(px(1.))
            .bg(colors.border)
            .into_any_element(),
    }
}

fn alignment_style(alignment: Alignment) -> TextAlign {
    match alignment {
        Alignment::Left => TextAlign::Left,
        Alignment::Center => TextAlign::Center,
        Alignment::Right => TextAlign::Right,
    }
}

fn render_list(
    list: &ListBlock,
    colors: &DshChatColors,
    language: Language,
    tag: &str,
    entity: &WeakEntity<AiWorkspace>,
    wrap: &std::collections::HashSet<String>,
) -> AnyElement {
    let mut column = div().w_full().flex().flex_col().gap(px(6.));
    for (index, item) in list.items.iter().enumerate() {
        column = column.child(render_list_item(
            list, item, index, colors, language, tag, entity, wrap,
        ));
    }
    column.into_any_element()
}

fn render_list_item(
    list: &ListBlock,
    item: &ListItem,
    index: usize,
    colors: &DshChatColors,
    language: Language,
    tag: &str,
    entity: &WeakEntity<AiWorkspace>,
    wrap: &std::collections::HashSet<String>,
) -> AnyElement {
    let mut row = div().w_full().flex().items_start();
    if let Some(checked) = item.checked {
        let icon = if checked {
            IconName::Check
        } else {
            IconName::Square
        };
        let tint = if checked { colors.send } else { colors.muted };
        row = row.child(
            div()
                .pt(px(4.))
                .pr(px(6.))
                .text_size(px(16.))
                .text_color(tint)
                .child(icon),
        );
    } else {
        let marker_width = if list.ordered { 32.0 } else { 22.0 };
        let marker = if list.ordered {
            format!("{}.", list.start + index)
        } else {
            "\u{2022}".to_owned()
        };
        row = row.child(
            div()
                .w(px(marker_width))
                .pr(px(6.))
                .text_size(px(tokens::BODY_FONT_SIZE))
                .line_height(px(tokens::BODY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(studio_selectable(marker, format!("{tag}-marker-{index}"))),
        );
    }
    row = row.child(div().flex_1().min_w_0().child(render_blocks(
        &item.blocks,
        colors,
        language,
        &format!("{tag}-item-{index}"),
        entity,
        wrap,
        true,
    )));
    row.into_any_element()
}

fn render_table(
    headers: &[String],
    rows: &[Vec<String>],
    alignments: &[Alignment],
    colors: &DshChatColors,
) -> AnyElement {
    let columns = headers.len().max(1);
    let column_width = 220.0;
    let mut table = div()
        .flex()
        .flex_col()
        .w(px(column_width * columns as f32))
        .border_1()
        .border_color(colors.border)
        .rounded(px(0.));
    let mut all_rows: Vec<&[String]> = Vec::with_capacity(rows.len() + 1);
    all_rows.push(headers);
    for row in rows {
        all_rows.push(row);
    }
    for (row_index, cells) in all_rows.iter().enumerate() {
        if row_index > 0 {
            table = table.child(div().w_full().h(px(1.)).bg(colors.border));
        }
        let mut row_div = div().w_full().flex();
        if row_index == 0 {
            row_div = row_div.bg(colors.sidebar);
        }
        for (column, cell) in cells.iter().enumerate() {
            let alignment = alignments.get(column).copied().unwrap_or(Alignment::Left);
            let weight = if row_index == 0 {
                FontWeight::SEMIBOLD
            } else {
                FontWeight::NORMAL
            };
            row_div = row_div.child(div().w(px(column_width)).px(px(10.)).py(px(10.)).child(
                inline_text(
                    cell,
                    colors,
                    tokens::BODY_FONT_SIZE,
                    tokens::BODY_LINE_HEIGHT,
                    weight,
                    alignment_style(alignment),
                ),
            ));
        }
        table = table.child(row_div);
    }
    div()
        .id(ElementId::Name(
            format!("ai-table-scroll-{}", headers.join("|")).into(),
        ))
        .w_full()
        .overflow_x_scroll()
        .child(table)
        .into_any_element()
}

/// Inline runs with emphasis/code/link highlights. Runs share the parent's
/// inherited text style, so sizes/weights stay consistent with the block.
fn inline_text(
    source: &str,
    colors: &DshChatColors,
    font_size: f32,
    line_height: f32,
    weight: FontWeight,
    align: TextAlign,
) -> AnyElement {
    let inline = parse_inline(source);
    let mut highlights: Vec<(Range<usize>, HighlightStyle)> = Vec::new();
    let mut overrides: Vec<(Range<usize>, SharedString)> = Vec::new();
    let mut offset = 0usize;
    for segment in &inline.segments {
        let start = offset;
        offset += segment.text.len();
        let style: InlineStyle = segment.style;
        if style.is_plain() {
            continue;
        }
        let highlight = HighlightStyle {
            color: style.link.then(|| colors.send.into()),
            font_weight: style.bold.then_some(FontWeight::BOLD),
            font_style: style.italic.then_some(FontStyle::Italic),
            background_color: style.code.then(|| colors.sidebar.into()),
            underline: style.link.then_some(UnderlineStyle::default()),
            strikethrough: style.strike.then_some(StrikethroughStyle::default()),
            ..Default::default()
        };
        highlights.push((start..offset, highlight));
        if style.code {
            overrides.push((start..offset, MONOSPACE.into()));
        }
    }
    if inline.text.is_empty() {
        return div().into_any_element();
    }
    let styled = StyledText::new(inline.text.clone()).with_highlights(highlights);
    let styled = if overrides.is_empty() {
        styled
    } else {
        styled.with_font_family_overrides(overrides)
    };
    div()
        .w_full()
        .text_size(px(font_size))
        .line_height(px(line_height))
        .font_weight(weight)
        .text_color(colors.text)
        .text_align(align)
        .child(styled)
        .into_any_element()
}

fn studio_selectable(value: String, tag: String) -> AnyElement {
    div()
        .w_full()
        .child(crate::studio::selectable_keyed(tag, value))
        .into_any_element()
}

fn code_block(
    text: &str,
    code_language: &str,
    colors: &DshChatColors,
    language: Language,
    tag: &str,
    entity: &WeakEntity<AiWorkspace>,
    wrap_set: &std::collections::HashSet<String>,
) -> AnyElement {
    let wrap = wrap_set.contains(tag);
    let mut header = div().w_full().flex().items_center().px(px(14.)).py(px(4.));
    header = header.child(
        div()
            .flex_1()
            .min_w_0()
            .text_size(px(tokens::SECONDARY_FONT_SIZE))
            .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
            .text_color(colors.muted)
            .whitespace_nowrap()
            .text_ellipsis()
            .child(studio_selectable(
                code_language.to_owned(),
                format!("{tag}-lang"),
            )),
    );
    let wrap_entity = entity.clone();
    let wrap_tag = tag.to_owned();
    header = header.child(widgets::icon_button(
        wrap_entity,
        IconName::ChevronsUpDown,
        language.text(if wrap {
            TextKey::AiChatDisableLineWrapping
        } else {
            TextKey::AiChatWrapLines
        }),
        format!("{tag}-code-wrap"),
        true,
        32.0,
        rgba(0x00000000),
        if wrap { colors.send } else { colors.muted },
        move |this, _, cx| this.toggle_code_wrap(&wrap_tag, cx),
    ));
    header = header.child(widgets::copy_button(
        entity.clone(),
        text.to_owned(),
        language,
        format!("{tag}-code-copy"),
        format!("{tag}-code"),
        None,
        colors,
    ));
    let body = code_body(text, code_language, colors, wrap, tag);
    let mut block = div()
        .w_full()
        .flex()
        .flex_col()
        .rounded(px(tokens::SMALL_RADIUS))
        .bg(colors.sidebar)
        .border_1()
        .border_color(colors.border)
        .child(header)
        .child(div().w_full().h(px(1.)).bg(colors.border));
    block = block.child(body);
    block.into_any_element()
}

fn code_body(
    text: &str,
    code_language: &str,
    colors: &DshChatColors,
    wrap: bool,
    tag: &str,
) -> AnyElement {
    let tokens = highlight_code(text, code_language);
    let mut column = div().w_full().flex().flex_col().p(px(16.));
    let mut line_start = 0usize;
    for (line_index, line) in text.split('\n').enumerate() {
        let line_end = line_start + line.len();
        let row = if wrap {
            div().w_full().child(code_run(line, line_index, 0, colors))
        } else {
            let mut row = div().flex().items_start().whitespace_nowrap();
            let mut cursor = line_start;
            let mut run = 0usize;
            for (range, kind) in tokens
                .iter()
                .filter(|(range, _)| range.start < line_end && range.end > line_start)
            {
                let start = range.start.max(line_start);
                let end = range.end.min(line_end);
                if cursor < start {
                    row = row.child(code_run(&text[cursor..start], line_index, run, colors));
                    run += 1;
                }
                row = row.child(code_run_colored(
                    &text[start..end],
                    line_index,
                    run,
                    token_color(*kind, colors),
                    colors,
                ));
                run += 1;
                cursor = end;
            }
            if cursor < line_end {
                row = row.child(code_run(&text[cursor..line_end], line_index, run, colors));
            }
            row
        };
        column = column.child(row);
        line_start = line_end + 1;
    }
    if wrap {
        column.into_any_element()
    } else {
        div()
            .id(ElementId::Name(format!("{tag}-code-scroll").into()))
            .w_full()
            .overflow_x_scroll()
            .child(column)
            .into_any_element()
    }
}

fn code_run(text: &str, line: usize, run: usize, colors: &DshChatColors) -> AnyElement {
    code_run_colored(text, line, run, colors.text, colors)
}

fn code_run_colored(
    text: &str,
    line: usize,
    run: usize,
    color: Rgba,
    _colors: &DshChatColors,
) -> AnyElement {
    let display = if text.is_empty() { " " } else { text };
    let style = TextStyleRefinement {
        color: Some(color.into()),
        font_family: Some(MONOSPACE.into()),
        font_size: Some(px(tokens::CODE_FONT_SIZE).into()),
        line_height: Some(px(tokens::CODE_LINE_HEIGHT).into()),
        ..Default::default()
    };
    div()
        .flex_shrink_0()
        .child(
            SelectableText::new(format!("ai-code-{line}-{run}"), display.to_owned())
                .text_style(style),
        )
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn known_language_tokens_are_classified() {
        let tokens = highlight_code("val name = \"text\" // comment\nreturn 42", "kotlin");
        assert!(tokens.iter().any(|(_, kind)| *kind == TokenKind::Keyword));
        assert!(tokens.iter().any(|(_, kind)| *kind == TokenKind::String));
        assert!(tokens.iter().any(|(_, kind)| *kind == TokenKind::Comment));
        assert!(tokens.iter().any(|(_, kind)| *kind == TokenKind::Number));
    }

    #[::core::prelude::v1::test]
    fn unknown_language_stays_verbatim() {
        assert!(highlight_code("val x = 1", "brainfuck").is_empty());
        assert!(highlight_code("val x = 1", "").is_empty());
    }

    #[::core::prelude::v1::test]
    fn hash_comments_are_only_tinted_for_known_languages() {
        let tokens = highlight_code("# heading\nx", "bash");
        assert_eq!(
            tokens.first().map(|(_, kind)| *kind),
            Some(TokenKind::Comment)
        );
        // The shared tokenizer treats '#' as a line comment for every known
        // language, matching Compose's lightweight tint.
        assert!(!highlight_code("# heading", "kotlin").is_empty());
        assert!(highlight_code("# heading", "brainfuck").is_empty());
    }
}
