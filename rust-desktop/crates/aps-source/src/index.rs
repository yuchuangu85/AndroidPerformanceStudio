use crate::{SourceLanguage, SourceSymbol, SourceSymbolKind};

#[derive(Debug)]
struct Token {
    word: String,
    line: usize,
    punctuation: bool,
}
/// Lightweight declaration hints, not a compiler parser. Comments/string bodies
/// are excluded; unresolved syntax cannot produce an exact navigation claim.
pub(crate) fn symbols(
    path: &str,
    text: &str,
    language: SourceLanguage,
    limit: usize,
) -> (Vec<SourceSymbol>, bool) {
    if language == SourceLanguage::Xml {
        return xml_symbols(path, text, limit);
    }
    let tokens = tokenize(text);
    let mut package = String::new();
    for (index, token) in tokens.iter().enumerate() {
        if token.word == "package" {
            let line = token.line;
            let mut parts = String::new();
            let mut expect_name = true;
            for next in tokens.iter().skip(index + 1) {
                if next.line != line || next.word == ";" {
                    break;
                }
                if (expect_name && next.punctuation) || (!expect_name && next.word != ".") {
                    parts.clear();
                    break;
                }
                if parts.len() + next.word.len() > 256 {
                    parts.clear();
                    break;
                }
                parts.push_str(&next.word);
                expect_name = !expect_name;
            }
            package = parts;
            break;
        }
    }
    let mut results = Vec::new();
    let mut omitted = false;
    for (index, token) in tokens.iter().enumerate() {
        let next = tokens.get(index + 1);
        let declaration = match token.word.as_str() {
            "class" | "interface" | "object" | "struct" | "enum" | "trait" | "record" => next
                .filter(|next| !next.punctuation)
                .map(|next| (next, SourceSymbolKind::Type)),
            "fun" | "fn" => next
                .filter(|next| !next.punctuation)
                .map(|next| (next, SourceSymbolKind::Function)),
            _ => None,
        };
        let declaration = declaration.or_else(|| {
            if !matches!(
                language,
                SourceLanguage::Java | SourceLanguage::C | SourceLanguage::Cpp
            ) || token.punctuation
                || next.is_none_or(|next| next.word != "(")
            {
                return None;
            }
            if [
                "if",
                "for",
                "while",
                "switch",
                "catch",
                "return",
                "new",
                "sizeof",
                "synchronized",
            ]
            .contains(&token.word.as_str())
            {
                return None;
            }
            // Require a type/modifier token immediately before the name, same
            // source line. This intentionally under-indexes complex signatures.
            let previous = index.checked_sub(1).and_then(|i| tokens.get(i))?;
            if previous.line != token.line
                || previous.word == "return"
                || previous.word == "new"
                || previous.punctuation && previous.word != "*" && previous.word != "&"
            {
                return None;
            }
            Some((
                token,
                if language == SourceLanguage::Java {
                    SourceSymbolKind::Method
                } else {
                    SourceSymbolKind::Function
                },
            ))
        });
        if let Some((name, kind)) = declaration {
            if results.len() == limit {
                omitted = true;
                break;
            }
            if name.word.len() > 256 {
                continue;
            }
            let qualified_name = if package.is_empty() {
                name.word.clone()
            } else {
                format!("{package}.{}", name.word)
            };
            results.push(SourceSymbol {
                relative_path: path.into(),
                name: name.word.clone(),
                qualified_name,
                kind,
                line: name.line,
            });
        }
    }
    results.dedup_by(|a, b| a.line == b.line && a.name == b.name && a.kind == b.kind);
    (results, omitted)
}
fn tokenize(text: &str) -> Vec<Token> {
    let bytes = text.as_bytes();
    let mut result = Vec::new();
    let mut index = 0;
    let mut line = 1;
    let mut block_comment = 0usize;
    while index < bytes.len() {
        if bytes[index] == b'\n' {
            line += 1;
            index += 1;
            continue;
        }
        if block_comment != 0 {
            if bytes.get(index..index + 2) == Some(b"/*") {
                block_comment += 1;
                index += 2;
            } else if bytes.get(index..index + 2) == Some(b"*/") {
                block_comment -= 1;
                index += 2;
            } else {
                index += 1;
            }
            continue;
        }
        if bytes.get(index..index + 2) == Some(b"//") {
            while index < bytes.len() && bytes[index] != b'\n' {
                index += 1;
            }
            continue;
        }
        if bytes.get(index..index + 2) == Some(b"/*") {
            block_comment = 1;
            index += 2;
            continue;
        }
        if bytes.get(index..index + 3) == Some(b"\"\"\"") {
            index += 3;
            while index < bytes.len() && bytes.get(index..index + 3) != Some(b"\"\"\"") {
                if bytes[index] == b'\n' {
                    line += 1;
                }
                index += 1;
            }
            index = (index + 3).min(bytes.len());
            continue;
        }
        if bytes[index] == b'"' || bytes[index] == b'\'' {
            let quote = bytes[index];
            index += 1;
            while index < bytes.len() {
                if bytes[index] == b'\n' {
                    line += 1;
                }
                if bytes[index] == b'\\' {
                    index = (index + 2).min(bytes.len());
                    continue;
                }
                if bytes[index] == quote {
                    index += 1;
                    break;
                }
                index += 1;
            }
            continue;
        }
        if bytes[index].is_ascii_alphabetic() || bytes[index] == b'_' {
            let begin = index;
            index += 1;
            while index < bytes.len()
                && (bytes[index].is_ascii_alphanumeric() || bytes[index] == b'_')
            {
                index += 1;
            }
            // Oversized identifiers are skipped, never copied into the index.
            if index - begin <= 256 {
                result.push(Token {
                    word: text[begin..index].into(),
                    line,
                    punctuation: false,
                });
            }
        } else {
            if b"().;{}*&".contains(&bytes[index]) {
                result.push(Token {
                    word: (bytes[index] as char).to_string(),
                    line,
                    punctuation: true,
                });
            }
            index += 1;
        }
    }
    result
}
fn xml_symbols(path: &str, text: &str, limit: usize) -> (Vec<SourceSymbol>, bool) {
    let mut rows = Vec::new();
    let mut in_comment = false;
    for (line_index, line) in text.lines().enumerate() {
        let mut rest = line;
        while !rest.is_empty() {
            if in_comment {
                if let Some(end) = rest.find("-->") {
                    rest = &rest[end + 3..];
                    in_comment = false;
                } else {
                    break;
                }
                continue;
            }
            if rest.starts_with("<!--") {
                in_comment = true;
                rest = &rest[4..];
                continue;
            }
            if let Some(position) = rest.find('@') {
                if let Some(comment) = rest.find("<!--") {
                    if comment < position {
                        in_comment = true;
                        rest = &rest[comment + 4..];
                        continue;
                    }
                }
                rest = &rest[position + 1..];
                let value = rest.strip_prefix('+').unwrap_or(rest);
                let count = value
                    .bytes()
                    .take_while(|byte| byte.is_ascii_alphanumeric() || b"_/".contains(byte))
                    .count();
                let resource = &value[..count];
                if resource.contains('/') && resource.len() <= 256 {
                    if rows.len() == limit {
                        return (rows, true);
                    }
                    rows.push(SourceSymbol {
                        relative_path: path.into(),
                        name: resource.into(),
                        qualified_name: resource.into(),
                        kind: SourceSymbolKind::Resource,
                        line: line_index + 1,
                    });
                }
                rest = &value[count..];
            } else {
                if rest.contains("<!--") {
                    in_comment = true;
                }
                break;
            }
        }
    }
    (rows, false)
}
