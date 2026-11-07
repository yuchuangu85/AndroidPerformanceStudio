use crate::PerfettoError;

/// Maximum bytes in one parsed cell; larger CSV output fails closed.
pub(crate) const MAX_CELL_BYTES: usize = 32 * 1024;
/// Maximum declared columns in one result set.
pub(crate) const MAX_COLUMNS: usize = 16;

/// Strict CSV decoder for the pinned Trace Processor formatter. It accepts
/// quoted cells (including embedded commas, CR and newlines, and doubled
/// quotes), CRLF/LF/CR row endings, empty cells and the literal "[NULL]" marker
/// (mapped to NULL by the caller). Anything ambiguous (a quote inside an
/// unquoted cell, data after a closing quote, an unterminated quote, too many
/// rows/columns/cell bytes, invalid UTF-8) fails closed instead of gluing cells
/// together. The upstream formatter does not escape quotes in trace text, so a
/// trace string containing a double quote is rejected here rather than
/// silently misparsed.
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
            '"' => return Err(PerfettoError::InvalidResult),
            ',' if !quoted => {
                row.push(std::mem::take(&mut field));
                closed = false;
            }
            '\n' if !quoted => {
                push_row(&mut rows, &mut row, &mut field, max_rows)?;
                closed = false;
            }
            '\r' if !quoted => {
                // Accept CRLF and a bare CR as row terminators outside quotes.
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                push_row(&mut rows, &mut row, &mut field, max_rows)?;
                closed = false;
            }
            _ if closed => return Err(PerfettoError::InvalidResult),
            _ => field.push(character),
        }
        if field.len() > MAX_CELL_BYTES || row.len() > MAX_COLUMNS {
            return Err(PerfettoError::InvalidResult);
        }
    }
    if quoted {
        return Err(PerfettoError::InvalidResult);
    }
    if !field.is_empty() || !row.is_empty() || closed {
        row.push(field);
        if row.len() > MAX_COLUMNS || rows.len() >= max_rows {
            return Err(PerfettoError::InvalidResult);
        }
        rows.push(row);
    }
    Ok(rows)
}

fn push_row(
    rows: &mut Vec<Vec<String>>,
    row: &mut Vec<String>,
    field: &mut String,
    max_rows: usize,
) -> Result<(), PerfettoError> {
    row.push(std::mem::take(field));
    if rows.len() >= max_rows {
        return Err(PerfettoError::InvalidResult);
    }
    rows.push(std::mem::take(row));
    Ok(())
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
    fn handles_quoted_commas_newlines_and_empty_cells() {
        assert_eq!(
            parse(b"\"a,b\",\"line1\nline2\",\"\"\n", 2).unwrap(),
            vec![vec!["a,b", "line1\nline2", ""]]
        );
        assert_eq!(
            parse(b"\"a,b\",\"carriage\rreturn\"\r", 2).unwrap(),
            vec![vec!["a,b", "carriage\rreturn"]]
        );
        assert_eq!(parse(b"x,,z", 1).unwrap(), vec![vec!["x", "", "z"]]);
    }

    #[test]
    fn distinguishes_empty_from_null_marker_and_keeps_unicode() {
        assert_eq!(
            parse("\"[NULL]\",\"\",\"中文\"\n".as_bytes(), 2).unwrap(),
            vec![vec!["[NULL]", "", "中文"]]
        );
    }

    #[test]
    fn rejects_truncated_quotes_extra_data_and_row_excess() {
        for bytes in [b"\"id".as_slice(), b"\"id\"bad\n", b"a\nb\nc\n"] {
            assert!(parse(bytes, 2).is_err());
        }
        // A quote that appears inside an unquoted cell is ambiguous.
        assert!(parse(b"a\"b,c\n", 2).is_err());
        // Data after a closing quote is ambiguous.
        assert!(parse(b"\"a\"b\n", 2).is_err());
    }

    #[test]
    fn rejects_oversized_cells_and_too_many_columns() {
        let cell = "x".repeat(MAX_CELL_BYTES + 1);
        assert!(parse(cell.as_bytes(), 2).is_err());
        let columns = vec!["1"; MAX_COLUMNS + 1].join(",");
        assert!(parse(columns.as_bytes(), 2).is_err());
    }
}
