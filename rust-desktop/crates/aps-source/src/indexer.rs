use crate::{SourceFile, SourceLanguage, SourceSymbol, SourceSymbolKind};

/// Heuristic declaration extraction, a faithful port of Compose
/// StructuralSourceIndexer.kt. It is not a compiler parser: comments and string
/// bodies are deliberately not skipped, exactly like the oracle regexes.
pub struct StructuralSourceIndexer;

impl StructuralSourceIndexer {
    pub fn index(&self, file: &SourceFile, text: &str, max_symbols: usize) -> Vec<SourceSymbol> {
        if max_symbols == 0 {
            return Vec::new();
        }
        match file.language {
            SourceLanguage::Kotlin => index_kotlin(file, text, max_symbols),
            SourceLanguage::Java => index_java(file, text, max_symbols),
            SourceLanguage::Xml => index_xml(file, text, max_symbols),
            SourceLanguage::C | SourceLanguage::Cpp => index_native(file, text, max_symbols),
            SourceLanguage::Other => Vec::new(),
        }
    }
}

fn symbol(
    file: &SourceFile,
    kind: SourceSymbolKind,
    qualified_name: String,
    signature: Option<String>,
    line: usize,
) -> SourceSymbol {
    SourceSymbol {
        snapshot_id: file.snapshot_id,
        relative_path: file.relative_path.clone(),
        kind,
        qualified_name,
        signature,
        start_line: line,
        end_line: line,
    }
}

fn qualify(package_name: &str, name: &str) -> String {
    if package_name.is_empty() {
        name.to_owned()
    } else {
        format!("{package_name}.{name}")
    }
}

fn is_word(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'_'
}

fn is_space(byte: u8) -> bool {
    matches!(byte, b' ' | b'\t' | b'\n' | 0x0b | 0x0c | b'\r')
}

fn is_identifier_start(byte: u8) -> bool {
    byte.is_ascii_alphabetic() || byte == b'_'
}

fn starts_with(bytes: &[u8], at: usize, value: &[u8]) -> bool {
    bytes.get(at..at + value.len()) == Some(value)
}

fn word_boundary_before(bytes: &[u8], at: usize) -> bool {
    at == 0 || !is_word(bytes[at - 1])
}

/// Java/Kotlin word characters plus the literal class characters used by the
/// Compose Java method regex: < > , ? [ ] . and the space character.
fn is_java_method_class(byte: u8) -> bool {
    is_word(byte) || matches!(byte, b'<' | b'>' | b',' | b'?' | b'[' | b']' | b'.' | b' ')
}

fn is_native_class(byte: u8) -> bool {
    is_word(byte) || matches!(byte, b':' | b'<' | b'>' | b',' | b'*' | b'&') || is_space(byte)
}

fn is_native_name(byte: u8) -> bool {
    is_word(byte) || byte == b':'
}

struct LineIndex {
    cursor: usize,
    line: usize,
}

impl LineIndex {
    fn new() -> Self {
        Self { cursor: 0, line: 1 }
    }

    /// One-based line number of a byte position; positions must not regress.
    fn at(&mut self, bytes: &[u8], position: usize) -> usize {
        let end = position.min(bytes.len());
        while self.cursor < end {
            if bytes[self.cursor] == b'\n' {
                self.line += 1;
            }
            self.cursor += 1;
        }
        self.line
    }
}

fn next_line_start(bytes: &[u8], from: usize) -> usize {
    match bytes[from.min(bytes.len())..]
        .iter()
        .position(|&byte| byte == b'\n')
    {
        Some(offset) => from + offset + 1,
        None => bytes.len() + 1,
    }
}

// ---------------------------------------------------------------------------
// Kotlin
// ---------------------------------------------------------------------------

fn package_name(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut start = 0;
    loop {
        let mut cursor = start;
        while cursor < bytes.len() && is_space(bytes[cursor]) {
            cursor += 1;
        }
        if starts_with(bytes, cursor, b"package")
            && cursor + 7 < bytes.len()
            && is_space(bytes[cursor + 7])
        {
            let mut name = cursor + 7;
            while name < bytes.len() && is_space(bytes[name]) {
                name += 1;
            }
            if name < bytes.len() && is_identifier_start(bytes[name]) {
                let begin = name;
                name += 1;
                while name < bytes.len() && (is_word(bytes[name]) || bytes[name] == b'.') {
                    name += 1;
                }
                return text[begin..name].to_owned();
            }
        }
        start = next_line_start(bytes, start);
        if start > bytes.len() {
            return String::new();
        }
    }
}

