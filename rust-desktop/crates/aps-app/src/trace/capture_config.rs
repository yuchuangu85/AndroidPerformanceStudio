//! Local, bounded TraceConfig editing and structural preflight. No device I/O.
//!
//! This is not version-specific Perfetto schema validation. Unknown fields are
//! retained, not type-checked. Device registration is not proof of permissions,
//! event availability, successful capture, or data completeness.
//! Named fields, brace/angle messages, scalar lists and message lists are
//! inspected; extension field keys are outside this inspector's supported syntax.

use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File},
    io::{ErrorKind, Read},
    ops::Range,
    path::Path,
};

const MAX_CONFIG_BYTES: usize = 1024 * 1024;
const MAX_TOKENS: usize = 65_536;
const MAX_DEPTH: usize = 64;
const DRAFT_NAME: &str = "capture-config.pbtxt";

#[derive(Clone, Debug, Default)]
pub(in crate::trace) struct PreflightReport {
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
    /// Positive automatic-stop duration; None means the user must stop manually.
    pub duration_ms: Option<u64>,
    pub total_buffer_kb: u64,
    pub data_sources: Vec<String>,
    pub atrace_categories: Vec<String>,
}

impl PreflightReport {
    /// Structural readiness only; warnings still require review by the caller.
    pub(in crate::trace) fn can_start(&self) -> bool {
        self.errors.is_empty()
    }
}

pub(in crate::trace) fn default_text() -> &'static str {
    include_str!("../../assets/perfetto/default-capture.pbtxt")
}

/// Import text without normalizing it. Invalid drafts remain editable; callers
/// must preflight the exact text that will be used to start a capture.
pub(in crate::trace) fn read_config(path: &Path) -> Result<String, String> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|error| format!("Cannot inspect {}: {error}", path.display()))?;
    if !metadata.is_file() || metadata.is_symlink() {
        return Err(format!("Config is not a regular file: {}", path.display()));
    }
    if metadata.len() > MAX_CONFIG_BYTES as u64 {
        return Err("Config exceeds the 1 MiB limit".into());
    }
    let file =
        File::open(path).map_err(|error| format!("Cannot open {}: {error}", path.display()))?;
    if !file.metadata().is_ok_and(|metadata| metadata.is_file()) {
        return Err("Config is not a regular file".into());
    }
    // Bound the read itself as well as metadata: files can grow after inspection.
    let mut bytes = Vec::new();
    file.take(MAX_CONFIG_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| format!("Cannot read {}: {error}", path.display()))?;
    check_size(bytes.len())?;
    String::from_utf8(bytes).map_err(|error| format!("Config must be UTF-8: {error}"))
}

/// Only an absent file returns None. Malformed text is returned unchanged for
/// repair; I/O, size and UTF-8 errors are surfaced, never replaced with defaults.
pub(in crate::trace) fn load_draft(data_dir: &Path) -> Result<Option<String>, String> {
    let path = data_dir.join(DRAFT_NAME);
    match fs::symlink_metadata(&path) {
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(None),
        Err(error) => Err(format!("Cannot inspect draft: {error}")),
        Ok(_) => read_config(&path).map(Some),
    }
}

/// Explicitly save even an unfinished draft. Reset is this same operation with
/// default_text(); merely loading or preflighting never writes anything.
pub(in crate::trace) fn save_draft(data_dir: &Path, text: &str) -> Result<(), String> {
    check_size(text.len())?;
    let path = data_dir.join(DRAFT_NAME);
    match fs::symlink_metadata(&path) {
        Ok(metadata) if !metadata.is_file() || metadata.is_symlink() => {
            return Err("Draft destination is not a regular file".into());
        }
        Err(error) if error.kind() != ErrorKind::NotFound => {
            return Err(format!("Cannot inspect draft: {error}"));
        }
        _ => {}
    }
    aps_platform::atomic_write(&path, text.as_bytes())
        .map_err(|error| format!("Cannot save capture config draft: {error}"))
}

fn check_size(size: usize) -> Result<(), String> {
    if size > MAX_CONFIG_BYTES {
        Err("Config exceeds the 1 MiB limit".into())
    } else {
        Ok(())
    }
}

#[derive(Clone, Copy, Debug)]
struct Location {
    line: usize,
    column: usize,
}

impl Location {
    fn diagnostic(self, message: impl std::fmt::Display) -> String {
        format!("line {}, column {}: {message}", self.line, self.column)
    }
}

#[derive(Clone, Debug)]
enum TokenKind<'a> {
    Word(&'a str),
    String(Vec<u8>),
    Symbol(u8),
}

#[derive(Debug)]
struct Token<'a> {
    kind: TokenKind<'a>,
    span: Range<usize>,
    location: Location,
}

struct Lexer<'a> {
    text: &'a str,
    at: usize,
    location: Location,
}

impl<'a> Lexer<'a> {
    fn byte(&self) -> Option<u8> {
        self.text.as_bytes().get(self.at).copied()
    }

    fn advance(&mut self) -> Option<u8> {
        let byte = self.byte()?;
        self.at += 1;
        if byte == b'\n' {
            self.location.line += 1;
            self.location.column = 1;
        } else {
            self.location.column += 1;
        }
        Some(byte)
    }

