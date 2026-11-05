//! Strict, bounded JSON values; the HAR envelope consumes entries independently.
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader, Read};

pub const MAX_DEPTH: usize = 64;
pub const MAX_STRING: usize = 256 * 1024;
pub const MAX_VALUE_BYTES: u64 = 2 * 1024 * 1024;
pub const MAX_VALUES: usize = 50_000;
pub const MAX_MEMBERS: usize = 4096;

#[derive(Debug)]
pub enum Value {
    Null,
    Bool,
    Number(String),
    String(String),
    Array,
    Object(BTreeMap<String, Value>),
}

impl Value {
    pub fn object(&self) -> Result<&BTreeMap<String, Value>, String> {
        match self {
            Self::Object(value) => Ok(value),
            _ => Err("expected JSON object".into()),
        }
    }
    pub fn text(&self) -> Result<&str, String> {
        match self {
            Self::String(value) => Ok(value),
            _ => Err("expected JSON string".into()),
        }
    }
    pub fn number(&self) -> Result<f64, String> {
        match self {
            Self::Number(value) => value
                .parse::<f64>()
                .ok()
                .filter(|v| v.is_finite())
                .ok_or_else(|| "number outside finite f64 range".into()),
            _ => Err("expected JSON number".into()),
        }
    }
    pub fn integer(&self) -> Result<i64, String> {
        match self {
            Self::Number(value) => value
                .parse()
                .map_err(|_| "expected signed 64-bit integer".into()),
            _ => Err("expected JSON integer".into()),
        }
    }
}

pub struct Parser<R: Read> {
    reader: BufReader<R>,
    position: u64,
    end: Option<u64>,
    remaining: usize,
    capture: Option<Vec<u8>>,
}