fn index_kotlin(file: &SourceFile, text: &str, max_symbols: usize) -> Vec<SourceSymbol> {
    let bytes = text.as_bytes();
    let package = package_name(text);
    let mut symbols = Vec::new();
    let mut lines = LineIndex::new();
    let mut cursor = 0;
    while cursor < bytes.len() && symbols.len() < max_symbols {
        if word_boundary_before(bytes, cursor) {
            if let Some(keyword_end) = kotlin_type_keyword(bytes, cursor) {
                if let Some((name_start, name_end)) = spaced_name(bytes, keyword_end) {
                    let line = lines.at(bytes, cursor);
                    symbols.push(symbol(
                        file,
                        SourceSymbolKind::Type,
                        qualify(&package, &text[name_start..name_end]),
                        None,
                        line,
                    ));
                    cursor = name_end;
                    continue;
                }
            }
        }
        cursor += 1;
    }
    let mut cursor = 0;
    let mut lines = LineIndex::new();
    while cursor < bytes.len() && symbols.len() < max_symbols {
        if starts_with(bytes, cursor, b"fun") && word_boundary_before(bytes, cursor) {
            if let Some(matched) = kotlin_function(bytes, cursor) {
                let line = lines.at(bytes, matched.start);
                symbols.push(symbol(
                    file,
                    SourceSymbolKind::Function,
                    qualify(&package, &text[matched.name_start..matched.name_end]),
                    Some(text[matched.args_start..matched.args_end].trim().to_owned()),
                    line,
                ));
                cursor = matched.end;
                continue;
            }
        }
        cursor += 1;
    }
    symbols.truncate(max_symbols);
    symbols
}

fn kotlin_type_keyword(bytes: &[u8], at: usize) -> Option<usize> {
    for keyword in [&b"class"[..], &b"interface"[..], &b"object"[..]] {
        if starts_with(bytes, at, keyword) {
            return Some(at + keyword.len());
        }
    }
    for keyword in [&b"enum"[..], &b"sealed"[..]] {
        if starts_with(bytes, at, keyword) {
            let mut cursor = at + keyword.len();
            let begin = cursor;
            while cursor < bytes.len() && is_space(bytes[cursor]) {
                cursor += 1;
            }
            if cursor > begin && starts_with(bytes, cursor, b"class") {
                return Some(cursor + 5);
            }
        }
    }
    None
}

/// Whitespace plus an identifier, matching the Compose type keyword suffix.
fn spaced_name(bytes: &[u8], from: usize) -> Option<(usize, usize)> {
    let mut cursor = from;
    let begin = cursor;
    while cursor < bytes.len() && is_space(bytes[cursor]) {
        cursor += 1;
    }
    if cursor == begin || cursor >= bytes.len() || !is_identifier_start(bytes[cursor]) {
        return None;
    }
    let start = cursor;
    cursor += 1;
    while cursor < bytes.len() && is_word(bytes[cursor]) {
        cursor += 1;
    }
    Some((start, cursor))
}

struct KotlinFunction {
    start: usize,
    name_start: usize,
    name_end: usize,
    args_start: usize,
    args_end: usize,
    end: usize,
}