    fn string(&mut self, quote: u8) -> Result<Vec<u8>, String> {
        let location = self.location;
        self.advance();
        let mut result = Vec::new();
        while let Some(byte) = self.advance() {
            match byte {
                byte if byte == quote => return Ok(result),
                b'\\' => {
                    let escaped = self
                        .advance()
                        .ok_or_else(|| location.diagnostic("unfinished string escape"))?;
                    match escaped {
                        b'a' => result.push(7),
                        b'b' => result.push(8),
                        b'f' => result.push(12),
                        b'n' => result.push(b'\n'),
                        b'r' => result.push(b'\r'),
                        b't' => result.push(b'\t'),
                        b'v' => result.push(11),
                        b'\\' | b'\'' | b'"' | b'?' => result.push(escaped),
                        b'0'..=b'7' => {
                            let mut value = u16::from(escaped - b'0');
                            for _ in 0..2 {
                                match self.byte() {
                                    Some(next @ b'0'..=b'7') => {
                                        self.advance();
                                        value = value * 8 + u16::from(next - b'0');
                                    }
                                    _ => break,
                                }
                            }
                            result.push(u8::try_from(value).map_err(|_| {
                                location.diagnostic("octal escape exceeds one byte")
                            })?);
                        }
                        b'x' | b'X' | b'u' | b'U' => {
                            let count = match escaped {
                                b'u' => 4,
                                b'U' => 8,
                                _ => 2,
                            };
                            let mut value = 0_u32;
                            let mut digits = 0;
                            while digits < count {
                                let Some(digit) =
                                    self.byte().and_then(|byte| (byte as char).to_digit(16))
                                else {
                                    break;
                                };
                                self.advance();
                                value = value * 16 + digit;
                                digits += 1;
                            }
                            if digits == 0 || (count > 2 && digits != count) {
                                return Err(
                                    location.diagnostic("invalid hex/Unicode string escape")
                                );
                            }
                            if count == 2 {
                                result.push(value as u8);
                            } else {
                                let character = char::from_u32(value)
                                    .ok_or_else(|| location.diagnostic("invalid Unicode scalar"))?;
                                result.extend_from_slice(
                                    character.encode_utf8(&mut [0; 4]).as_bytes(),
                                );
                            }
                        }
                        _ => return Err(location.diagnostic("invalid string escape")),
                    }
                }
                0..=31 | 127 => {
                    return Err(location.diagnostic("unescaped control character in string"));
                }
                _ => result.push(byte),
            }
        }
        Err(location.diagnostic("unterminated string"))
    }

    fn tokens(mut self) -> Result<Vec<Token<'a>>, String> {
        let mut tokens = Vec::new();
        while let Some(byte) = self.byte() {
            let location = self.location;
            let start = self.at;
            if byte.is_ascii_whitespace() {
                self.advance();
                continue;
            }
            let rest = &self.text.as_bytes()[self.at..];
            if byte == b'#' || rest.starts_with(b"//") {
                while self.byte().is_some_and(|byte| byte != b'\n') {
                    self.advance();
                }
                continue;
            }
            if rest.starts_with(b"/*") {
                self.advance();
                self.advance();
                while !self.text.as_bytes()[self.at..].starts_with(b"*/") {
                    if self.advance().is_none() {
                        return Err(location.diagnostic("unterminated block comment"));
                    }
                }
                self.advance();
                self.advance();
                continue;
            }
            if tokens.len() == MAX_TOKENS {
                return Err(location.diagnostic("config token limit exceeded"));
            }
            let kind = match byte {
                b'"' | b'\'' => TokenKind::String(self.string(byte)?),
                b'{' | b'}' | b'<' | b'>' | b'[' | b']' | b':' | b',' | b';' => {
                    self.advance();
                    TokenKind::Symbol(byte)
                }
                byte if byte.is_ascii_alphanumeric()
                    || matches!(byte, b'_' | b'.' | b'+' | b'-') =>
                {
                    while self.byte().is_some_and(|byte| {
                        byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.' | b'+' | b'-')
                    }) {
                        self.advance();
                    }
                    TokenKind::Word(&self.text[start..self.at])
                }
                _ => {
                    return Err(
                        location.diagnostic("unexpected character outside a string/comment")
                    );
                }
            };
            tokens.push(Token {
                kind,
                span: start..self.at,
                location,
            });
        }
        Ok(tokens)
    }
}

#[derive(Debug)]
enum Value<'a> {
    Atom(&'a str),
    String(Vec<u8>),
    Message {
        fields: Vec<Field<'a>>,
        close: usize,
    },
    List(Vec<Value<'a>>),
}

impl<'a> Value<'a> {
    fn string(&self) -> Option<&str> {
        match self {
            Self::String(bytes) => std::str::from_utf8(bytes).ok(),
            _ => None,
        }
    }

    fn fields(&self) -> Option<&[Field<'a>]> {
        match self {
            Self::Message { fields, .. } => Some(fields),
            _ => None,
        }
    }

    fn items(&self) -> &[Value<'a>] {
        match self {
            Self::List(values) => values,
            _ => std::slice::from_ref(self),
        }
    }
}

#[derive(Debug)]
struct Field<'a> {
    name: &'a str,
    value: Value<'a>,
    tokens: Range<usize>,
    location: Location,
}

struct Document<'a> {
    tokens: Vec<Token<'a>>,
    fields: Vec<Field<'a>>,
}

struct Parser<'t, 'a> {
    tokens: &'t [Token<'a>],
    at: usize,
}