impl<R: Read> Parser<R> {
    pub fn new(reader: R) -> Self {
        Self {
            reader: BufReader::new(reader),
            position: 0,
            end: None,
            remaining: MAX_VALUES,
            capture: None,
        }
    }
    pub fn peek(&mut self) -> Result<Option<u8>, String> {
        loop {
            match self.reader.fill_buf() {
                Ok(bytes) => return Ok(bytes.first().copied()),
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => return Err("HAR read error".into()),
            }
        }
    }
    fn byte(&mut self) -> Result<u8, String> {
        if self.end.is_some_and(|end| self.position >= end) {
            return Err("JSON value exceeds 2 MiB byte budget".into());
        }
        let value = self.peek()?.ok_or("truncated JSON")?;
        self.reader.consume(1);
        if let Some(bytes) = &mut self.capture {
            bytes.push(value);
        }
        self.position = self
            .position
            .checked_add(1)
            .ok_or("input offset overflow")?;
        Ok(value)
    }
    pub fn whitespace(&mut self) -> Result<(), String> {
        while matches!(self.peek()?, Some(b' ' | b'\n' | b'\r' | b'\t')) {
            self.byte()?;
        }
        Ok(())
    }
    pub fn expect(&mut self, expected: u8) -> Result<(), String> {
        self.whitespace()?;
        if self.byte()? != expected {
            return Err("unexpected JSON token".into());
        }
        Ok(())
    }
    pub fn eat(&mut self, expected: u8) -> Result<bool, String> {
        self.whitespace()?;
        if self.peek()? == Some(expected) {
            self.byte()?;
            Ok(true)
        } else {
            Ok(false)
        }
    }
    pub fn key(&mut self) -> Result<String, String> {
        self.whitespace()?;
        self.string()
    }
    /// A bounded value is independent of other entries, including skipped metadata.
    pub fn bounded_value(&mut self, depth: usize) -> Result<Value, String> {
        self.whitespace()?;
        self.end = Some(
            self.position
                .checked_add(MAX_VALUE_BYTES)
                .ok_or("offset overflow")?,
        );
        self.remaining = MAX_VALUES;
        let result = self.value(depth);
        self.end = None;
        result
    }
    /// Keep one validated original value, including arrays omitted by the summary model.
    pub fn bounded_raw_value(&mut self, depth: usize) -> Result<Vec<u8>, String> {
        self.whitespace()?;
        self.capture = Some(Vec::new());
        let result = self.bounded_value(depth);
        let bytes = self.capture.take().unwrap();
        result?;
        Ok(bytes)
    }
    fn hex4(&mut self) -> Result<u32, String> {
        let mut value = 0;
        for _ in 0..4 {
            let digit = match self.byte()? {
                byte @ b'0'..=b'9' => u32::from(byte - b'0'),
                byte @ b'a'..=b'f' => u32::from(byte - b'a' + 10),
                byte @ b'A'..=b'F' => u32::from(byte - b'A' + 10),
                _ => return Err("invalid JSON unicode escape".into()),
            };
            value = (value << 4) | digit;
        }
        Ok(value)
    }
    fn string(&mut self) -> Result<String, String> {
        if self.byte()? != b'"' {
            return Err("expected JSON string".into());
        }
        let mut bytes = Vec::new();
        let mut encoded = 0usize;
        loop {
            let byte = self.byte()?;
            encoded += 1;
            if encoded > MAX_STRING {
                return Err("JSON string exceeds 256 KiB budget".into());
            }
            match byte {
                b'"' => return String::from_utf8(bytes).map_err(|_| "invalid JSON UTF-8".into()),
                0..=31 => return Err("unescaped control character".into()),
                b'\\' => {
                    let escaped = self.byte()?;
                    encoded += 1;
                    match escaped {
                        b'"' | b'\\' | b'/' => bytes.push(escaped),
                        b'b' => bytes.push(8),
                        b'f' => bytes.push(12),
                        b'n' => bytes.push(b'\n'),
                        b'r' => bytes.push(b'\r'),
                        b't' => bytes.push(b'\t'),
                        b'u' => {
                            let high = self.hex4()?;
                            encoded += 4;
                            let code = if (0xd800..=0xdbff).contains(&high) {
                                if self.byte()? != b'\\' || self.byte()? != b'u' {
                                    return Err("unpaired JSON surrogate".into());
                                }
                                let low = self.hex4()?;
                                encoded += 6;
                                if !(0xdc00..=0xdfff).contains(&low) {
                                    return Err("unpaired JSON surrogate".into());
                                }
                                0x10000 + ((high - 0xd800) << 10) + low - 0xdc00
                            } else {
                                high
                            };
                            let character =
                                char::from_u32(code).ok_or("invalid JSON unicode scalar")?;
                            bytes.extend_from_slice(character.encode_utf8(&mut [0; 4]).as_bytes());
                        }
                        _ => return Err("invalid JSON escape".into()),
                    }
                }
                _ => bytes.push(byte),
            }
            if bytes.len() > MAX_STRING || encoded > MAX_STRING {
                return Err("JSON string exceeds 256 KiB budget".into());
            }
        }
    }
    fn digits(&mut self, bytes: &mut Vec<u8>) -> Result<usize, String> {
        let mut count = 0;
        while matches!(self.peek()?, Some(b'0'..=b'9')) {
            if bytes.len() >= 128 {
                return Err("JSON number exceeds 128 byte budget".into());
            }
            bytes.push(self.byte()?);
            count += 1;
        }
        Ok(count)
    }
    fn number(&mut self) -> Result<Value, String> {
        let mut bytes = Vec::new();
        if self.peek()? == Some(b'-') {
            bytes.push(self.byte()?);
        }
        match self.peek()? {
            Some(b'0') => bytes.push(self.byte()?),
            Some(b'1'..=b'9') => {
                self.digits(&mut bytes)?;
            }
            _ => return Err("invalid JSON number".into()),
        }
        if self.peek()? == Some(b'.') {
            bytes.push(self.byte()?);
            if self.digits(&mut bytes)? == 0 {
                return Err("missing fractional digits".into());
            }
        }
        if matches!(self.peek()?, Some(b'e' | b'E')) {
            bytes.push(self.byte()?);
            if matches!(self.peek()?, Some(b'+' | b'-')) {
                bytes.push(self.byte()?);
            }
            if self.digits(&mut bytes)? == 0 {
                return Err("missing exponent digits".into());
            }
        }
        if bytes.len() > 128 {
            return Err("JSON number exceeds 128 byte budget".into());
        }
        let value = Value::Number(String::from_utf8(bytes).map_err(|_| "invalid number")?);
        value.number()?;
        Ok(value)
    }
    fn literal(&mut self, expected: &[u8], value: Value) -> Result<Value, String> {
        for byte in expected {
            if self.byte()? != *byte {
                return Err("invalid JSON literal".into());
            }
        }
        Ok(value)
    }
    fn value(&mut self, depth: usize) -> Result<Value, String> {
        if depth > MAX_DEPTH {
            return Err("JSON depth budget exceeded".into());
        }
        self.remaining = self
            .remaining
            .checked_sub(1)
            .ok_or("JSON value count budget exceeded")?;
        self.whitespace()?;
        match self.peek()? {
            Some(b'"') => self.string().map(Value::String),
            Some(b'-' | b'0'..=b'9') => self.number(),
            Some(b'n') => self.literal(b"null", Value::Null),
            Some(b't') => self.literal(b"true", Value::Bool),
            Some(b'f') => self.literal(b"false", Value::Bool),
            Some(b'[') => {
                self.byte()?;
                if !self.eat(b']')? {
                    loop {
                        // Arrays in HAR details are not part of the summary; validate and discard.
                        self.value(depth + 1)?;
                        if self.eat(b']')? {
                            break;
                        }
                        self.expect(b',')?;
                    }
                }
                Ok(Value::Array)
            }
            Some(b'{') => {
                self.byte()?;
                let mut fields = BTreeMap::new();
                if !self.eat(b'}')? {
                    loop {
                        if fields.len() >= MAX_MEMBERS {
                            return Err("JSON member budget exceeded".into());
                        }
                        let key = self.key()?;
                        self.expect(b':')?;
                        let value = self.value(depth + 1)?;
                        if fields.insert(key, value).is_some() {
                            return Err("duplicate JSON key".into());
                        }
                        if self.eat(b'}')? {
                            break;
                        }
                        self.expect(b',')?;
                    }
                }
                Ok(Value::Object(fields))
            }
            _ => Err("unexpected JSON value".into()),
        }
    }
}