fn kotlin_function(bytes: &[u8], at: usize) -> Option<KotlinFunction> {
    let mut cursor = at + 3;
    let begin = cursor;
    while cursor < bytes.len() && is_space(bytes[cursor]) {
        cursor += 1;
    }
    if cursor == begin {
        return None;
    }
    if cursor < bytes.len() && bytes[cursor] == b'<' {
        if let Some(close) = bytes[cursor + 1..]
            .iter()
            .position(|&byte| byte == b'>')
            .map(|offset| cursor + 1 + offset)
        {
            if close > cursor + 1 {
                cursor = close + 1;
                while cursor < bytes.len() && is_space(bytes[cursor]) {
                    cursor += 1;
                }
            }
        }
    }
    let mut paren = cursor;
    while paren < bytes.len() && bytes[paren] != b'(' {
        if !is_java_method_class(bytes[paren]) && !is_space(bytes[paren]) {
            return None;
        }
        paren += 1;
    }
    if paren >= bytes.len() {
        return None;
    }
    let mut name_end = paren;
    while name_end > cursor && is_space(bytes[name_end - 1]) {
        name_end -= 1;
    }
    if name_end == cursor {
        return None;
    }
    let mut name_start = name_end;
    while name_start > cursor && is_word(bytes[name_start - 1]) {
        name_start -= 1;
    }
    if name_start == name_end || !is_identifier_start(bytes[name_start]) {
        return None;
    }
    if name_start != cursor {
        if name_start <= cursor + 1 || bytes[name_start - 1] != b'.' {
            return None;
        }
        for &byte in &bytes[cursor..name_start - 1] {
            if is_space(byte) || !is_java_method_class(byte) {
                return None;
            }
        }
    }
    let close = bytes[paren + 1..]
        .iter()
        .position(|&byte| byte == b')')
        .map(|offset| paren + 1 + offset)?;
    Some(KotlinFunction {
        start: at,
        name_start,
        name_end,
        args_start: paren + 1,
        args_end: close,
        end: close + 1,
    })
}

// ---------------------------------------------------------------------------
// Java
// ---------------------------------------------------------------------------

fn index_java(file: &SourceFile, text: &str, max_symbols: usize) -> Vec<SourceSymbol> {
    let bytes = text.as_bytes();
    let package = package_name(text);
    let mut symbols = Vec::new();
    let mut lines = LineIndex::new();
    let mut cursor = 0;
    while cursor < bytes.len() && symbols.len() < max_symbols {
        if word_boundary_before(bytes, cursor) {
            let keyword_end = [
                &b"class"[..],
                &b"interface"[..],
                &b"enum"[..],
                &b"record"[..],
            ]
            .iter()
            .find(|keyword| starts_with(bytes, cursor, keyword))
            .map(|keyword| cursor + keyword.len());
            if let Some(keyword_end) = keyword_end {
                if let Some((name_start, name_end)) = spaced_name(bytes, keyword_end) {
                    let line = lines.at(bytes, cursor);
                    symbols.push(symbol(
                        file,
                        SourceSymbolKind::Type,
                        qualify(&package, &text[name_start..name_end]),
                        None,
                        line,
                    ));
                    cursor = name_end;
                    continue;
                }
            }
        }
        cursor += 1;
    }
    if symbols.len() < max_symbols {
        let mut lines = LineIndex::new();
        for matched in java_methods(bytes) {
            if symbols.len() == max_symbols {
                break;
            }
            let line = lines.at(bytes, matched.start);
            symbols.push(symbol(
                file,
                SourceSymbolKind::Method,
                qualify(&package, &text[matched.name_start..matched.name_end]),
                Some(text[matched.args_start..matched.args_end].trim().to_owned()),
                line,
            ));
        }
    }
    symbols.truncate(max_symbols);
    symbols
}

struct JavaMethod {
    start: usize,
    name_start: usize,
    name_end: usize,
    args_start: usize,
    args_end: usize,
    end: usize,
}

/// Exact Java regex semantics for the Compose pattern
/// (?:public|protected|private|static|final|native|synchronized|abstract|\s)+
/// [\w<>, ?\[\].]+\s+([A-Za-z_]\w*)\s*\(([^)]*)\): a right-to-left reachability
/// DP computes every position where a match can start (the regex leftmost-match
/// property), then each match is parsed greedily.
fn java_methods(bytes: &[u8]) -> Vec<JavaMethod> {
    let length = bytes.len();
    if length == 0 {
        return Vec::new();
    }
    let mut paren = vec![false; length + 1];
    {
        let mut close_after = false;
        for index in (0..length).rev() {
            let value = if is_space(bytes[index]) {
                paren[index + 1]
            } else if bytes[index] == b'(' && close_after {
                true
            } else {
                false
            };
            paren[index] = value;
            if bytes[index] == b')' {
                close_after = true;
            }
        }
    }
    const RING: usize = 13;
    let mut s_state = [false; RING];
    let mut b_state = [false; RING];
    let mut continuation_state = [false; RING];
    let mut starts = vec![false; length + 1];
    for index in (0..length).rev() {
        let next = (index + 1) % RING;
        let name_next = name_start(bytes, index + 1, &paren);
        let s_next = if index + 1 <= length {
            s_state[next]
        } else {
            false
        };
        let b_next = b_state[next];
        let s_here = is_space(bytes[index]) && (name_next || s_next);
        let b_here = s_here || (is_java_method_class(bytes[index]) && b_next);
        let a_here = is_java_method_class(bytes[index]) && b_next;
        let continue_p = keyword_length(bytes, index)
            .filter(|keyword| {
                index + keyword <= length && continuation_state[(index + keyword) % RING]
            })
            .is_some()
            || (is_space(bytes[index]) && continuation_state[next]);
        s_state[index % RING] = s_here;
        b_state[index % RING] = b_here;
        continuation_state[index % RING] = a_here || continue_p;
        starts[index] = continue_p;
    }
    let mut result = Vec::new();
    let mut from = 0;
    while from < length {
        let Some(start) = (from..length).find(|&index| starts[index]) else {
            break;
        };
        match parse_java_method(bytes, start) {
            Some(matched) => {
                from = matched.end;
                result.push(matched);
            }
            None => from = start + 1,
        }
    }
    result
}

