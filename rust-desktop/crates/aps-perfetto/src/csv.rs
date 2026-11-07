use crate::PerfettoError;

/// Strict enough to reject malformed/multiple result sets instead of quietly
/// gluing cells together. Matches quoted headers, numbers and [NULL] from TP.
pub(crate) fn parse(bytes: &[u8], max_rows: usize) -> Result<Vec<Vec<String>>, PerfettoError> {
    let input = std::str::from_utf8(bytes).map_err(|_| PerfettoError::InvalidResult)?;
    let mut rows = Vec::new();
    let mut row = Vec::new();
    let mut field = String::new();
    let mut quoted = false;
    let mut closed = false;
    let mut chars = input.chars().peekable();
    while let Some(character) = chars.next() {
        match character {
            '"' if quoted && chars.peek() == Some(&'"') => {
                field.push('"');
                chars.next();
            }
            '"' if quoted => {
                quoted = false;
                closed = true;
            }
            '"' if field.is_empty() && !closed => quoted = true,
            ',' if !quoted => {
                row.push(std::mem::take(&mut field));
                closed = false;
            }
            '\n' if !quoted => {
                row.push(std::mem::take(&mut field));
                if rows.len() >= max_rows {
                    return Err(PerfettoError::InvalidResult);
                }
                rows.push(std::mem::take(&mut row));
                closed = false;
            }
            '\r' if !quoted && chars.peek() == Some(&'\n') => {}
            _ if closed => return Err(PerfettoError::InvalidResult),
            _ => field.push(character),
        }
        if field.len() > 32 * 1024 || row.len() > 16 {
            return Err(PerfettoError::InvalidResult);
        }
    }
    if quoted {
        return Err(PerfettoError::InvalidResult);
    }
    if !field.is_empty() || !row.is_empty() || closed {
        row.push(field);
        if rows.len() >= max_rows {
            return Err(PerfettoError::InvalidResult);
        }
        rows.push(row);
    }
    Ok(rows)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reads_processor_csv_with_null_and_embedded_quotes() {
        assert_eq!(
            parse(b"\"id\",\"name\"\r\n1,\"a\"\"b\"\r\n2,\"[NULL]\"\n", 3).unwrap(),
            vec![vec!["id", "name"], vec!["1", "a\"b"], vec!["2", "[NULL]"]]
        );
    }
    #[test]
    fn rejects_truncated_quotes_extra_data_and_row_excess() {
        for bytes in [b"\"id".as_slice(), b"\"id\"bad\n", b"a\nb\nc\n"] {
            assert!(parse(bytes, 2).is_err());
        }
    }
}