fn identifier(word: &str) -> bool {
    let mut bytes = word.bytes();
    bytes
        .next()
        .is_some_and(|byte| byte.is_ascii_alphabetic() || byte == b'_')
        && bytes.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

fn unsigned(word: &str) -> Option<u64> {
    let (digits, base) =
        if let Some(hex) = word.strip_prefix("0x").or_else(|| word.strip_prefix("0X")) {
            (hex, 16)
        } else if word.len() > 1 && word.starts_with('0') {
            (&word[1..], 8)
        } else {
            (word, 10)
        };
    if digits.is_empty() || !digits.bytes().all(|byte| (byte as char).is_digit(base)) {
        return None;
    }
    u64::from_str_radix(digits, base).ok()
}

fn scalar(word: &str) -> bool {
    if identifier(word) || matches!(word, "-inf" | "+inf" | "-nan" | "+nan") {
        return true;
    }
    let number = word
        .strip_prefix('-')
        .or_else(|| word.strip_prefix('+'))
        .unwrap_or(word);
    if number.is_empty() {
        return false;
    }
    if number.starts_with("0x")
        || number.starts_with("0X")
        || !number.contains(['.', 'e', 'E', 'f', 'F'])
    {
        // Check lexical shape independently of u64 range for unknown fields.
        let (digits, base) = if number.starts_with("0x") || number.starts_with("0X") {
            (&number[2..], 16)
        } else if number.len() > 1 && number.starts_with('0') {
            (&number[1..], 8)
        } else {
            (number, 10)
        };
        return !digits.is_empty() && digits.bytes().all(|byte| (byte as char).is_digit(base));
    }
    number
        .strip_suffix(['f', 'F'])
        .unwrap_or(number)
        .parse::<f64>()
        .is_ok()
}

impl<'a> Parser<'_, 'a> {
    fn symbol(&self, symbol: u8) -> bool {
        self.tokens
            .get(self.at)
            .is_some_and(|token| matches!(token.kind, TokenKind::Symbol(byte) if byte == symbol))
    }

    fn error(&self, message: &str) -> String {
        self.tokens
            .get(self.at)
            .or_else(|| self.tokens.last())
            .map_or(Location { line: 1, column: 1 }, |token| token.location)
            .diagnostic(message)
    }

    fn fields(&mut self, depth: usize, close: Option<u8>) -> Result<Vec<Field<'a>>, String> {
        let mut fields = Vec::new();
        while self.at < self.tokens.len() && !close.is_some_and(|symbol| self.symbol(symbol)) {
            let start = self.at;
            let token = &self.tokens[self.at];
            let TokenKind::Word(name) = token.kind else {
                return Err(self.error("expected a field name"));
            };
            if !identifier(name) {
                return Err(self.error("invalid field name (extensions are not supported by this structural inspector)"));
            }
            let location = token.location;
            self.at += 1;
            if self.symbol(b':') {
                self.at += 1;
            } else if !self.symbol(b'{') && !self.symbol(b'<') {
                return Err(self.error("expected ':' before scalar/list"));
            }
            let value = self.value(depth + 1)?;
            if self.symbol(b',') || self.symbol(b';') {
                self.at += 1;
            }
            fields.push(Field {
                name,
                value,
                tokens: start..self.at,
                location,
            });
        }
        if close.is_some() && self.at == self.tokens.len() {
            return Err(self.error("unclosed message"));
        }
        Ok(fields)
    }

    fn value(&mut self, depth: usize) -> Result<Value<'a>, String> {
        if depth > MAX_DEPTH {
            return Err(self.error("config nesting depth limit exceeded"));
        }
        let token = self
            .tokens
            .get(self.at)
            .ok_or_else(|| self.error("expected a value"))?;
        let kind = token.kind.clone();
        self.at += 1;
        match kind {
            TokenKind::Symbol(open @ (b'{' | b'<')) => {
                let close = if open == b'{' { b'}' } else { b'>' };
                let fields = self.fields(depth, Some(close))?;
                let close = self.tokens[self.at].span.start;
                self.at += 1;
                Ok(Value::Message { fields, close })
            }
            TokenKind::Symbol(b'[') => {
                let mut values = Vec::new();
                while !self.symbol(b']') {
                    let value = self.value(depth + 1)?;
                    if matches!(value, Value::List(_)) {
                        return Err(self.error("nested scalar lists are not supported"));
                    }
                    values.push(value);
                    if self.symbol(b']') {
                        break;
                    }
                    if !self.symbol(b',') {
                        return Err(self.error("expected ',' between list values"));
                    }
                    self.at += 1;
                }
                self.at += 1;
                Ok(Value::List(values))
            }
            TokenKind::String(mut bytes) => {
                while let Some(Token {
                    kind: TokenKind::String(next),
                    ..
                }) = self.tokens.get(self.at)
                {
                    bytes.extend_from_slice(next);
                    self.at += 1;
                }
                Ok(Value::String(bytes))
            }
            TokenKind::Word(word) if scalar(word) => Ok(Value::Atom(word)),
            _ => Err(token
                .location
                .diagnostic("invalid scalar or mismatched delimiter")),
        }
    }
}

fn parse(text: &str) -> Result<Document<'_>, String> {
    check_size(text.len())?;
    let tokens = Lexer {
        text,
        at: 0,
        location: Location { line: 1, column: 1 },
    }
    .tokens()?;
    let fields = Parser {
        tokens: &tokens,
        at: 0,
    }
    .fields(0, None)?;
    Ok(Document { tokens, fields })
}

fn named<'f, 'a>(fields: &'f [Field<'a>], name: &'f str) -> impl Iterator<Item = &'f Field<'a>> {
    fields.iter().filter(move |field| field.name == name)
}

fn single<'f, 'a>(
    fields: &'f [Field<'a>],
    name: &str,
    errors: &mut Vec<String>,
) -> Option<&'f Field<'a>> {
    let mut matches = fields.iter().filter(|field| field.name == name);
    let first = matches.next()?;
    if let Some(duplicate) = matches.next() {
        errors.push(duplicate.location.diagnostic(format!("duplicate {name}")));
        return None;
    }
    Some(first)
}

fn uint32(field: &Field<'_>, positive: bool, errors: &mut Vec<String>) -> Option<u64> {
    if let Value::Atom(word) = &field.value
        && let Some(value) = unsigned(word)
            .filter(|value| *value <= u64::from(u32::MAX) && (!positive || *value > 0))
    {
        return Some(value);
    }
    errors.push(field.location.diagnostic(format!(
        "{} must be {}uint32",
        field.name,
        if positive { "a positive " } else { "a " }
    )));
    None
}

fn string_field<'f>(field: &'f Field<'_>, errors: &mut Vec<String>) -> Option<&'f str> {
    match field.value.string() {
        Some(value) if !value.trim().is_empty() && !value.chars().any(char::is_control) => {
            Some(value)
        }
        _ => {
            errors.push(field.location.diagnostic(format!(
                "{} must be a nonempty UTF-8 string without controls",
                field.name
            )));
            None
        }
    }
}

fn message<'f, 'a>(
    value: &'f Value<'a>,
    field: &Field<'_>,
    errors: &mut Vec<String>,
) -> Option<&'f [Field<'a>]> {
    match value.fields() {
        Some(fields) => Some(fields),
        None => {
            errors.push(
                field
                    .location
                    .diagnostic(format!("{} must contain a message", field.name)),
            );
            None
        }
    }
}