fn name_start(bytes: &[u8], at: usize, paren: &[bool]) -> bool {
    if at >= bytes.len() || !is_identifier_start(bytes[at]) {
        return false;
    }
    let mut cursor = at + 1;
    while cursor < bytes.len() && is_word(bytes[cursor]) {
        cursor += 1;
    }
    paren.get(cursor).copied().unwrap_or(false)
}

fn keyword_length(bytes: &[u8], at: usize) -> Option<usize> {
    const KEYWORDS: [&[u8]; 8] = [
        b"public",
        b"protected",
        b"private",
        b"static",
        b"final",
        b"native",
        b"synchronized",
        b"abstract",
    ];
    KEYWORDS
        .iter()
        .find(|keyword| starts_with(bytes, at, keyword))
        .map(|keyword| keyword.len())
}

fn parse_java_method(bytes: &[u8], start: usize) -> Option<JavaMethod> {
    let length = bytes.len();
    let mut paren = start;
    while paren < length && bytes[paren] != b'(' {
        if !is_java_method_class(bytes[paren]) && !is_space(bytes[paren]) {
            return None;
        }
        paren += 1;
    }
    if paren >= length {
        return None;
    }
    let mut name_end = paren;
    while name_end > start && is_space(bytes[name_end - 1]) {
        name_end -= 1;
    }
    if name_end == start {
        return None;
    }
    let mut name_begin = name_end;
    while name_begin > start && is_word(bytes[name_begin - 1]) {
        name_begin -= 1;
    }
    if name_begin == name_end || !is_identifier_start(bytes[name_begin]) {
        return None;
    }
    if name_begin == start || !is_space(bytes[name_begin - 1]) {
        return None;
    }
    let close = bytes[paren + 1..]
        .iter()
        .position(|&byte| byte == b')')
        .map(|offset| paren + 1 + offset)?;
    Some(JavaMethod {
        start,
        name_start: name_begin,
        name_end,
        args_start: paren + 1,
        args_end: close,
        end: close + 1,
    })
}

// ---------------------------------------------------------------------------
// XML resources
// ---------------------------------------------------------------------------

fn index_xml(file: &SourceFile, text: &str, max_symbols: usize) -> Vec<SourceSymbol> {
    let bytes = text.as_bytes();
    let mut symbols = Vec::new();
    let mut lines = LineIndex::new();
    let mut cursor = 0;
    while cursor < bytes.len() && symbols.len() < max_symbols {
        if bytes[cursor] == b'@' {
            if let Some((resource_type, resource_name, end)) = xml_resource(bytes, cursor) {
                let line = lines.at(bytes, cursor);
                symbols.push(symbol(
                    file,
                    SourceSymbolKind::Resource,
                    format!(
                        "{}/{}",
                        &text[resource_type.0..resource_type.1],
                        &text[resource_name.0..resource_name.1]
                    ),
                    None,
                    line,
                ));
                cursor = end;
                continue;
            }
        }
        cursor += 1;
    }
    symbols
}

type Slice = (usize, usize);