pub fn quote(value: &str, output: &mut String) {
    use std::fmt::Write;
    output.push('"');
    for character in value.chars() {
        match character {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            character if character <= '\u{1f}' => {
                write!(output, "\\u{:04x}", character as u32).unwrap();
            }
            character => output.push(character),
        }
    }
    output.push('"');
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(bytes: &[u8]) -> Result<Value, String> {
        let mut parser = Parser::new(bytes);
        let value = parser.bounded_value(0)?;
        parser.whitespace()?;
        if parser.peek()?.is_some() {
            return Err("trailing input".into());
        }
        Ok(value)
    }

    #[test]
    fn strictly_validates_number_literal_and_container_grammar() {
        for input in [
            "0", "-0", "1.25", "-2e+3", "true", "false", "null", "{}", "[1,null]",
        ] {
            assert!(parse(input.as_bytes()).is_ok(), "{input}");
        }
        for input in [
            "01",
            "-01",
            "+1",
            "1.",
            "1e",
            "1e+",
            ".1",
            "--1",
            "1e9999",
            "NaN",
            "tru",
            "nullx",
            "[1,]",
            "{\"x\":0,}",
            "{\"x\":1,\"x\":2}",
        ] {
            assert!(parse(input.as_bytes()).is_err(), "{input}");
        }
    }

    #[test]
    fn unicode_escapes_utf8_and_output_quoting_are_strict() {
        let value = parse(br#""\u0041\uD83D\uDE00\n\"\\\/\b\f\r\t""#).unwrap();
        assert_eq!(value.text().unwrap(), "A\u{1f600}\n\"\\/\u{8}\u{c}\r\t");
        let text = "\u{0}\u{1f}\n\"\\\u{6d4b}\u{1f600}";
        let mut encoded = String::new();
        quote(text, &mut encoded);
        assert_eq!(parse(encoded.as_bytes()).unwrap().text().unwrap(), text);
        for input in [
            br#""\uD800""#.as_slice(),
            br#""\uDC00""#,
            br#""\uD800\u0041""#,
            br#""\uZZZZ""#,
            br#""\q""#,
            &[b'"', 255, b'"'],
            b"\"a\nb\"",
        ] {
            assert!(parse(input).is_err());
        }
    }

    #[test]
    fn every_truncated_prefix_is_rejected() {
        let input = br#"{"a":[true,null,-12.5e2,{"b":"\uD83D\uDE00"}]}"#;
        assert!(parse(input).is_ok());
        for end in 0..input.len() {
            assert!(parse(&input[..end]).is_err(), "prefix {end}");
        }
    }

    #[test]
    fn byte_node_member_and_numeric_token_budgets_are_enforced() {
        let members = (0..=MAX_MEMBERS)
            .map(|i| format!("\"{i}\":0"))
            .collect::<Vec<_>>()
            .join(",");
        assert!(parse(format!("{{{members}}}").as_bytes())
            .unwrap_err()
            .contains("member"));
        let nodes = format!("[{}0]", "0,".repeat(MAX_VALUES));
        assert!(parse(nodes.as_bytes()).unwrap_err().contains("count"));
        let bytes = format!(
            "[{}]",
            (0..10)
                .map(|_| format!("\"{}\"", "x".repeat(MAX_STRING - 2)))
                .collect::<Vec<_>>()
                .join(",")
        );
        assert!(parse(bytes.as_bytes()).unwrap_err().contains("byte budget"));
        assert!(parse("1".repeat(129).as_bytes())
            .unwrap_err()
            .contains("128"));
        let at_limit = format!("{}0{}", "[".repeat(MAX_DEPTH), "]".repeat(MAX_DEPTH));
        assert!(parse(at_limit.as_bytes()).is_ok());
        assert!(parse(format!("[{at_limit}]").as_bytes())
            .unwrap_err()
            .contains("depth"));
    }
}