pub(in crate::trace) fn preflight(
    text: &str,
    sources: Option<&BTreeSet<String>>,
    categories: Option<&BTreeSet<String>>,
) -> PreflightReport {
    let mut report = PreflightReport::default();
    report.warnings.push("Structural inspection only, not version-specific schema validation; permissions, kernel events and data completeness are not verified.".into());
    let document = match parse(text) {
        Ok(document) => document,
        Err(error) => {
            report.errors.push(error);
            return report;
        }
    };
    let root = &document.fields;
    let origin = Location { line: 1, column: 1 };
    let duration = single(root, "duration_ms", &mut report.errors)
        .and_then(|field| uint32(field, false, &mut report.errors));
    report.duration_ms = duration.filter(|duration| *duration > 0);
    if duration == Some(0) || !root.iter().any(|field| field.name == "duration_ms") {
        report.warnings.push(
            "Missing or zero duration_ms: capture requires manual Stop; no automatic completion is scheduled.".into(),
        );
    }
    let mut buffers = Vec::new();
    let mut buffer_names = BTreeMap::new();
    for field in named(root, "buffers") {
        for value in field.value.items() {
            let index = buffers.len();
            buffers.push(field.location);
            let Some(fields) = message(value, field, &mut report.errors) else {
                continue;
            };
            match single(fields, "size_kb", &mut report.errors) {
                Some(size) => {
                    if let Some(size) = uint32(size, true, &mut report.errors) {
                        report.total_buffer_kb += size;
                    }
                }
                None if !fields.iter().any(|field| field.name == "size_kb") => report
                    .errors
                    .push(field.location.diagnostic("buffer is missing size_kb")),
                None => {}
            }
            if let Some(policy) = single(fields, "fill_policy", &mut report.errors)
                && !matches!(&policy.value, Value::Atom(word) if matches!(*word, "UNSPECIFIED" | "RING_BUFFER" | "DISCARD") || unsigned(word).is_some_and(|value| value <= 2))
            {
                report.errors.push(policy.location.diagnostic(
                    "invalid fill_policy; expected RING_BUFFER or DISCARD (or enum number 0..2)",
                ));
            }
            if let Some(name) = single(fields, "name", &mut report.errors)
                .and_then(|field| string_field(field, &mut report.errors))
                && buffer_names.insert(name, index).is_some()
            {
                report.errors.push(
                    field
                        .location
                        .diagnostic(format!("duplicate buffer name {name:?}")),
                );
            }
        }
    }
    if buffers.is_empty() {
        report
            .errors
            .push(origin.diagnostic("at least one buffers message is required"));
    }
    let mut used_buffers = BTreeSet::new();
    let mut source_names = BTreeSet::new();
    let mut category_names = BTreeSet::new();
    for field in named(root, "data_sources") {
        for value in field.value.items() {
            let Some(fields) = message(value, field, &mut report.errors) else {
                continue;
            };
            let Some(config) = single(fields, "config", &mut report.errors) else {
                report.errors.push(
                    field
                        .location
                        .diagnostic("data_sources requires exactly one config message"),
                );
                continue;
            };
            let Some(config_fields) = message(&config.value, config, &mut report.errors) else {
                continue;
            };
            let Some(name) = single(config_fields, "name", &mut report.errors)
                .and_then(|field| string_field(field, &mut report.errors))
            else {
                report.errors.push(
                    config
                        .location
                        .diagnostic("data source config requires exactly one nonempty name"),
                );
                continue;
            };
            source_names.insert(name.to_owned());
            if sources.is_some_and(|sources| !sources.contains(name)) {
                report.warnings.push(config.location.diagnostic(format!(
                    "data source {name:?} is not advertised by the device; capture may omit it"
                )));
            }
            let target = single(config_fields, "target_buffer", &mut report.errors);
            let target_index = target.and_then(|field| uint32(field, false, &mut report.errors));
            let target_name = single(config_fields, "target_buffer_name", &mut report.errors);
            let named_index = target_name
                .and_then(|field| string_field(field, &mut report.errors))
                .and_then(|name| {
                    let index = buffer_names.get(name).copied();
                    if index.is_none() {
                        report.errors.push(
                            config
                                .location
                                .diagnostic(format!("unknown target_buffer_name {name:?}")),
                        );
                    }
                    index
                });
            if let (Some(index), Some(named)) = (target_index, named_index)
                && index != named as u64
            {
                report.errors.push(
                    config
                        .location
                        .diagnostic("target_buffer and target_buffer_name disagree"),
                );
            }
            let index = named_index
                .map(|index| index as u64)
                .or(target_index)
                .unwrap_or(0);
            if index >= buffers.len() as u64 {
                report.errors.push(config.location.diagnostic(format!(
                    "target_buffer {index} is out of range for {} buffers",
                    buffers.len()
                )));
            } else {
                used_buffers.insert(index as usize);
            }
            if name == "linux.ftrace"
                && let Some(ftrace) = single(config_fields, "ftrace_config", &mut report.errors)
                && let Some(fields) = message(&ftrace.value, ftrace, &mut report.errors)
            {
                for category in named(fields, "atrace_categories") {
                    for value in category.value.items() {
                        match value.string() {
                                    Some(name) if !name.trim().is_empty() && !name.chars().any(char::is_control) => {
                                        category_names.insert(name.to_owned());
                                        if categories.is_some_and(|categories| !categories.contains(name)) {
                                            report.errors.push(category.location.diagnostic(format!("atrace category {name:?} is not advertised by the device; remove it or refresh capabilities")));
                                        }
                                    }
                                    _ => report.errors.push(category.location.diagnostic("atrace_categories must contain nonempty UTF-8 strings without controls")),
                                }
                    }
                }
                for app in named(fields, "atrace_apps") {
                    if app
                        .value
                        .items()
                        .iter()
                        .any(|value| value.string() == Some("*"))
                    {
                        report.warnings.push(app.location.diagnostic("atrace_apps '*' requests broad app tracing: overhead and sensitive data risk"));
                    }
                }
            }
            if matches!(name, "android.heapprofd" | "android.java_hprof") {
                report.warnings.push(config.location.diagnostic(format!("{name}: heap profiling/dumps can add overhead, pause processes and expose sensitive memory; process scopes and permissions require review")));
            }
            if name == "android.heapprofd" {
                for heap in named(config_fields, "heapprofd_config")
                    .filter_map(|field| field.value.fields())
                {
                    if named(heap, "block_client")
                        .any(|field| matches!(field.value, Value::Atom("true" | "1")))
                    {
                        report.warnings.push(
                            config
                                .location
                                .diagnostic("heapprofd block_client can stall selected processes"),
                        );
                    }
                    if named(heap, "all")
                        .any(|field| matches!(field.value, Value::Atom("true" | "1")))
                    {
                        report.warnings.push(
                            config
                                .location
                                .diagnostic("heapprofd all requests a broad process scope"),
                        );
                    }
                }
            }
            if matches!(
                name,
                "android.log"
                    | "android.protolog"
                    | "android.windowmanager"
                    | "android.input.inputevent"
                    | "android.surfaceflinger.layers"
                    | "android.surfaceflinger.transactions"
                    | "android.viewcapture"
            ) {
                report.warnings.push(config.location.diagnostic(format!("{name}: broad/verbose log or UI capture can add volume/overhead and expose sensitive data; device privileges vary")));
            }
        }
    }
    if source_names.is_empty() {
        report
            .errors
            .push(origin.diagnostic("at least one named data source config is required"));
    }
    for (index, location) in buffers.iter().enumerate() {
        if !used_buffers.contains(&index) {
            report.warnings.push(location.diagnostic(format!(
                "buffer {index} is unused by configured data sources"
            )));
        }
    }
    if sources.is_none() {
        report.warnings.push(
            "Device source capabilities unavailable; configured sources have not been checked."
                .into(),
        );
    }
    if categories.is_none() && !category_names.is_empty() {
        report.warnings.push("Device atrace category capabilities unavailable; configured categories have not been checked.".into());
    }
    report.data_sources = source_names.into_iter().collect();
    report.atrace_categories = category_names.into_iter().collect();
    report
}