fn xml_resource(bytes: &[u8], at: usize) -> Option<(Slice, Slice, usize)> {
    let mut cursor = at + 1;
    if cursor < bytes.len() && bytes[cursor] == b'+' {
        cursor += 1;
    }
    if cursor >= bytes.len() || !is_identifier_start(bytes[cursor]) {
        return None;
    }
    let type_start = cursor;
    cursor += 1;
    while cursor < bytes.len() && is_word(bytes[cursor]) {
        cursor += 1;
    }
    let type_end = cursor;
    if cursor >= bytes.len() || bytes[cursor] != b'/' {
        return None;
    }
    cursor += 1;
    if cursor >= bytes.len() || !is_identifier_start(bytes[cursor]) {
        return None;
    }
    let name_start = cursor;
    cursor += 1;
    while cursor < bytes.len() && is_word(bytes[cursor]) {
        cursor += 1;
    }
    Some(((type_start, type_end), (name_start, cursor), cursor))
}

// ---------------------------------------------------------------------------
// C / C++ native symbols
// ---------------------------------------------------------------------------

fn index_native(file: &SourceFile, text: &str, max_symbols: usize) -> Vec<SourceSymbol> {
    let bytes = text.as_bytes();
    let mut symbols = Vec::new();
    let mut lines = LineIndex::new();
    let mut cursor = 0;
    loop {
        if cursor > bytes.len() || symbols.len() == max_symbols {
            break;
        }
        if let Some((name, args, end)) = native_function(bytes, cursor) {
            let line = lines.at(bytes, cursor);
            symbols.push(symbol(
                file,
                SourceSymbolKind::NativeSymbol,
                text[name.0..name.1].to_owned(),
                Some(text[args.0..args.1].trim().to_owned()),
                line,
            ));
            cursor = end;
            if cursor < bytes.len() && bytes.get(cursor.wrapping_sub(1)) != Some(&b'\n') {
                cursor = next_line_start(bytes, cursor);
            }
        } else {
            cursor = next_line_start(bytes, cursor);
        }
    }
    symbols
}

/// Match the Compose pattern
/// (?m)^[\w:<>,*&\s]+\s+([A-Za-z_~][\w:]*)\s*\(([^;{}]*)\)\s*(?:const\s*)?\{
fn native_function(bytes: &[u8], start: usize) -> Option<(Slice, Slice, usize)> {
    let length = bytes.len();
    let mut cursor = start;
    while cursor < length && is_native_class(bytes[cursor]) {
        cursor += 1;
    }
    let (name_start, name_end, paren);
    if cursor < length && bytes[cursor] == b'~' {
        let begin = cursor;
        let mut end = cursor + 1;
        while end < length && is_native_name(bytes[end]) {
            end += 1;
        }
        let mut after = end;
        while after < length && is_space(bytes[after]) {
            after += 1;
        }
        if after >= length || bytes[after] != b'(' {
            return None;
        }
        if begin <= start + 1 || !is_space(bytes[begin - 1]) {
            return None;
        }
        name_start = begin;
        name_end = end;
        paren = after;
    } else {
        if cursor >= length || bytes[cursor] != b'(' {
            return None;
        }
        let mut end = cursor;
        while end > start && is_space(bytes[end - 1]) {
            end -= 1;
        }
        if end <= start + 1 {
            return None;
        }
        let mut begin = end;
        while begin > start && is_native_name(bytes[begin - 1]) {
            begin -= 1;
        }
        if begin == end || !is_identifier_start(bytes[begin]) {
            return None;
        }
        if begin <= start + 1 || !is_space(bytes[begin - 1]) {
            return None;
        }
        name_start = begin;
        name_end = end;
        paren = cursor;
    }
    let mut region_end = paren + 1;
    while region_end < length && !matches!(bytes[region_end], b';' | b'{' | b'}') {
        region_end += 1;
    }
    let mut search_end = region_end;
    while let Some(close) = (paren + 1..search_end)
        .rev()
        .find(|&index| bytes[index] == b')')
    {
        let mut tail = close + 1;
        while tail < length && is_space(bytes[tail]) {
            tail += 1;
        }
        if starts_with(bytes, tail, b"const") {
            tail += 5;
            while tail < length && is_space(bytes[tail]) {
                tail += 1;
            }
        }
        if tail < length && bytes[tail] == b'{' {
            return Some(((name_start, name_end), (paren + 1, close), tail + 1));
        }
        search_end = close;
    }
    None
}
