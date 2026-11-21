//! Private entry transport, not diagnostic output. Never persisted by this engine.
use crate::json::{self, Parser};
use crate::MAX_ENTRIES;
use std::collections::BTreeSet;
use std::io::{Read, Write};

struct Stream<'a, W> {
    output: &'a mut W,
    count: u64,
}

impl<W: Write> Stream<'_, W> {
    fn frame(&mut self, label: &str, bytes: &[u8]) -> Result<(), String> {
        writeln!(self.output, "{label}\t{}", bytes.len())
            .and_then(|_| self.output.write_all(bytes))
            .and_then(|_| self.output.write_all(b"\n"))
            .map_err(|_| "HAR entry stream write failed".into())
    }

    fn envelope<R: Read>(&mut self, parser: &mut Parser<R>, log: bool) -> Result<(), String> {
        parser.expect(b'{')?;
        let mut keys = BTreeSet::new();
        let mut key_bytes = 0;
        if !parser.eat(b'}')? {
            loop {
                let key = parser.key()?;
                key_bytes += key.len();
                if keys.len() >= json::MAX_MEMBERS || key_bytes > json::MAX_VALUE_BYTES as usize {
                    return Err("HAR envelope budget exceeded".into());
                }
                if !keys.insert(key.clone()) {
                    return Err("duplicate JSON key".into());
                }
                parser.expect(b':')?;
                match (log, key.as_str()) {
                    (false, "log") => self.envelope(parser, true)?,
                    (true, "version") => {
                        let version = parser.bounded_value(2)?;
                        let version = version.text()?;
                        if !matches!(version, "1.1" | "1.2") {
                            return Err("unsupported HAR version".into());
                        }
                        self.frame("version", version.as_bytes())?;
                    }
                    (true, "creator") => self.frame("creator", &parser.bounded_raw_value(2)?)?,
                    (true, "entries") => {
                        parser.expect(b'[')?;
                        if !parser.eat(b']')? {
                            loop {
                                if self.count >= MAX_ENTRIES {
                                    return Err("HAR entry count budget exceeded".into());
                                }
                                let bytes = parser.bounded_raw_value(3)?;
                                self.frame(&format!("entry\t{}", self.count), &bytes)?;
                                self.count += 1;
                                if parser.eat(b']')? {
                                    break;
                                }
                                parser.expect(b',')?;
                            }
                        }
                    }
                    _ => {
                        parser.bounded_value(if log { 2 } else { 1 })?;
                    }
                }
                if parser.eat(b'}')? {
                    break;
                }
                parser.expect(b',')?;
            }
        }
        if (!log && !keys.contains("log"))
            || (log && (!keys.contains("entries") || !keys.contains("version")))
        {
            return Err("missing HAR container or version".into());
        }
        Ok(())
    }
}

/// Bounded, backpressured transport. Partial frames MUST be discarded unless an end
/// record, EOF, and successful process exit are all observed by the consumer.
pub fn stream_entries(reader: impl Read, mut output: impl Write) -> Result<(), String> {
    output
        .write_all(b"APS-HAR-ENTRIES\t1\n")
        .map_err(|_| "HAR entry stream write failed")?;
    let mut stream = Stream {
        output: &mut output,
        count: 0,
    };
    let mut parser = Parser::new(reader);
    stream.envelope(&mut parser, false)?;
    parser.whitespace()?;
    if parser.peek()?.is_some() {
        return Err("trailing JSON data".into());
    }
    writeln!(stream.output, "end\t{}", stream.count)
        .map_err(|_| "HAR entry stream write failed".into())
}