/// Explicit editor action, never an implicit rewrite during load/preflight/save.
/// Update each linux.ftrace config; never invent a source or alter atrace_apps,
/// events, targets or unrelated fields. Token-only edits retain comments, even
/// comments embedded in old repeated category lists/string concatenations.
pub(in crate::trace) fn set_atrace_categories(
    text: &str,
    categories: &BTreeSet<String>,
) -> Result<String, String> {
    let document = parse(text)?;
    let mut rendered = String::new();
    for category in categories {
        if category.trim().is_empty() || category.chars().any(char::is_control) {
            return Err("Categories must be nonempty strings without control characters".into());
        }
        if category.len() > MAX_CONFIG_BYTES {
            return Err("Categories exceed the 1 MiB limit".into());
        }
        rendered.push_str("atrace_categories: \"");
        for character in category.chars() {
            if matches!(character, '\\' | '"') {
                rendered.push('\\');
            }
            rendered.push(character);
        }
        rendered.push_str("\"\n");
        check_size(rendered.len())?;
    }
    let mut edits: Vec<(Range<usize>, String)> = Vec::new();
    let mut added = 0;
    let mut found = false;
    for source in named(&document.fields, "data_sources") {
        for value in source.value.items() {
            let fields = value.fields().ok_or_else(|| {
                source
                    .location
                    .diagnostic("data_sources must contain a message")
            })?;
            let mut errors = Vec::new();
            let Some(config) = single(fields, "config", &mut errors) else {
                return Err(source
                    .location
                    .diagnostic("data_sources requires exactly one config"));
            };
            let config_fields = config
                .value
                .fields()
                .ok_or_else(|| config.location.diagnostic("config must be a message"))?;
            let name = single(config_fields, "name", &mut errors)
                .and_then(|field| string_field(field, &mut errors));
            if !errors.is_empty() {
                return Err(errors.join("\n"));
            }
            if name != Some("linux.ftrace") {
                continue;
            }
            found = true;
            let ftrace = single(config_fields, "ftrace_config", &mut errors);
            if !errors.is_empty() {
                return Err(errors.join("\n"));
            }
            let (fields, close, wrap) = match ftrace {
                Some(Field {
                    value: Value::Message { fields, close },
                    ..
                }) => (fields.as_slice(), *close, false),
                Some(field) => {
                    return Err(field.location.diagnostic("ftrace_config must be a message"));
                }
                None => match config.value {
                    Value::Message { close, .. } => (&[][..], close, true),
                    _ => unreachable!(),
                },
            };
            let old: Vec<_> = named(fields, "atrace_categories").collect();
            let current: Option<BTreeSet<String>> = old
                .iter()
                .flat_map(|field| field.value.items())
                .map(|value| value.string().map(str::to_owned))
                .collect();
            if current.as_ref() == Some(categories) {
                continue;
            }
            let anchor = old.first().map_or(close, |field| {
                document.tokens[field.tokens.start].span.start
            });
            let line_start = text[..anchor].rfind('\n').map_or(0, |index| index + 1);
            let prefix = &text[line_start..anchor];
            let indent = if prefix.bytes().all(|byte| matches!(byte, b' ' | b'\t')) {
                prefix
            } else {
                "    "
            };
            // At most the entire input can be removed. Larger staged additions
            // cannot fit the final limit, even if later blocks remove more text.
            let budget = MAX_CONFIG_BYTES * 2 - added;
            let mut replacement = String::new();
            let mut push = |part: &str| -> Result<(), String> {
                if part.len() > budget - replacement.len() {
                    return Err("Edited config exceeds the 1 MiB limit".into());
                }
                replacement.push_str(part);
                Ok(())
            };
            if !rendered.is_empty() {
                if old.is_empty() {
                    push("\n")?;
                    if wrap {
                        push(indent)?;
                        push("    ftrace_config {\n")?;
                    }
                    for line in rendered.lines() {
                        push(indent)?;
                        push(if wrap { "        " } else { "    " })?;
                        push(line)?;
                        push("\n")?;
                    }
                    if wrap {
                        push(indent)?;
                        push("    }\n")?;
                    }
                    push(indent)?;
                } else {
                    for (index, line) in rendered.lines().enumerate() {
                        if index > 0 {
                            push("\n")?;
                            push(indent)?;
                        }
                        push(line)?;
                    }
                }
            }
            added += replacement.len();
            // Replace only lexical tokens: trivia between them remains byte exact.
            let mut first = true;
            for field in old {
                for index in field.tokens.clone() {
                    edits.push((
                        document.tokens[index].span.clone(),
                        if first {
                            first = false;
                            replacement.clone()
                        } else {
                            String::new()
                        },
                    ));
                }
            }
            if first && !replacement.is_empty() {
                edits.push((anchor..anchor, replacement));
            }
        }
    }
    if !found {
        return Err("No linux.ftrace data source exists; cannot apply atrace categories".into());
    }
    let removed: usize = edits.iter().map(|(span, _)| span.len()).sum();
    check_size(text.len() - removed + added)?;
    edits.sort_by_key(|(span, _)| span.start);
    let mut result = String::with_capacity(text.len() - removed + added);
    let mut cursor = 0;
    for (span, replacement) in edits {
        result.push_str(&text[cursor..span.start]);
        result.push_str(&replacement);
        cursor = span.end;
    }
    result.push_str(&text[cursor..]);
    parse(&result)?;
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_platform::TempWorkspace;
    use std::fs;

    const MINIMAL: &str = "buffers { size_kb: 1024 }\ndata_sources { config { name: \"linux.ftrace\" ftrace_config { atrace_categories: \"gfx\" } } }\nduration_ms: 1000\n";

    fn set(values: &[&str]) -> BTreeSet<String> {
        values.iter().map(|value| (*value).to_owned()).collect()
    }

    fn report(text: &str) -> PreflightReport {
        preflight(text, None, None)
    }

    fn has(messages: &[String], needle: &str) -> bool {
        messages.iter().any(|message| message.contains(needle))
    }

    #[test]
    fn supplied_default_preserves_requested_sources_and_scope() {
        let result = report(default_text());
        assert!(result.can_start(), "{:?}", result.errors);
        assert_eq!(result.duration_ms, Some(10000));
        assert_eq!(result.total_buffer_kb, 65536);
        assert_eq!(
            result.data_sources,
            set(&[
                "android.gpu.memory",
                "linux.process_stats",
                "android.windowmanager",
                "android.protolog",
                "android.input.inputevent",
                "android.surfaceflinger.layers",
                "android.surfaceflinger.transactions",
                "com.android.wm.shell.transition",
                "android.inputmethod",
                "android.viewcapture",
                "android.log",
                "android.surfaceflinger.frametimeline",
                "linux.sys_stats",
                "android.heapprofd",
                "android.java_hprof",
                "linux.ftrace",
            ])
            .into_iter()
            .collect::<Vec<_>>()
        );
        assert_eq!(result.atrace_categories.len(), 27);
        assert!(has(&result.warnings, "heap"));
        assert!(has(&result.warnings, "log"));
        assert!(has(&result.warnings, "block_client"));
        assert!(!has(&result.warnings, "unused"));
        assert!(default_text().contains("process_cmdline: \"com.tcl.android.launcher\""));
    }

    #[test]
    fn detects_original_four_buffer_bug_and_bad_targets() {
        let original_shape = format!(
            "{}\nbuffers {{ size_kb: 63488 fill_policy: RING_BUFFER }}\nbuffers {{ size_kb: 63488 fill_policy: RING_BUFFER }}",
            default_text()
        );
        let result = report(&original_shape);
        assert!(result.can_start());
        assert_eq!(result.total_buffer_kb, 192512);
        assert!(has(&result.warnings, "buffer 2 is unused"));
        assert!(has(&result.warnings, "buffer 3 is unused"));
        for target in ["1", "-1", "4294967296", "oops", "\"0\""] {
            let text = MINIMAL.replace(
                "name: \"linux.ftrace\"",
                &format!("name: \"linux.ftrace\" target_buffer: {target}"),
            );
            assert!(!report(&text).can_start(), "{target}");
        }
    }

    #[test]
    fn missing_or_zero_duration_supports_manual_stop_without_auto_completion() {
        for value in ["", "duration_ms: 0", "duration_ms: 0x0"] {
            let text = MINIMAL.replace("duration_ms: 1000", value);
            let result = report(&text);
            assert!(result.can_start(), "{value}: {:?}", result.errors);
            assert_eq!(
                result.duration_ms, None,
                "manual Stop must not schedule a zero-duration completion"
            );
            assert!(has(&result.warnings, "manual Stop"));
            assert!(has(&result.warnings, "no automatic completion"));
        }
        assert_eq!(report(MINIMAL).duration_ms, Some(1000));
    }

    #[test]
    fn rejects_invalid_and_duplicate_duration() {
        for value in [
            "duration_ms: -1",
            "duration_ms: 1.5",
            "duration_ms: 4294967296",
            "duration_ms: \"10\"",
            "duration_ms: unknown",
            "duration_ms: 1 duration_ms: 2",
            "duration_ms {}",
        ] {
            let text = MINIMAL.replace("duration_ms: 1000", value);
            let result = report(&text);
            assert!(!result.can_start(), "{value}");
            assert!(has(&result.errors, "duration_ms"), "{:?}", result.errors);
        }
    }

    #[test]
    fn validates_required_messages_and_buffer_scalars() {
        for text in [
            "duration_ms: 1",
            "buffers: 1 duration_ms: 1",
            "buffers { size_kb: 0 } data_sources {} duration_ms: 1",
            "buffers { size_kb: 1 size_kb: 2 } duration_ms: 1",
            "buffers { size_kb: 1 fill_policy: WRONG } duration_ms: 1",
            "buffers { size_kb: 1 } data_sources { config { name: 2 } } duration_ms: 1",
            "buffers { size_kb: 1 } data_sources { config { name: \"\" } } duration_ms: 1",
            "buffers { size_kb: 1 } data_sources { config { name: \"x\" } config { name: \"y\" } } duration_ms: 1",
        ] {
            assert!(!report(text).can_start(), "{text}");
        }
    }

    #[test]
    fn handles_comments_quotes_concatenation_and_escapes() {
        let text = MINIMAL
            .replace("\"linux.ftrace\"", "'linux.' /* } fake { */ \"ftrace\"")
            .replace("\"gfx\"", r#""\x67\146x""#);
        let text = format!(
            "# buffers {{ size_kb: 999 }}\n{text}\n// duration_ms: 0\nunique_session_name: \"quote \\\" slash \\\\ brace }} # //\"\n"
        );
        let result = report(&text);
        assert!(result.can_start(), "{:?}", result.errors);
        assert_eq!(result.atrace_categories, vec!["gfx"]);
        assert_eq!(result.total_buffer_kb, 1024);
    }

    #[test]
    fn syntax_failures_have_line_and_column_diagnostics() {
        for bad in [
            "buffers {",
            "buffers < }",
            "duration_ms 1",
            "foo: 12oops",
            "foo: 1..2",
            "foo: @",
            "foo: \"unterminated",
            "foo: \"bad\\q\"",
            "foo: \"bad\nline\"",
            "/* unclosed",
            "foo: [1 2]",
            "}",
        ] {
            let result = report(&format!("# first line\n{bad}"));
            assert!(!result.can_start(), "{bad}");
            assert!(has(&result.errors, "line "), "{:?}", result.errors);
            assert!(has(&result.errors, "column "), "{:?}", result.errors);
        }
    }

    #[test]
    fn lists_angle_messages_and_integer_bases_are_structural() {
        let text = "buffers: [<size_kb: 0x400 fill_policy: 1>, {size_kb: 010}] data_sources: [{config: <name: 'linux.ftrace' target_buffer: 0 ftrace_config: {atrace_categories: ['gfx', 'view']}>}] duration_ms: 01750";
        let result = report(text);
        assert!(result.can_start(), "{:?}", result.errors);
        assert_eq!(result.duration_ms, Some(1000));
        assert_eq!(result.total_buffer_kb, 1032);
        assert_eq!(result.atrace_categories, vec!["gfx", "view"]);
        assert!(has(&result.warnings, "buffer 1 is unused"));
    }

    #[test]
    fn missing_and_empty_device_capabilities_are_not_success_claims() {
        let unknown = report(MINIMAL);
        assert!(unknown.can_start());
        assert!(has(&unknown.warnings, "source capabilities unavailable"));
        assert!(has(&unknown.warnings, "category capabilities unavailable"));
        assert!(has(&unknown.warnings, "schema"));
        let empty = BTreeSet::new();
        let result = preflight(MINIMAL, Some(&empty), Some(&empty));
        assert!(has(&result.warnings, "linux.ftrace"));
        assert!(has(&result.errors, "gfx"));
        let result = preflight(MINIMAL, Some(&set(&["linux.ftrace"])), Some(&set(&["gfx"])));
        assert!(result.can_start());
        assert!(!has(&result.warnings, "capabilities unavailable"));
        assert!(has(&result.warnings, "completeness"));
    }

    #[test]
    fn bounded_text_depth_and_token_counts() {
        assert!(!report(&" ".repeat(MAX_CONFIG_BYTES + 1)).can_start());
        let deep = format!(
            "{}{}",
            "nested {".repeat(MAX_DEPTH + 1),
            "}".repeat(MAX_DEPTH + 1)
        );
        assert!(has(&report(&deep).errors, "depth"));
        let tokens = "a: 1 ".repeat(MAX_TOKENS / 3 + 1);
        assert!(has(&report(&tokens).errors, "token"));
    }

    #[test]
    fn imports_are_bounded_utf8_regular_files() {
        let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = temp.path().join("import.pbtxt");
        fs::write(&path, MINIMAL).unwrap();
        assert_eq!(read_config(&path).unwrap(), MINIMAL);
        fs::write(&path, [0xff, 0xfe]).unwrap();
        assert!(read_config(&path).unwrap_err().contains("UTF-8"));
        fs::write(&path, vec![b' '; MAX_CONFIG_BYTES + 1]).unwrap();
        assert!(read_config(&path).is_err());
        assert!(read_config(temp.path()).is_err());
    }

    #[test]
    fn draft_roundtrip_explicit_reset_and_malformed_text_preservation() {
        let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let dir = temp.path().join("drafts");
        assert_eq!(load_draft(&dir).unwrap(), None);
        assert!(!dir.exists());
        save_draft(&dir, MINIMAL).unwrap();
        assert_eq!(load_draft(&dir).unwrap().as_deref(), Some(MINIMAL));
        save_draft(&dir, "buffers {").unwrap();
        assert_eq!(load_draft(&dir).unwrap().as_deref(), Some("buffers {"));
        assert!(!report(&load_draft(&dir).unwrap().unwrap()).can_start());
        save_draft(&dir, default_text()).unwrap();
        assert_eq!(load_draft(&dir).unwrap().as_deref(), Some(default_text()));
        assert!(save_draft(&dir, &" ".repeat(MAX_CONFIG_BYTES + 1)).is_err());
        assert_eq!(load_draft(&dir).unwrap().as_deref(), Some(default_text()));
        fs::write(dir.join(DRAFT_NAME), [0xff]).unwrap();
        assert!(load_draft(&dir).is_err());
        assert_eq!(fs::read(dir.join(DRAFT_NAME)).unwrap(), [0xff]);
    }

    #[test]
    fn category_edit_preserves_unrelated_fields_comments_and_targets() {
        let text = MINIMAL.replace("atrace_categories: \"gfx\"", "# keep category note\natrace_categories: [\"gfx\", /* keep inline */ 'view'] # keep trailing\nftrace_events: \"sched/sched_switch\" atrace_apps: \"*\"");
        let text = format!("{text}# untouched\nunique_session_name: 'keep \\\"quote'\n");
        let changed = set_atrace_categories(&text, &set(&["wm", "gfx"])).unwrap();
        for preserved in [
            "# keep category note",
            "/* keep inline */",
            "# keep trailing",
            "ftrace_events: \"sched/sched_switch\"",
            "atrace_apps: \"*\"",
            "# untouched",
            "unique_session_name: 'keep \\\"quote'",
        ] {
            assert!(changed.contains(preserved), "{preserved}: {changed}");
        }
        let result = report(&changed);
        assert!(result.can_start(), "{:?}", result.errors);
        assert_eq!(result.atrace_categories, vec!["gfx", "wm"]);
        assert_eq!(result.duration_ms, Some(1000));
        assert_eq!(result.total_buffer_kb, 1024);
        assert_eq!(
            set_atrace_categories(&changed, &set(&["wm", "gfx"])).unwrap(),
            changed
        );
        assert!(
            report(&set_atrace_categories(&text, &BTreeSet::new()).unwrap())
                .atrace_categories
                .is_empty()
        );
    }

    #[test]
    fn category_edit_adds_missing_fields_and_updates_each_ftrace_block() {
        let text = MINIMAL.replace("ftrace_config { atrace_categories: \"gfx\" }", "");
        let changed = set_atrace_categories(&text, &set(&["view"])).unwrap();
        assert!(report(&changed).can_start());
        assert_eq!(report(&changed).atrace_categories, vec!["view"]);
        let text = format!(
            "{MINIMAL} data_sources {{ config {{ name: 'linux.ftrace' ftrace_config {{}} }} }}"
        );
        let changed = set_atrace_categories(&text, &set(&["view"])).unwrap();
        assert_eq!(changed.matches("atrace_categories: \"view\"").count(), 2);
        assert!(set_atrace_categories("bad {", &set(&["view"])).is_err());
        assert!(
            set_atrace_categories(
                &MINIMAL.replace("linux.ftrace", "android.log"),
                &set(&["view"])
            )
            .is_err()
        );
        assert!(set_atrace_categories(MINIMAL, &set(&["bad\ncategory"])).is_err());
        let injection = "gfx\" } duration_ms: 0 #";
        let changed = set_atrace_categories(MINIMAL, &set(&[injection])).unwrap();
        assert_eq!(report(&changed).duration_ms, Some(1000));
        assert_eq!(report(&changed).atrace_categories, vec![injection]);
    }

    #[test]
    fn named_buffer_targets_are_resolved_and_cross_checked() {
        let text = MINIMAL
            .replace("size_kb: 1024", "size_kb: 1024 name: 'events'")
            .replace(
                "name: \"linux.ftrace\"",
                "name: \"linux.ftrace\" target_buffer_name: 'events'",
            );
        assert!(report(&text).can_start());
        assert!(!has(&report(&text).warnings, "unused"));
        assert!(
            !report(&text.replace(
                "target_buffer_name: 'events'",
                "target_buffer_name: 'missing'"
            ))
            .can_start()
        );
        assert!(
            !report(&text.replace(
                "target_buffer_name: 'events'",
                "target_buffer_name: 'events' target_buffer: 1"
            ))
            .can_start()
        );
        assert!(!report(&format!("{text} buffers {{ size_kb: 1 name: 'events' }}")).can_start());
    }

    #[test]
    fn category_edits_have_output_and_staged_memory_bounds() {
        let category = "v".repeat(MAX_CONFIG_BYTES / 2);
        let sources =
            "data_sources { config { name: 'linux.ftrace' ftrace_config {} } }\n".repeat(20);
        assert!(set_atrace_categories(&sources, &set(&[&category])).is_err());
        assert!(set_atrace_categories(MINIMAL, &set(&[&"v".repeat(MAX_CONFIG_BYTES)])).is_err());
        let text = MINIMAL.replace(
            "atrace_categories:",
            &format!("\n{}atrace_categories:", " ".repeat(100_000)),
        );
        let categories = (0..200).map(|index| format!("category_{index}")).collect();
        assert!(set_atrace_categories(&text, &categories).is_err());
    }

    #[test]
    fn malformed_editor_inputs_never_panic_or_change_unrelated_report_fields() {
        let insertions = [
            "}",
            "{",
            "[",
            "]",
            "<",
            ">",
            "\"",
            "'",
            "\\",
            "/*",
            "*/",
            "#",
            ":",
            ";",
            "\n",
            "\0",
            "\u{1f600}",
        ];
        let mut state = 17_u64;
        for _ in 0..2048 {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1);
            let at = state as usize % (MINIMAL.len() + 1);
            let insertion = insertions[(state >> 32) as usize % insertions.len()];
            let mut text = MINIMAL.to_owned();
            text.insert_str(at, insertion);
            let before = report(&text);
            if let Ok(edited) = set_atrace_categories(&text, &set(&["view"])) {
                let after = report(&edited);
                assert_eq!(before.duration_ms, after.duration_ms);
                assert_eq!(before.total_buffer_kb, after.total_buffer_kb);
                assert_eq!(before.data_sources, after.data_sources);
            }
        }
    }

    #[test]
    fn unicode_byte_escapes_and_decoys_do_not_change_category_scope() {
        let text = MINIMAL.replace("\"gfx\"", r#""\u0067\U00000066x""#);
        assert_eq!(report(&text).atrace_categories, vec!["gfx"]);
        let text = format!(
            "{MINIMAL}other {{ atrace_categories: 'decoy' bytes: '\\377' }} # atrace_categories: 'comment'\n"
        );
        let changed = set_atrace_categories(&text, &set(&["view"])).unwrap();
        assert!(changed.contains("other { atrace_categories: 'decoy' bytes: '\\377' }"));
        assert!(changed.contains("# atrace_categories: 'comment'"));
        assert_eq!(report(&changed).atrace_categories, vec!["view"]);
        for invalid in [r#""\400""#, r#""\uD800""#, r#""\UFFFFFFFF""#, r#""\x""#] {
            assert!(!report(&MINIMAL.replace("\"gfx\"", invalid)).can_start());
        }
    }

    #[test]
    fn exact_file_limit_and_nonregular_draft_failures_preserve_data() {
        let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = temp.path().join("exact.pbtxt");
        fs::write(&path, vec![b' '; MAX_CONFIG_BYTES]).unwrap();
        assert_eq!(read_config(&path).unwrap().len(), MAX_CONFIG_BYTES);
        fs::create_dir(temp.path().join(DRAFT_NAME)).unwrap();
        assert!(load_draft(temp.path()).is_err());
        assert!(save_draft(temp.path(), default_text()).is_err());
        assert!(temp.path().join(DRAFT_NAME).is_dir());
    }

    #[cfg(unix)]
    #[test]
    fn symlink_drafts_and_imports_never_overwrite_link_targets() {
        let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let target = temp.path().join("original.pbtxt");
        fs::write(&target, MINIMAL).unwrap();
        std::os::unix::fs::symlink(&target, temp.path().join(DRAFT_NAME)).unwrap();
        assert!(load_draft(temp.path()).is_err());
        assert!(save_draft(temp.path(), default_text()).is_err());
        assert!(read_config(&temp.path().join(DRAFT_NAME)).is_err());
        assert_eq!(fs::read_to_string(&target).unwrap(), MINIMAL);
    }
}
